package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.events.Ring
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route

/** A resume point: `<seq>` (within this boot) or `<boot>-<seq>`. */
data class StreamCursor(val boot: String?, val seq: Long)

fun parseCursor(raw: String?): StreamCursor? {
    if (raw == null) return null
    val m = Regex("^(?:([0-9a-z]{1,32})-)?(\\d+)$").find(raw.trim()) ?: return null
    val seq = m.groupValues[2].toLongOrNull()?.takeIf { it <= 9_007_199_254_740_991 } ?: return null
    return StreamCursor(m.groupValues[1].ifEmpty { null }, seq)
}

/**
 * `GET /api/stream`, the owner's live feed (spec/04-stream-and-events.md §3, with decided fixes 3
 * and 11): opens with retry, a comment and a `hello` naming the boot; replays from `Last-Event-ID`
 * (or `?last_event_id=`), or sends one `resync` when it cannot; then every event as it happens. A
 * stream opened with a device key ends within about five seconds of that key being revoked.
 */
fun Route.streamRoutes(app: App) {
    endpoint(
        "/api/stream",
        HttpMethod.Get to {
            val cursor = parseCursor(call.request.headers["Last-Event-ID"]) ?: parseCursor(call.request.queryParameters["last_event_id"])
            val key = call.ownerKey()
            val bus = app.bus
            // An event about one person's own bell or follows (it names a memberId) goes only to them.
            val me = call.person.id
            fun mine(event: dev.wildware.feed.server.events.AppEvent) =
                (event.payload["memberId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.let { it == null || it == me }
            call.withStreamSlot(app) {
                call.respondSse(
                SseContent(
                    stillAllowed = key?.let { k -> { app.devices.authenticate(k) != null } },
                    now = app.clock,
                ) { sink ->
                    val sameBoot = cursor != null && (cursor.boot == null || cursor.boot == bus.boot)
                    val (_, stop) = bus.subscribeFrom(
                        since = if (sameBoot) cursor!!.seq else null,
                        listener = { if (mine(it)) sink.send(Frames.event(it, bus.boot)) },
                    ) { replay ->
                        val opening = StringBuilder(Frames.retry(Frames.RETRY_MS)).append(Frames.comment("connected"))
                        when {
                            cursor == null -> opening.append(Frames.hello(bus.boot, bus.lastSeq))
                            !sameBoot -> opening.append(Frames.hello(bus.boot, bus.lastSeq))
                                .append(Frames.resync("restarted", cursor.seq, bus.lastSeq, bus.boot))
                            replay is Ring.Replay.Hit -> {
                                opening.append(Frames.hello(bus.boot, cursor.seq))
                                replay.events.filter(::mine).forEach { opening.append(Frames.event(it, bus.boot)) }
                            }
                            else -> opening.append(Frames.hello(bus.boot, bus.lastSeq))
                                .append(Frames.resync((replay as Ring.Replay.Miss).reason, cursor.seq, bus.lastSeq, bus.boot))
                        }
                        sink.send(opening.toString())
                    }
                    stop
                },
            )
            }
        },
    )
}
