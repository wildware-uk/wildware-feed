package dev.wildware.feed.server.events

import dev.wildware.feed.server.http.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One published event: `{type, seq, at, payload}` on the wire (spec/04-stream-and-events.md §1). */
data class AppEvent(val type: String, val seq: Long, val at: String, val payload: JsonObject) {
    val envelope: JsonObject get() = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("seq", JsonPrimitive(seq))
        put("at", JsonPrimitive(at))
        put("payload", payload)
    }
}

/**
 * The event bus: a seq counter, a boot id new each start, a replay ring and the listeners.
 *
 * A listener that publishes while an event is being handed out does not jump the queue: the new
 * event waits until every listener has seen the current one, so every listener sees events in seq
 * order (decided fix 2). Listeners run under the bus lock and must not block: streams only queue a
 * frame.
 */
class Bus(capacity: Int = RING_CAPACITY, val boot: String = newBootId(), private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    private val ring = Ring(capacity)
    private val listeners = LinkedHashSet<(AppEvent) -> Unit>()
    private val pending = ArrayDeque<AppEvent>()
    private var dispatching = false
    private var seq = 0L

    init {
        require(Regex("^[0-9a-z]{1,32}$").matches(boot)) { "Invalid boot id: $boot" }
    }

    val lastSeq: Long get() = lock.withLock { seq }

    /**
     * Checked before every publish. The app sets it to refuse a publish while the database lock is
     * held: listeners read the database under the bus lock, so publishing with the database lock
     * held could deadlock two threads.
     */
    @Volatile
    var beforePublish: (String) -> Unit = {}

    fun publish(type: String, payload: JsonObject): AppEvent {
        beforePublish(type)
        return publishLocked(type, payload)
    }

    private fun publishLocked(type: String, payload: JsonObject): AppEvent = lock.withLock {
        seq += 1
        val event = AppEvent(type, seq, Wire.isoOf(clock()), payload)
        ring.push(event)
        pending.addLast(event)
        if (!dispatching) drain()
        event
    }

    private fun drain() {
        dispatching = true
        try {
            while (true) {
                val event = pending.removeFirstOrNull() ?: break
                for (listener in listeners.toList()) {
                    if (listener !in listeners) continue
                    try {
                        listener(event)
                    } catch (e: Exception) {
                        System.err.println("event subscriber threw on ${event.type}: $e")
                    }
                }
            }
        } finally {
            dispatching = false
        }
    }

    /** Listen to every event from now on; call the result to stop. */
    fun subscribe(listener: (AppEvent) -> Unit): () -> Unit {
        lock.withLock { listeners += listener }
        return { lock.withLock { listeners -= listener } }
    }

    /** Subscribe and replay [since] in one step, so nothing falls between the two. */
    fun <T> subscribeFrom(since: Long?, listener: (AppEvent) -> Unit, onReplay: (Ring.Replay?) -> T): Pair<T, () -> Unit> = lock.withLock {
        val replay = since?.let { ring.since(it) }
        val result = onReplay(replay)
        listeners += listener
        result to { lock.withLock { listeners -= listener } }
    }

    fun replaySince(seq: Long): Ring.Replay = lock.withLock { ring.since(seq) }

    /** The first event matching [matches] within [timeoutMs], or null. */
    suspend fun waitFor(timeoutMs: Long, matches: (AppEvent) -> Boolean): AppEvent? {
        val found = CompletableDeferred<AppEvent>()
        val stop = subscribe { if (matches(it)) found.complete(it) }
        try {
            return withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            stop()
        }
    }

    companion object {
        const val RING_CAPACITY = 500
        fun newBootId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/** The last [capacity] events, for replay to a stream that reconnects. */
class Ring(private val capacity: Int) {
    sealed interface Replay {
        data class Hit(val events: List<AppEvent>) : Replay
        /** `expired` (older than the ring) or `ahead` (newer than anything published). */
        data class Miss(val reason: String) : Replay
    }

    private val events = ArrayDeque<AppEvent>()

    fun push(event: AppEvent) {
        events.addLast(event)
        if (events.size > capacity) events.removeFirst()
    }

    fun since(seq: Long): Replay {
        val newest = events.lastOrNull()?.seq ?: 0
        val oldest = events.firstOrNull()?.seq ?: (newest + 1)
        if (seq > newest) return Replay.Miss("ahead")
        if (seq < oldest - 1) return Replay.Miss("expired")
        return Replay.Hit(events.filter { it.seq > seq })
    }
}
