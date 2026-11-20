package dev.wildware.feed.server.domain

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonObject

/**
 * The feed's writes (spec/02, spec/05 §7): projects, updates, messages, cards, tags, fields, embed
 * looks and apps.
 *
 * The TS server runs each write to the end before anything else touches the database. Here every
 * write runs inside [op], which holds the one connection for the whole write, and the events it
 * publishes go out after it lets go, in the order they were published, even when the write throws
 * halfway (the TS server has already published those by then). Publishing outside the database lock
 * means a bus listener that reads the database can never deadlock against a write.
 *
 * There are no transactions, as in the TS server: a write that fails halfway keeps what it wrote.
 */
class Feed(val db: Database, val bus: Bus, val clock: () -> Long) {
    private val pending = ThreadLocal<MutableList<Pair<String, JsonObject>>?>()

    val projects = Projects(this)
    val cards = Cards(this)
    val updates = Updates(this)
    val messages = Messages(this)
    val tags = Tags(this)
    val fields = Fields(this)
    val embedTypes = EmbedTypes(this)
    val apps = Apps(this)
    val blocks = Blocks(this)

    /** Run one write alone; nested calls join the outer one. */
    fun <T> op(block: () -> T): T {
        if (pending.get() != null) return db.withConnection { block() }
        val events = mutableListOf<Pair<String, JsonObject>>()
        pending.set(events)
        try {
            return db.withConnection { block() }
        } finally {
            pending.set(null)
            events.forEach { (type, payload) -> bus.publish(type, payload) }
        }
    }

    fun publish(type: String, payload: JsonObject) {
        val events = pending.get()
        if (events != null) events += type to payload else bus.publish(type, payload)
    }

    fun now(): Long = clock()
}

/** The feed for this server: stateless, so a fresh one per call costs nothing. */
val App.feed: Feed get() = Feed(db, bus, clock)

/** A value that was given, possibly null, as opposed to a key that was left out. */
class Given<out T>(val value: T)
