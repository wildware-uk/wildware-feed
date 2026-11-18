package dev.wildware.agents

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where the live connection stands, for the "offline" line in the app. */
enum class StreamStatus {
    /** Not started, or stopped. */
    Idle,

    /** Opening the first connection. */
    Connecting,

    /** Connected: what's on screen is current. */
    Live,

    /** Lost the connection and trying again. What's on screen may be out of date. */
    Offline,

    /** The dashboard no longer accepts this device's key. Sign in again. */
    SignedOut,
}

/**
 * One event from `GET /api/stream`. [seq] is the server's event counter (the SSE `id:`), [payload]
 * the ids it is about. Events carry ids, not data: an arrival is a reason to re-read, never a row.
 */
data class StreamEvent(val type: String, val seq: Long, val payload: JsonObject?) {
    companion object {
        const val Resync = "resync"
    }
}

/** One raw SSE frame: the `event:`, `data:` and `id:` lines between two blank lines. */
internal data class SseFrame(val event: String, val data: String, val id: String?)

/**
 * Reads SSE frames off a response body. Comment lines (`: heartbeat`) count as life but are not
 * frames. [onBytes] is called for every line, so a watchdog can tell a quiet line from a dead one.
 */
internal class SseReader(private val channel: ByteReadChannel, private val onRetry: (Long) -> Unit = {}) {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false
    private var id: String? = null

    /** The next frame, or null when the body ends. [lineTimeoutMillis] with no line at all throws [StreamStalled]. */
    suspend fun next(lineTimeoutMillis: Long): SseFrame? {
        while (true) {
            // Wrapped so "no line in time" (null from the timeout) differs from "the body ended" (a null line).
            val read = withTimeoutOrNull(lineTimeoutMillis) { Read(channel.readUTF8Line()) } ?: throw StreamStalled()
            val line = read.line ?: return null
            if (line.isEmpty()) {
                val frame = if (hasData || event.isNotEmpty()) SseFrame(event.ifEmpty { "message" }, data.toString(), id) else null
                event = ""
                data.clear()
                hasData = false
                id = null
                if (frame != null) return frame
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                "id" -> if ('\u0000' !in value) id = value
                "retry" -> value.toLongOrNull()?.let(onRetry)
            }
        }
    }

    private class Read(val line: String?)
}

/** No bytes at all for longer than the server's heartbeat allows: the connection is dead but nobody said so. */
internal class StreamStalled : Exception("The live connection went quiet.")

/** What one connection reports back while it is open. */
internal interface StreamSink {
    fun opened()
    fun retryHint(millis: Long)
    suspend fun frame(frame: SseFrame)
}

/** Opens one connection to the stream and hands each frame on until it ends. Throws when it fails. */
internal fun interface StreamOpener {
    suspend fun open(cursor: Long, sink: StreamSink)
}

/** How the live stream retries. The defaults suit a phone: quick at first, then patient. */
data class StreamTiming(
    /** The first wait after a drop. The server's `retry:` hint replaces it once heard. */
    val firstRetryMillis: Long = 2_000,
    val maxRetryMillis: Long = 30_000,
    /** The server writes a heartbeat every 15 s; three missed means the line is dead. */
    val silenceMillis: Long = 45_000,
)

/**
 * The app's one connection to `GET /api/stream`, ported from the website's `src/web/stream.ts`.
 *
 * - Resume: the cursor is the newest seq seen. A reconnect sends it as `Last-Event-ID` (and as
 *   `last_event_id` in the query, as the website does), and the server replays exactly what was missed.
 * - `resync`: the server's word that the gap is too wide (or it restarted). Its seq is adopted as the
 *   cursor, not maxed, because a restarted server counts from zero again. Listeners refetch.
 * - Reconnect: on any drop, after a wait that doubles from [StreamTiming.firstRetryMillis] up to
 *   [StreamTiming.maxRetryMillis], and resets once a connection opens.
 * - A key the server no longer accepts (401) stops it: retrying would never work.
 *
 * [floor] is the lowest seq every reader of the stream already accounts for (each read is stamped
 * with one). A reconnect never resumes from below it, so a connection that opened before any event
 * happened still resumes from the snapshot rather than asking for nothing.
 */
class LiveStream internal constructor(
    private val opener: StreamOpener,
    private val scope: CoroutineScope,
    private val timing: StreamTiming,
) {
    constructor(client: DashboardClient, scope: CoroutineScope, timing: StreamTiming = StreamTiming()) :
        this(client.streamOpener(timing), scope, timing)

    private val mutableStatus = MutableStateFlow(StreamStatus.Idle)
    val status: StateFlow<StreamStatus> = mutableStatus.asStateFlow()

    /** The newest seq this app has seen on the stream: what a reconnect resumes from. */
    var cursor: Long = 0
        private set

    /** How many times it has connected. A test and a debug screen read it. */
    var connections: Int = 0
        private set

    internal var floor: () -> Long = { 0 }
    private var listener: (suspend (StreamEvent) -> Unit)? = null
    private var job: Job? = null
    private var retryMillis = timing.firstRetryMillis
    private val delivering = Mutex()

    /** Starts listening. [onEvent] runs for every event in order, one at a time. */
    fun start(onEvent: suspend (StreamEvent) -> Unit) {
        listener = onEvent
        if (job?.isActive == true) return
        mutableStatus.value = StreamStatus.Connecting
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableStatus.value = StreamStatus.Idle
    }

    /**
     * Brings the app back in line after it may have been asleep: on coming to the front, or when the
     * network comes back. Every reader refetches (a `resync`), and with [reconnect] the connection is
     * replaced too, because one that died while the app was suspended can look open.
     */
    fun revive(reconnect: Boolean) {
        if (listener == null) return
        if (reconnect || job?.isActive != true || mutableStatus.value == StreamStatus.SignedOut) {
            job?.cancel()
            retryMillis = timing.firstRetryMillis
            mutableStatus.value = if (mutableStatus.value == StreamStatus.Live) StreamStatus.Live else StreamStatus.Connecting
            job = scope.launch { run() }
        }
        scope.launch { deliver(StreamEvent(StreamEvent.Resync, cursor, null)) }
    }

    /** One event at a time, in order, whichever coroutine it came from. */
    private suspend fun deliver(event: StreamEvent) {
        val onEvent = listener ?: return
        delivering.withLock { onEvent(event) }
    }

    private suspend fun run() {
        var failures = 0
        while (true) {
            val resumeFrom = maxOf(cursor, floor())
            try {
                connections += 1
                opener.open(resumeFrom, object : StreamSink {
                    override fun opened() {
                        mutableStatus.value = StreamStatus.Live
                        failures = 0
                    }

                    override fun retryHint(millis: Long) {
                        retryMillis = millis.coerceIn(500, timing.maxRetryMillis)
                    }

                    override suspend fun frame(frame: SseFrame) = receive(frame)
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: DashboardException) {
                if (refused.status == 401) {
                    mutableStatus.value = StreamStatus.SignedOut
                    return
                }
            } catch (_: Throwable) {
                // No signal, a timeout, a server restart: all the same here. Wait and go again.
            }
            mutableStatus.value = StreamStatus.Offline
            delay(backoff(failures++))
        }
    }

    private fun backoff(failures: Int): Long {
        val doublings = minOf(failures, 10)
        return minOf(retryMillis shl doublings, timing.maxRetryMillis)
    }

    private suspend fun receive(frame: SseFrame) {
        val envelope = runCatching { json.decodeFromString(StreamEnvelope.serializer(), frame.data) }.getOrNull()
        val seq = frame.id?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            ?: envelope?.seq
            ?: (envelope?.payload?.get("seq")?.jsonPrimitive?.longOrNull)
            ?: 0
        val type = frame.event.takeIf { it != "message" } ?: envelope?.type ?: return
        if (type == StreamEvent.Resync) {
            val resyncSeq = frame.id?.trim()?.toLongOrNull() ?: resyncSeq(frame.data) ?: seq
            cursor = resyncSeq
            deliver(StreamEvent(type, resyncSeq, null))
            return
        }
        if (seq > 0) cursor = maxOf(cursor, seq)
        // A frame whose body didn't parse still tells listeners something changed: they refetch.
        deliver(StreamEvent(type, seq, envelope?.payload))
    }

    private fun resyncSeq(data: String): Long? =
        runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()?.get("seq")?.jsonPrimitive?.longOrNull

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The real connection: an authorised GET held open, read line by line. */
internal fun DashboardClient.streamOpener(timing: StreamTiming): StreamOpener =
    StreamOpener { cursor, sink ->
        http.prepareGet("api/stream") {
            authorise()
            header(HttpHeaders.Accept, "text/event-stream")
            header(HttpHeaders.CacheControl, "no-cache")
            if (cursor > 0) {
                header("Last-Event-ID", cursor.toString())
                parameter("last_event_id", cursor)
            }
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = timing.silenceMillis + 5_000
            }
        }.execute { response ->
            response.requireSuccess()
            sink.opened()
            val reader = SseReader(response.bodyAsChannel(), sink::retryHint)
            while (true) {
                val frame = reader.next(timing.silenceMillis) ?: break
                sink.frame(frame)
            }
        }
    }
