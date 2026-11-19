package dev.wildware.feed.server

import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.AppState
import dev.wildware.feed.server.domain.Notifications
import dev.wildware.feed.server.domain.Requests
import dev.wildware.feed.server.domain.Devices
import dev.wildware.feed.server.domain.Reads
import dev.wildware.feed.server.mcp.registerEmbedAppTools
import dev.wildware.feed.server.mcp.registerFeedTools
import dev.wildware.feed.server.mcp.registerRequestTools
import dev.wildware.feed.server.mcp.registerSessionTools
import dev.wildware.feed.server.mcp.registerMediaTools
import dev.wildware.feed.server.push.startApplePush

/**
 * Everything one running server holds: its settings, the database and the in-memory state that a
 * restart forgets (limiters). The desktop app builds one of these to host the server itself.
 */
class App(val config: Config, val db: Database, val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    val agents = Agents(db, config.tokenSecret, clock)
    val devices = Devices(db, config.tokenSecret, clock)
    val reads = Reads(db, clock)

    /** Every MCP tool; feature files register theirs (see `mcp/`). */
    val tools = dev.wildware.feed.server.mcp.ToolRegistry()

    /** The event bus: seq, boot id, replay ring. Lives and dies with this process. */
    val bus = dev.wildware.feed.server.events.Bus(clock = clock).also { bus ->
        bus.beforePublish = { type ->
            check(!db.heldByCurrentThread) { "$type published while holding the database lock; publish after the write" }
        }
    }

    /** Shared by the login form and device sign-in: 5 wrong guesses in 15 minutes per address. */
    val loginLimiter = SlidingLimiter(limit = 5, windowMs = 900_000, clock = clock)

    /** 120 requests a minute per agent token, one limiter per route as in the TS server. */
    val mcpLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)
    val agentStreamLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)

    /** Six attempts a minute per upload token, successful ones included (spec/07 §4). */
    val uploadLimiter = SlidingLimiter(limit = 6, windowMs = 60_000, clock = clock)

    /** Its own 120 a minute per agent token, apart from /mcp's (spec/07 §8). */
    val agentMediaLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)

    val mediaPaths = dev.wildware.feed.server.media.MediaPaths(
        config.dataDir,
        config.s3?.let {
            dev.wildware.feed.server.media.S3MediaStore(
                dev.wildware.feed.server.media.S3Client(it.endpoint, it.bucket, it.accessKeyId, it.secretAccessKey, it.region),
                java.nio.file.Path.of(config.dataDir, "tmp", "fetch").toAbsolutePath(),
            )
        },
    )

    /** Reserving uploads and taking their bytes. */
    val uploads = dev.wildware.feed.server.media.Uploads(
        db, mediaPaths, config.tokenSecret, config.publicBaseUrl, config.maxImageBytes, config.maxVideoBytes, config.maxFileBytes, clock,
    )

    /** Thumbnails, posters and transcodes for uploaded media. */
    val derive = dev.wildware.feed.server.media.Derive(
        db, bus, mediaPaths, dev.wildware.feed.server.media.FfmpegTools(threads = config.ffmpegThreads), clock, config.deriveWorkers,
    )

    /** How long media is kept (migration 048), and the nightly removal. */
    val retention = dev.wildware.feed.server.media.Retention(db, bus, mediaPaths, clock)

    /** Which file answers a media URL. */
    val serve = dev.wildware.feed.server.media.Serve(db, mediaPaths)

    /** Owner requests, the bell, push subscriptions and app state (the request area). */
    val requests = Requests(db, bus, reads, clock) { config.holdSeconds * 1000L }
    val notifications = Notifications(db, bus, clock)
    val appState = AppState(db, clock)

    /** The bell's recorder listens from the start, as the TS server's does. */
    private val stopNotificationRecorder = notifications.startRecorder()

    /** What is waiting for each agent, and handing messages over on its stream. */
    val work = dev.wildware.feed.server.domain.AgentWork(db, bus, clock)

    /** Agent sessions and presence. */
    val sessions = dev.wildware.feed.server.domain.Sessions(db, bus, work, clock)

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "feed-jobs").apply { isDaemon = true }
    }

    /** Run [block] every [periodMs], first after one period; a failure is logged and the job carries on. */
    fun every(periodMs: Long, name: String = "job", block: () -> Unit) {
        scheduler.scheduleAtFixedRate({
            try {
                block()
            } catch (e: Exception) {
                System.err.println("$name failed: $e")
            }
        }, periodMs, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private var stopApplePush: () -> Unit = {}

    /** The background jobs (spec/08 §1). Called once when the server starts serving. */
    fun startJobs() {
        every(dev.wildware.feed.server.domain.Sessions.SWEEP_INTERVAL_MS, "presence sweep") { sessions.sweep() }
        every(dev.wildware.feed.server.domain.Requests.SWEEP_INTERVAL_MS, "request sweep") { requests.expireDue() }
        every(dev.wildware.feed.server.media.Derive.WORKER_INTERVAL_MS, "media derive") { derive.tick() }
        every(15 * 60_000L, "media sweep") { uploads.sweep() }
        every(dev.wildware.feed.server.media.Retention.CHECK_INTERVAL_MS, "media retention") { retention.tick() }
        stopApplePush = startApplePush()
    }

    init {
        tools.registerFeedTools(this)
        tools.registerEmbedAppTools(this)
        tools.registerRequestTools(this)
        tools.registerSessionTools(this)
        tools.registerMediaTools(this)
    }

    override fun close() {
        stopNotificationRecorder()
        stopApplePush()
        derive.close()
        scheduler.shutdownNow()
        db.close()
    }

    companion object {
        fun open(config: Config) = App(config, Database.open(config))
    }
}
