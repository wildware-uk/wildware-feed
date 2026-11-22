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
import dev.wildware.feed.server.mcp.registerPairingTools
import dev.wildware.feed.server.mcp.registerSubscriptionTools
import dev.wildware.feed.server.mcp.registerSecretTools
import dev.wildware.feed.server.mcp.registerFeedbackTools
import dev.wildware.feed.server.mcp.registerBlockTools
import dev.wildware.feed.server.push.startApplePush

/**
 * Everything one running server holds: its settings, the database and the in-memory state that a
 * restart forgets (limiters). The desktop app builds one of these to host the server itself.
 */
class App(val config: Config, val db: Database, val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    /** What [close] puts back: a server with many teams makes its process strict about teams. */
    private val strictBefore = dev.wildware.feed.server.db.Tenant.strict

    init {
        config.rowSecurity?.let { on -> if (on != db.rowSecurity) dev.wildware.feed.server.db.TeamTables.setRowSecurity(db, on) }
        dev.wildware.feed.server.http.OwnerCookie.hostOnly = config.multiTeam
        // With many teams, work that forgot to say its team fails, rather than landing in the home
        // team. (Config.load refuses many teams without Postgres.)
        if (config.multiTeam) dev.wildware.feed.server.db.Tenant.strict = true
        // An app's csp may name none of this server's own addresses: the home team's, or any team's.
        dev.wildware.feed.server.domain.Apps.ownOrigins = listOf(config.origin, config.publicBaseUrl)
            .mapNotNull { dev.wildware.feed.server.domain.WebUrl.parse(it)?.origin }.toSet()
        dev.wildware.feed.server.domain.Apps.ownDomain = config.teamDomain
    }

    val agents = Agents(db, config.tokenSecret, clock)
    val devices = Devices(db, config.tokenSecret, clock)

    /** The people of the team and their invites (migration 055). */
    val people = dev.wildware.feed.server.domain.People(db, config.tokenSecret, config.adminPasswordHash, clock)
    val reads = Reads(db, clock)

    /** The web app's bundle, served at the root, when this server has one ([Config.webDir]). */
    val web: dev.wildware.feed.server.http.WebApp? =
        config.webDir?.let { dev.wildware.feed.server.http.WebApp(java.io.File(it)) }?.takeIf { it.present }

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

    /**
     * With [Config.trustLoopback] (the desktop app): every client on this computer shares one budget
     * of 20 wrong passwords in 15 minutes, apart from [loginLimiter], so a bad script there can't
     * lock out phones on the network, and nothing there can guess without end.
     */
    val loopbackLimiter = SlidingLimiter(limit = 20, windowMs = 900_000, clock = clock)

    /** Ten wrong passwords an hour per email, from anywhere (docs/TEAMS.md §10). */
    val emailLimiter = SlidingLimiter(limit = 10, windowMs = 3_600_000, clock = clock)

    /** Invites looked at or accepted: 20 an hour per address. */
    val inviteLimiter = SlidingLimiter(limit = 20, windowMs = 3_600_000, clock = clock)

    /** 120 requests a minute per agent token, one limiter per route as in the TS server. */
    val mcpLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)

    /** With many teams: 1,200 MCP calls a minute across all of one team's agents, and 50 open streams a team. */
    val teamMcpLimiter = SlidingLimiter(limit = 1200, windowMs = 60_000, clock = clock)
    val teamStreams = dev.wildware.feed.server.http.TeamStreams()
    val agentStreamLimiter = SlidingLimiter(limit = 120, windowMs = 60_000, clock = clock)

    /** Six attempts a minute per upload token, successful ones included (spec/07 §4). */
    val uploadLimiter = SlidingLimiter(limit = 6, windowMs = 60_000, clock = clock)

    /**
     * Agent pairing, keyed by client address since the caller has no token: five pairings started
     * in ten minutes, and 60 polls a minute (each waits up to 25 seconds, so one agent makes about 3).
     */
    val pairStartLimiter = SlidingLimiter(limit = 5, windowMs = 600_000, clock = clock)
    val pairPollLimiter = SlidingLimiter(limit = 60, windowMs = 60_000, clock = clock)

    /**
     * OAuth for MCP clients (migration 051), keyed by client address: 20 registrations an hour, 60
     * authorize-page loads and 60 token calls a minute. Wrong passwords also count on [loginLimiter].
     */
    val oauthRegisterLimiter = SlidingLimiter(limit = 20, windowMs = 3_600_000, clock = clock)
    val oauthAuthorizeLimiter = SlidingLimiter(limit = 60, windowMs = 60_000, clock = clock)
    val oauthTokenLimiter = SlidingLimiter(limit = 60, windowMs = 60_000, clock = clock)

    /** Secret links (`/api/agent/secret/<token>`), spent or not: 60 a minute per client address. */
    val secretLinkLimiter = SlidingLimiter(limit = 60, windowMs = 60_000, clock = clock)

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

    /** The teams, their addresses and limits (docs/HOSTING.md §4, §7). */
    val teams = dev.wildware.feed.server.domain.Teams(db, config, mediaPaths, clock).also { teams ->
        people.checkSeat = { teams.checkSeat() }
        mediaPaths.homeHeir = { teams.homeHeir() }
    }

    /** The admin API's own state: idempotency and its rate limit (docs/HOSTING.md §7). */
    val admin = dev.wildware.feed.server.http.AdminApi(this)

    /** Reserving uploads and taking their bytes. */
    val uploads = dev.wildware.feed.server.media.Uploads(
        db, mediaPaths, config.tokenSecret, { teams.address() }, config.maxImageBytes, config.maxVideoBytes, config.maxFileBytes, clock,
    ).also { it.checkStorage = { bytes -> teams.checkStorage(bytes) } }

    /** Thumbnails, posters and transcodes for uploaded media. */
    val derive = dev.wildware.feed.server.media.Derive(
        db, bus, mediaPaths, dev.wildware.feed.server.media.FfmpegTools(threads = config.ffmpegThreads), clock, config.deriveWorkers,
        perTeam = if (config.multiTeam) 1 else dev.wildware.feed.server.media.Derive.BATCH,
    )

    /** How long media is kept (migration 048), and the nightly removal. */
    val retention = dev.wildware.feed.server.media.Retention(db, bus, mediaPaths, clock)

    /** Which file answers a media URL. */
    val serve = dev.wildware.feed.server.media.Serve(db, mediaPaths)

    /** Owner requests, the bell, push subscriptions and app state (the request area). */
    val requests = Requests(db, bus, reads, clock) { config.holdSeconds * 1000L }.also { it.people = people }

    /** Feedback and feature requests (migration 067): an agent's waits on one of [requests]. */
    val feedback = dev.wildware.feed.server.domain.Feedback(this).also { fb -> requests.onSettled = { request, state -> fb.settled(request, state) } }
    val notifications = Notifications(db, bus, clock)
    val appState = AppState(db, clock)

    /** Agents asking to join, and the owner allowing or denying them (migration 050). */
    val pairings = dev.wildware.feed.server.domain.Pairings(db, bus, config.tokenSecret, clock)

    /** Secrets the owner and agents share, sealed under SECRETS_KEY (migration 054, docs/SECRETS.md). */
    val secrets = dev.wildware.feed.server.domain.Secrets(
        db, bus, config.secretsKeys?.let { dev.wildware.feed.server.auth.SecretBox(it.current, it.old) },
        config.tokenSecret, { teams.address() }, clock,
    )

    /** MCP clients connecting over OAuth, allowed by the owner in the browser (migration 051). */
    val oauth = dev.wildware.feed.server.domain.OAuth(db, config.tokenSecret, clock)

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

    /**
     * Run [block] every [periodMs] as [every] does, once for each team in turn, inside that team
     * (docs/HOSTING.md §3: one process sweeps every team). One team's failure doesn't stop the rest.
     */
    fun everyTeam(periodMs: Long, name: String = "job", block: () -> Unit) = every(periodMs, name) { forEachTeam(name, block) }

    /** [block] once inside each team that is open for work. */
    fun forEachTeam(name: String = "job", block: () -> Unit) {
        for (team in teamIds()) {
            try {
                dev.wildware.feed.server.db.Tenant.with(team, block)
            } catch (e: Exception) {
                System.err.println("$name failed for a team: $e")
            }
        }
    }

    /** Every open team's id, oldest first: not one being deleted. */
    fun teamIds(): List<String> = db.query("SELECT id FROM teams WHERE state = 'active' ORDER BY seq") { it.getString(1) }

    private var stopApplePush: () -> Unit = {}

    /** After SECRETS_KEY changes: re-seal what is still under an old key, and say how it went (counts only). */
    private fun resealSecrets() {
        var done = 0
        var unopened = 0
        forEachTeam("secret reseal") { secrets.reseal().let { (d, u) -> done += d; unopened += u } }
        if (done > 0) println("secrets: re-sealed $done under the current SECRETS_KEY; SECRETS_KEY_OLD can go once nothing is left")
        if (unopened > 0) System.err.println("secrets: $unopened are sealed under a key that is neither SECRETS_KEY nor in SECRETS_KEY_OLD")
    }

    /** The background jobs (spec/08 §1). Called once when the server starts serving. */
    fun startJobs() {
        everyTeam(dev.wildware.feed.server.domain.Sessions.SWEEP_INTERVAL_MS, "presence sweep") { sessions.sweep() }
        everyTeam(dev.wildware.feed.server.domain.Requests.SWEEP_INTERVAL_MS, "request sweep") { requests.expireDue() }
        everyTeam(dev.wildware.feed.server.domain.Pairings.SWEEP_INTERVAL_MS, "pairing sweep") { pairings.expireDue() }
        everyTeam(dev.wildware.feed.server.domain.OAuth.SWEEP_INTERVAL_MS, "oauth sweep") { oauth.sweep() }
        everyTeam(dev.wildware.feed.server.domain.Secrets.SWEEP_INTERVAL_MS, "secret sweep") { secrets.sweep() }
        resealSecrets()
        everyTeam(dev.wildware.feed.server.media.Derive.WORKER_INTERVAL_MS, "media derive") { derive.tick() }
        everyTeam(15 * 60_000L, "media sweep") { uploads.sweep() }
        everyTeam(dev.wildware.feed.server.media.Retention.CHECK_INTERVAL_MS, "media retention") { retention.tick() }
        everyTeam(10 * 60_000L, "sign-in code sweep") { teams.sweepCodes() }
        every(60_000L, "team deletion") { teams.purgeDue() }
        every(60 * 60_000L, "admin request sweep") { admin.sweep() }
        stopApplePush = startApplePush()
    }

    init {
        tools.registerFeedTools(this)
        tools.registerEmbedAppTools(this)
        tools.registerRequestTools(this)
        tools.registerSessionTools(this)
        tools.registerMediaTools(this)
        tools.registerPairingTools(this)
        tools.registerSubscriptionTools(this)
        tools.registerSecretTools(this)
        tools.registerFeedbackTools(this)
        tools.registerBlockTools(this)
    }

    override fun close() {
        if (config.multiTeam) {
            dev.wildware.feed.server.db.Tenant.strict = strictBefore
            dev.wildware.feed.server.http.OwnerCookie.hostOnly = false
        }
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
