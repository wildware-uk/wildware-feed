package dev.wildware.feed.server.http

import dev.wildware.feed.server.events.AppEvent
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The bytes of each kind of frame (spec/04-stream-and-events.md §2, with the boot-id fix). */
object Frames {
    const val RETRY_MS = 2_000
    const val HEARTBEAT_MS = 15_000L

    /** Stream ids are `<boot>-<seq>`. */
    fun id(boot: String, seq: Long) = "$boot-$seq"

    fun retry(ms: Int) = "retry: ${maxOf(0, ms)}\n\n"

    fun comment(text: String) = ": ${oneLine(text)}\n\n"

    fun data(json: JsonElement) = oneLine(Wire.encode(json))

    fun event(event: AppEvent, boot: String) =
        "id: ${id(boot, event.seq)}\nevent: ${event.type}\ndata: ${data(event.envelope)}\n\n"

    fun hello(boot: String, seq: Long): String {
        val body = buildJsonObject {
            put("type", JsonPrimitive("hello"))
            put("boot", JsonPrimitive(boot))
            put("seq", JsonPrimitive(seq))
        }
        return "id: ${id(boot, seq)}\nevent: hello\ndata: ${data(body)}\n\n"
    }

    fun resync(reason: String, from: Long, seq: Long, boot: String): String {
        val body = buildJsonObject {
            put("type", JsonPrimitive("resync"))
            put("reason", JsonPrimitive(reason))
            put("from", JsonPrimitive(from))
            put("seq", JsonPrimitive(seq))
            put("boot", JsonPrimitive(boot))
        }
        return "id: ${id(boot, seq)}\nevent: resync\ndata: ${data(body)}\n\n"
    }

    /** A named frame with no id (the agent stream's `work`, `message`, `answer`, `reaction`). */
    fun named(name: String, json: JsonElement) = "event: $name\ndata: ${data(json)}\n\n"

    private fun oneLine(text: String) = text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
}

/**
 * A live event stream. [start] writes the opening frames and subscribes, handing back what to undo
 * when the stream ends; frames sent through [Sink] are written in order. The stream ends when the
 * client goes away, when [Sink.close] is called, or when [stillAllowed] turns false (checked every
 * [checkEveryMs]).
 *
 * Send it with [respondSse], not `respond`: the engine sends the response headers before it calls
 * [writeTo], so a stream that only subscribed there would miss whatever was published between the
 * client seeing the headers and the subscription. [respondSse] subscribes first, as the TS server
 * did (spec/04-stream-and-events.md §3.3).
 */
class SseContent(
    private val heartbeatMs: Long = Frames.HEARTBEAT_MS,
    private val checkEveryMs: Long = 5_000,
    private val stillAllowed: (() -> Boolean)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val start: (Sink) -> (() -> Unit),
) : OutgoingContent.WriteChannelContent() {
    class Sink internal constructor(private val channel: Channel<String?>) {
        fun send(frame: String) {
            channel.trySend(frame)
        }

        /** End the stream from the server side. */
        fun close() {
            channel.trySend(null)
        }
    }

    override val status: HttpStatusCode get() = HttpStatusCode.OK
    override val contentType: ContentType? get() = null
    override val headers: Headers get() = Headers.build {
        append("Content-Type", "text/event-stream; charset=utf-8")
        append("Cache-Control", "no-cache, no-store, no-transform")
        append("Connection", "keep-alive")
        append("X-Accel-Buffering", "no")
    }

    private val frames = Channel<String?>(Channel.UNLIMITED)
    private val sink = Sink(frames)
    private val lock = Any()
    private var stop: (() -> Unit)? = null
    private var ended = false

    /** Subscribe now and queue the opening frames; a second call does nothing. */
    fun open() = synchronized(lock) {
        if (stop == null && !ended) stop = start(sink)
    }

    /** Unsubscribe and drop what is queued; safe to call more than once. */
    fun release() {
        val undo = synchronized(lock) {
            ended = true
            stop.also { stop = null }
        }
        undo?.invoke()
        frames.close()
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        open()
        try {
            coroutineScope {
                val beats = launch {
                    while (true) {
                        delay(heartbeatMs)
                        sink.send(Frames.comment("heartbeat ${Wire.isoOf(now())}"))
                    }
                }
                val checks = stillAllowed?.let { allowed ->
                    launch {
                        while (true) {
                            delay(checkEveryMs)
                            if (!runCatching(allowed).getOrDefault(true)) sink.close()
                        }
                    }
                }
                try {
                    for (frame in frames) {
                        if (frame == null) break
                        channel.writeStringUtf8(frame)
                        channel.flush()
                    }
                } finally {
                    beats.cancel()
                    checks?.cancel()
                }
            }
        } finally {
            release()
        }
    }
}

/** Subscribe, then send the headers and the stream; unsubscribe however it ends. */
suspend fun ApplicationCall.respondSse(content: SseContent) {
    content.open()
    try {
        respond(content)
    } finally {
        content.release()
    }
}
