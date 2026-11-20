package dev.wildware.feed.server.events

import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.http.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One published event: `{type, seq, at, payload}` on the wire (spec/04-stream-and-events.md §1).
 * [team] is whose it is; it never goes on the wire, and only that team's listeners hear it.
 */
data class AppEvent(val type: String, val seq: Long, val at: String, val payload: JsonObject, val team: String = Tenant.HOME) {
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
 *
 * Each team has its own seq and replay ring (docs/HOSTING.md §3), and an event goes only to
 * listeners of its own team: a stream, a wait or a poll listens to the team it was opened in
 * ([Tenant.id]). The few listeners that serve every team ([subscribeEveryTeam]: the bell, iOS
 * alerts, Live Activities) run inside the event's team.
 */
class Bus(capacity: Int = RING_CAPACITY, val boot: String = newBootId(), private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    private val capacity = capacity

    /** One team's seq and replay ring. */
    private class Log(capacity: Int) {
        val ring = Ring(capacity)
        var seq = 0L
    }

    private val logs = HashMap<String, Log>()
    private fun log(team: String) = logs.getOrPut(team) { Log(capacity) }

    /** A listener and the team it hears; null hears every team. */
    private class Listener(val team: String?, val handle: (AppEvent) -> Unit)

    private val listeners = LinkedHashSet<Listener>()
    private val pending = ArrayDeque<AppEvent>()
    private var dispatching = false

    init {
        require(Regex("^[0-9a-z]{1,32}$").matches(boot)) { "Invalid boot id: $boot" }
    }

    /** The seq of this team's newest event. */
    val lastSeq: Long get() = lock.withLock { log(Tenant.id).seq }

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
        val team = Tenant.id
        val log = log(team)
        log.seq += 1
        val event = AppEvent(type, log.seq, Wire.isoOf(clock()), payload, team)
        log.ring.push(event)
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
                    if (listener.team != null && listener.team != event.team) continue
                    try {
                        Tenant.with(event.team) { listener.handle(event) }
                    } catch (e: Exception) {
                        System.err.println("event subscriber threw on ${event.type}: $e")
                    }
                }
            }
        } finally {
            dispatching = false
        }
    }

    /** Listen to every event of this team from now on; call the result to stop. */
    fun subscribe(listener: (AppEvent) -> Unit): () -> Unit = add(Listener(Tenant.id, listener))

    /** Listen to every team's events, each run inside its own team. For the server's own effects only. */
    fun subscribeEveryTeam(listener: (AppEvent) -> Unit): () -> Unit = add(Listener(null, listener))

    private fun add(entry: Listener): () -> Unit {
        lock.withLock { listeners += entry }
        return { lock.withLock { listeners -= entry } }
    }

    /** Subscribe and replay [since] in one step, so nothing falls between the two. This team only. */
    fun <T> subscribeFrom(since: Long?, listener: (AppEvent) -> Unit, onReplay: (Ring.Replay?) -> T): Pair<T, () -> Unit> = lock.withLock {
        val team = Tenant.id
        val replay = since?.let { log(team).ring.since(it) }
        val result = onReplay(replay)
        val entry = Listener(team, listener)
        listeners += entry
        result to { lock.withLock { listeners -= entry } }
    }

    fun replaySince(seq: Long): Ring.Replay = lock.withLock { log(Tenant.id).ring.since(seq) }

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
