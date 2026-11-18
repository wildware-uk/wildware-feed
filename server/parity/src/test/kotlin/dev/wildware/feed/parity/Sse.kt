package dev.wildware.feed.parity

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One server-sent event as it came off the wire. A comment line (`: ...`) is a frame with only [comment]. */
data class SseFrame(
    val id: String?,
    val event: String?,
    val data: String?,
    val retry: String?,
    val comment: String?,
) {
    /** The event seq from the `id:`, which is `<seq>` or, since the boot-id fix, `<boot>-<seq>`. */
    val seq: Long? get() = id?.substringAfterLast('-')?.toLongOrNull()

    /** The boot id from the `id:`, when it carries one. */
    val boot: String? get() = id?.takeIf { '-' in it }?.substringBeforeLast('-')

    val json: JsonElement get() = Parity.json.parseToJsonElement(data ?: error("frame has no data"))
    val obj: JsonObject get() = json.jsonObject
}

/**
 * A live event stream, read in the background into a queue of frames. Close it in a `finally`
 * (or `use`) so the server sees the disconnect.
 */
class SseStream private constructor(private val scope: CoroutineScope) : AutoCloseable {
    private val frames = Channel<SseFrame>(Channel.UNLIMITED)
    val seen = mutableListOf<SseFrame>()
    lateinit var response: HttpResponse
        private set

    /** The next frame that matches, skipping (but remembering) the rest. */
    suspend fun await(timeout: Duration = 10.seconds, what: String = "a matching frame", match: (SseFrame) -> Boolean): SseFrame =
        withTimeoutOrNull(timeout) {
            while (true) {
                val frame = frames.receive()
                seen += frame
                if (match(frame)) return@withTimeoutOrNull frame
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("no $what within $timeout; saw ${seen.takeLast(10)}")

    suspend fun awaitEvent(name: String, timeout: Duration = 10.seconds, match: (SseFrame) -> Boolean = { true }) =
        await(timeout, "'$name' event") { it.event == name && match(it) }

    /** Every frame that arrives within [window]. */
    suspend fun drain(window: Duration): List<SseFrame> {
        val got = mutableListOf<SseFrame>()
        withTimeoutOrNull(window) {
            while (true) {
                val frame = frames.receive()
                seen += frame
                got += frame
            }
        }
        return got
    }

    /** Wait for the server to end the stream; frames still arriving are kept in [seen]. */
    suspend fun awaitClosed(timeout: Duration = 10.seconds) {
        withTimeoutOrNull(timeout) {
            try {
                for (frame in frames) seen += frame
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection broke rather than ended cleanly: closed either way.
            }
            true
        } ?: error("the stream was still open after $timeout")
    }

    override fun close() = scope.cancel()

    companion object {
        /** Open [path] and wait until the response headers are in. */
        suspend fun open(path: String, configure: HttpRequestBuilder.() -> Unit = {}): SseStream {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val stream = SseStream(scope)
            val ready = CompletableDeferred<HttpResponse>()
            scope.launch {
                try {
                    Parity.http.prepareGet(Parity.baseUrl + path) {
                        header(HttpHeaders.Accept, "text/event-stream")
                        timeout {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                        configure()
                    }.execute { response ->
                        ready.complete(response)
                        stream.read(response)
                    }
                } catch (e: Throwable) {
                    ready.completeExceptionally(e)
                    stream.frames.close(e)
                }
            }
            stream.response = withTimeout(15.seconds) { ready.await() }
            return stream
        }
    }

    private suspend fun read(response: HttpResponse) {
        val channel = response.bodyAsChannel()
        var id: String? = null
        var event: String? = null
        var retry: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isEmpty()) {
                if (hasData || id != null || event != null || retry != null) {
                    frames.send(SseFrame(id, event, if (hasData) data.toString() else null, retry, null))
                }
                id = null; event = null; retry = null; data.clear(); hasData = false
                continue
            }
            if (line.startsWith(":")) {
                frames.send(SseFrame(null, null, null, null, line.removePrefix(":").trimStart()))
                continue
            }
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "id" -> id = value
                "event" -> event = value
                "retry" -> retry = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
        frames.close()
    }
}

/** Open, run [block], always close. */
suspend fun <T> withStream(path: String, configure: HttpRequestBuilder.() -> Unit = {}, block: suspend (SseStream) -> T): T {
    val stream = SseStream.open(path, configure)
    try {
        return block(stream)
    } finally {
        stream.close()
    }
}
