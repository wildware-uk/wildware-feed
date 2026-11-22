package dev.wildware.agents

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A small place to keep one string, such as the app's settings file. The app supplies a lasting one. */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

/** Keeps the text in memory only: forgotten when the app closes. For tests and previews. */
class MemoryTextStore(private var text: String? = null) : TextStore {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/**
 * Which comments the owner has seen, per card, ported from the website's `src/web/unread.svelte.ts`.
 *
 * A card's mark is the time of the newest comment the owner has seen on it. Comments after it,
 * not written by the owner, are unread. A card met for the first time starts level, not unread,
 * so a new device doesn't badge every card with its whole history. At most [Max] cards are
 * remembered, the most recently read kept.
 */
class ReadMarks(private val store: TextStore = MemoryTextStore()) {
    private var marks: Map<String, Long> = parse(runCatching { store.read() }.getOrNull())

    /** Bumped on every change, so a store can tell when to recount. */
    var version: Int = 0
        private set

    fun unread(cardId: String, comments: List<MessageView>): Int {
        val mark = marks[cardId] ?: return 0
        return comments.count { !it.isOwner && it.createdAt > mark }
    }

    /** The first time a card is seen in the data, everything on it so far counts as seen. */
    fun seed(cardId: String, comments: List<MessageView>) {
        if (cardId in marks) return
        write(cardId, newestAt(comments))
    }

    fun markRead(cardId: String, comments: List<MessageView>) {
        val newest = newestAt(comments)
        if ((marks[cardId] ?: -1) >= newest) return
        write(cardId, newest)
    }

    /** Puts the mark just before the newest comment someone else wrote, so it counts as one unread. */
    fun markUnread(cardId: String, comments: List<MessageView>) {
        val newest = comments.filter { !it.isOwner }.maxOfOrNull { it.createdAt } ?: return
        write(cardId, newest - 1)
    }

    fun isKnown(cardId: String): Boolean = cardId in marks

    val size: Int get() = marks.size

    private fun write(cardId: String, at: Long) {
        val next = marks + (cardId to at)
        marks = if (next.size <= Max) next else next.entries.sortedByDescending { it.value }.take(Max).associate { it.key to it.value }
        version += 1
        runCatching { store.write(json.encodeToString(serializer, marks)) }
    }

    companion object {
        const val Max = 500

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Long.serializer())

        internal fun newestAt(comments: List<MessageView>): Long = comments.maxOfOrNull { it.createdAt } ?: 0

        internal fun parse(text: String?): Map<String, Long> =
            if (text.isNullOrBlank()) emptyMap() else runCatching { json.decodeFromString(serializer, text) }.getOrElse { emptyMap() }
    }
}
