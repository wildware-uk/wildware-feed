package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Card refs (migration 056): an update, a message or a request points at up to [MAX] other cards.
 * An agent replies on a card with a video, then asks a question that points at the reply, and the
 * owner sees the video inside the question.
 *
 * A ref is checked when it is given: the card must be there, live, and one the poster may read. It
 * is not checked again: a card removed later stays in the list and reads as removed.
 */
class CardRefs(private val db: Database, private val clock: () -> Long) {
    /** What a ref points at, as much of it as a summary needs. */
    private class Target(
        val kind: String,
        val id: String,
        val projectId: String?,
        val author: String?,
        val title: String?,
        val body: String?,
        val deleted: Boolean,
        val updateId: String? = null,
        val replyTo: String? = null,
        val requestId: String? = null,
        val state: String? = null,
    )

    /**
     * [refs] checked for [by] (`agent:<id>` or `human`), in order, each card once. [self] is the
     * card being written, which cannot point at itself. [kept] are the refs it carries already (an
     * edit): those stay allowed even if their card has been removed since.
     */
    fun check(by: String, refs: List<CardRef>?, self: CardRef? = null, kept: List<CardRef> = emptyList()): List<CardRef> {
        if (refs.isNullOrEmpty()) return emptyList()
        val unique = refs.distinct()
        if (unique.size > MAX) throw invalid("refs takes at most $MAX cards")
        for (ref in unique) {
            if (ref.kind !in CardRef.KINDS) throw invalid("a ref's kind is one of: ${CardRef.KINDS.joinToString(", ")}")
            if (ref.id.isBlank()) throw invalid("a ref needs the id of the card it points at")
            if (ref == self) throw invalid("a card cannot point at itself")
        }
        val targets = targets(unique.filter { it !in kept })
        for (ref in unique) {
            if (ref in kept) continue
            val target = targets[ref]
            if (target == null || target.deleted || !visible(by, target)) throw notFound("no such ${ref.kind}: ${ref.id}")
        }
        return unique
    }

    /** The refs on [card] become [refs], in that order. */
    fun set(card: CardRef, refs: List<CardRef>) {
        db.update("DELETE FROM card_refs WHERE team_id = :team AND ${card.anchor} = ?", card.id)
        val at = clock()
        for (ref in refs) {
            db.update(
                "INSERT INTO card_refs (team_id, ${card.anchor}, ref_kind, ref_id, created_at) VALUES (:team, ?, ?, ?, ?)",
                card.id, ref.kind, ref.id, at,
            )
        }
    }

    /** The refs on one card, in order. */
    fun on(card: CardRef): List<CardRef> = db.query(
        "SELECT ref_kind, ref_id FROM card_refs WHERE team_id = :team AND ${card.anchor} = ? ORDER BY seq", card.id,
    ) { CardRef(it.getString(1), it.getString(2)) }

    /** The refs on many cards of one [kind], as [CardRef]s, by card id. Cards without refs are left out. */
    fun onMany(kind: String, ids: List<String>): Map<String, List<CardRef>> {
        if (ids.isEmpty()) return emptyMap()
        val anchor = CardRef(kind, "").anchor
        val out = LinkedHashMap<String, MutableList<CardRef>>()
        for (chunk in ids.distinct().chunked(CHUNK)) {
            db.query(
                "SELECT $anchor, ref_kind, ref_id, seq FROM card_refs WHERE team_id = :team AND $anchor IN (${marks(chunk)})", *chunk.toTypedArray(),
            ) { Triple(it.getString(1), CardRef(it.getString(2), it.getString(3)), it.getLong(4)) }
                .sortedBy { it.third }
                .forEach { (card, ref, _) -> out.getOrPut(card) { mutableListOf() } += ref }
        }
        return out
    }

    /**
     * The refs on many cards of one [kind], by card id: a short summary of each card pointed at,
     * enough to draw it small and open its thread. Cards without refs are left out. With a [reader]
     * (an agent), a card it may not read is summarised as removed.
     */
    fun summaries(kind: String, ids: List<String>, reader: String? = null): Map<String, List<JsonObject>> {
        val byCard = onMany(kind, ids)
        if (byCard.isEmpty()) return emptyMap()
        val all = byCard.values.flatten().distinct()
        val targets = targets(all).filterValues { reader == null || visible(reader, it) }
        val live = targets.values.filter { !it.deleted }
        val reads = Reads(db, clock)
        val media = live.groupBy { it.kind }.flatMap { (k, ts) ->
            val found = reads.mediaForMany(CardRef(k, "").anchor, ts.map { it.id })
            ts.map { CardRef(k, it.id) to found[it.id].orEmpty() }
        }.toMap()
        val names = agentNames(live.mapNotNull { it.author?.takeIf { a -> a.startsWith(AGENT) }?.removePrefix(AGENT) })
        val views = all.associateWith { ref -> view(ref, targets[ref], media[ref].orEmpty(), names) }
        return byCard.mapValues { (_, refs) -> refs.map { views.getValue(it) } }
    }

    private fun view(ref: CardRef, target: Target?, media: List<JsonObject>, names: Map<String, String>): JsonObject {
        val gone = target == null || target.deleted
        return buildJsonObject {
            put("kind", ref.kind)
            put("id", ref.id)
            put("projectId", target?.projectId)
            put("deleted", gone)
            if (gone) {
                for (key in listOf("author", "authorName", "title", "text", "media")) put(key, JsonNull)
                put("mediaCount", 0)
                for (key in listOf("updateId", "replyTo", "requestId", "state")) put(key, JsonNull)
                return@buildJsonObject
            }
            target!!
            put("author", target.author)
            put("authorName", target.author?.takeIf { it.startsWith(AGENT) }?.let { names[it.removePrefix(AGENT)] })
            put("title", target.title?.let { Reads.cut(it, TITLE_MAX) })
            put("text", target.body?.let { Reads.firstLine(it) }?.takeIf { it.isNotEmpty() }?.let { Reads.cut(it, TEXT_MAX) })
            put("media", media.firstOrNull() ?: JsonNull)
            put("mediaCount", media.size)
            put("updateId", target.updateId)
            put("replyTo", target.replyTo)
            put("requestId", target.requestId)
            put("state", target.state)
        }
    }

    /**
     * Whether [by] may read [target]. Today every agent reads every channel, so it comes down to a
     * message that @names agents, which reaches only them (and its author). When channels belong to
     * teams (docs/HOSTING.md), the target's lookup is scoped to the poster's team like every other
     * query, and a card in another team is simply not found.
     */
    private fun visible(by: String, target: Target): Boolean {
        if (target.kind != "message") return true
        return Mentions.readable(db, target.id, target.author, by)
    }

    private fun targets(refs: List<CardRef>): Map<CardRef, Target> {
        val out = HashMap<CardRef, Target>()
        for ((kind, group) in refs.groupBy { it.kind }) {
            for (chunk in group.map { it.id }.distinct().chunked(CHUNK)) {
                val sql = when (kind) {
                    "update" -> "SELECT id, project_id, agent_id, title, body, deleted_at FROM updates"
                    "message" -> "SELECT id, project_id, author, body, deleted_at, update_id, reply_to, request_id FROM messages"
                    "request" -> "SELECT id, project_id, agent_id, question, detail, state FROM approvals"
                    else -> continue
                }
                db.query("$sql WHERE team_id = :team AND id IN (${marks(chunk)})", *chunk.toTypedArray()) { rs ->
                    when (kind) {
                        "update" -> Target(
                            kind, rs.getString("id"), rs.getString("project_id"), AGENT + rs.getString("agent_id"),
                            rs.getString("title"), rs.getString("body"), rs.longOrNull("deleted_at") != null,
                        )
                        "message" -> Target(
                            kind, rs.getString("id"), rs.getString("project_id"), rs.getString("author"), null, rs.getString("body"),
                            rs.longOrNull("deleted_at") != null,
                            updateId = rs.getString("update_id"), replyTo = rs.getString("reply_to"), requestId = rs.getString("request_id"),
                        )
                        else -> Target(
                            kind, rs.getString("id"), rs.getString("project_id"), AGENT + rs.getString("agent_id"),
                            rs.getString("question"), rs.getString("detail"), false,
                            state = rs.getString("state").let { if (it in OPEN_STATES) it else "answered" },
                        )
                    }
                }.forEach { out[CardRef(kind, it.id)] = it }
            }
        }
        return out
    }

    private fun agentNames(ids: List<String>): Map<String, String> = ids.distinct().chunked(CHUNK).flatMap { chunk ->
        db.query("SELECT id, name FROM agents WHERE team_id = :team AND id IN (${marks(chunk)})", *chunk.toTypedArray()) { it.getString(1) to it.getString(2) }
    }.toMap()

    companion object {
        /** The most cards one card may point at. */
        const val MAX = 8
        const val TITLE_MAX = 200
        const val TEXT_MAX = 200
        private const val AGENT = "agent:"
        private const val CHUNK = 500
        /** A request's state as reads name it (`Rows.request`): approved and rejected are both answered. */
        private val OPEN_STATES = setOf("pending", "timeout", "cancelled", "withdrawn")

        private fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }
    }
}
