package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What every card shares: pin, work status, reactions and acknowledgements
 * (spec/02 §6, spec/05 §7.16, §7.17, §7.23, §7.27; TS `src/domain/cards.ts`).
 */
class Cards(private val feed: Feed) {
    private val db get() = feed.db

    data class WorkItem(
        val kind: String, val id: String, val projectId: String?, val status: String, val statusAt: Long?,
        val createdAt: Long, val title: String?, val summary: String, val author: String,
    )

    fun find(card: CardRef): CardRow? {
        val request = card.kind == "request"
        val status = if (request) "NULL" else "status"
        val statusAt = if (request) "NULL" else "status_at"
        val deleted = if (request) "NULL" else "deleted_at"
        return db.one(
            "SELECT id, project_id, pinned, $status AS status, $statusAt AS status_at, $deleted AS deleted_at FROM ${card.table} WHERE id = ?",
            card.id,
        ) { CardRow(it.getString("id"), it.getString("project_id"), it.getInt("pinned") == 1, it.getString("status"), it.longOrNull("status_at"), it.longOrNull("deleted_at")) }
    }

    /** Missing or deleted is `no such <kind>: <id>`. */
    fun resolve(card: CardRef): CardRow {
        val row = find(card)
        if (row == null || row.deletedAt != null) throw notFound("no such ${card.kind}: ${card.id}")
        return row
    }

    /** The card's own change event, with the TS placeholders (spec/02 surprise 15). */
    fun announce(card: CardRef, row: CardRow) {
        when (card.kind) {
            "update" -> feed.publish("update.updated", buildJsonObject {
                put("updateId", card.id); put("projectId", row.projectId ?: ""); put("pinned", row.pinned)
            })
            "message" -> feed.publish("message.updated", buildJsonObject {
                put("messageId", card.id); put("projectId", row.projectId); put("author", "")
            })
            else -> feed.publish("request.updated", buildJsonObject {
                put("requestId", card.id); put("agentId", ""); put("projectId", row.projectId)
            })
        }
    }

    fun pin(card: CardRef, pinned: Boolean): Boolean = feed.op {
        val row = resolve(card)
        if (row.pinned == pinned) return@op row.pinned
        db.update("UPDATE ${card.table} SET pinned = ? WHERE id = ?", pinned, card.id)
        val saved = find(card) ?: throw notFound("no such ${card.kind}: ${card.id}")
        announce(card, saved)
        saved.pinned
    }

    fun setStatus(card: CardRef, status: String?): String? = feed.op {
        if (card.kind !in WORKABLE) throw invalid("a ${card.kind} cannot be marked as work")
        if (status != null && status !in STATUSES) throw invalid("status must be one of: ${STATUSES.joinToString(", ")}")
        val row = resolve(card)
        if (row.status == status) return@op row.status
        db.update("UPDATE ${card.table} SET status = ?, status_at = ? WHERE id = ?", status, feed.now(), card.id)
        val saved = find(card) ?: throw notFound("no such ${card.kind}: ${card.id}")
        announce(card, saved)
        saved.status
    }

    fun reactions(card: CardRef): List<ReactionRow> =
        db.query("SELECT ${Rows.REACTION} FROM reactions WHERE ${card.anchor} IN (?) ORDER BY seq", card.id) { ReactionRow(Rows.reaction(it)) }

    /** `reactToCard`: the card is checked before the emoji; omitting [on] toggles. */
    fun react(card: CardRef, actor: String, emoji: String, on: Boolean?): Pair<List<ReactionRow>, Boolean> = feed.op {
        val row = resolve(card)
        val clean = Emoji.assert(emoji)
        val already = reactions(card).any { it.actor == actor && it.emoji == clean }
        val wanted = on ?: !already
        var changed = false
        if (wanted && !already) {
            changed = db.update(
                "INSERT INTO reactions (id, ${card.anchor}, actor, emoji, created_at) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (${card.anchor}, actor, emoji) WHERE ${card.anchor} IS NOT NULL DO NOTHING",
                Ulid.next(), card.id, actor, clean, feed.now(),
            ) > 0
        } else if (!wanted && already) {
            changed = db.update("DELETE FROM reactions WHERE ${card.anchor} = ? AND actor = ? AND emoji = ?", card.id, actor, clean) > 0
        }
        if (changed) {
            feed.publish("reaction.updated", buildJsonObject {
                put("card", card.json)
                put("messageId", if (card.kind == "message") card.id else null)
                put("projectId", row.projectId)
                put("actor", actor)
                put("emoji", clean)
                put("on", wanted)
            })
        }
        reactions(card) to wanted
    }

    /** One row per (agent, card), updated in place; `ack.updated` on every call. */
    fun acknowledge(agentId: String, card: CardRef, state: String): AckRow = feed.op {
        if (state !in ACK_STATES) throw invalid("state must be one of: ${ACK_STATES.joinToString(", ")}")
        db.one("SELECT id FROM agents WHERE id = ?", agentId) { it.getString(1) } ?: throw notFound("no such agent: $agentId")
        resolve(card)
        val now = feed.now()
        db.update(
            "INSERT INTO acknowledgements (id, agent_id, ${card.anchor}, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (agent_id, ${card.anchor}) WHERE ${card.anchor} IS NOT NULL " +
                "DO UPDATE SET state = excluded.state, updated_at = excluded.updated_at",
            Ulid.next(), agentId, card.id, state, now, now,
        )
        val ack = db.one(
            "SELECT ${Rows.ACK} FROM acknowledgements WHERE agent_id = ? AND ${card.anchor} = ?", agentId, card.id,
        ) { AckRow(Rows.ack(it)) }!!
        feed.publish("ack.updated", buildJsonObject {
            put("ackId", ack.id)
            put("agentId", ack.agentId)
            put("card", card.json)
            put("messageId", ack.messageId)
            put("taskId", ack.taskId)
            put("state", ack.state)
        })
        ack
    }

    /** `listWorkInScope`: [projectIds] null is every project. */
    fun listWork(projectIds: List<String>?, statuses: List<String>?, limit: Int): List<WorkItem> {
        for (status in statuses.orEmpty()) if (status !in STATUSES) throw invalid("${JsText.quote(status)} is not a work state")
        if (projectIds != null && projectIds.isEmpty()) return emptyList()
        val states = statuses ?: STATUSES
        if (states.isEmpty()) return emptyList()
        val marks = states.joinToString(", ") { "?" }
        val scope = projectIds?.let { ids -> "AND project_id IN (${ids.joinToString(", ") { "?" }})" } ?: ""
        val params = states + projectIds.orEmpty()
        return db.query(
            "SELECT 'update' AS kind, id, project_id, status, status_at, created_at, title, body, 'agent:' || agent_id AS author, " +
                "COALESCE(status_at, created_at) AS moved_at FROM updates WHERE status IN ($marks) AND deleted_at IS NULL $scope " +
                "UNION ALL SELECT 'message' AS kind, id, project_id, status, status_at, created_at, NULL AS title, body, author, " +
                "COALESCE(status_at, created_at) AS moved_at FROM messages WHERE status IN ($marks) AND deleted_at IS NULL $scope " +
                "ORDER BY moved_at DESC, id DESC LIMIT ?",
            *(params + params).toTypedArray(), limit,
        ) {
            val words = it.getString("body").jsTrim()
            WorkItem(
                kind = it.getString("kind"), id = it.getString("id"), projectId = it.getString("project_id"),
                status = it.getString("status"), statusAt = it.longOrNull("status_at"), createdAt = it.getLong("created_at"),
                title = it.getString("title"),
                summary = if (words.length > WORK_SUMMARY_CHARS) words.substring(0, WORK_SUMMARY_CHARS) + "…" else words,
                author = it.getString("author"),
            )
        }
    }

    companion object {
        val STATUSES = listOf("todo", "in_progress", "blocked", "done", "cancelled")
        val ACK_STATES = listOf("thinking", "read", "done")
        private val WORKABLE = listOf("update", "message")
        const val WORK_SUMMARY_CHARS = 240

        /** `oneCard`: ids trimmed, blank is absent; exactly one must be named. */
        fun oneCard(update: String? = null, message: String? = null, request: String? = null): CardRef {
            val given = listOf("update" to update, "message" to message, "request" to request)
                .mapNotNull { (kind, id) -> id?.jsTrim()?.takeIf { it.isNotEmpty() }?.let { CardRef(kind, it) } }
            if (given.isEmpty()) throw invalid("name one of: update, message, request")
            if (given.size > 1) throw invalid("name one of: update, message, request — not ${given.size} of them")
            return given.single()
        }
    }
}

/** `assertEmoji` (spec/02 §7.8). */
object Emoji {
    const val MAX_LENGTH = 24
    val SHORTCODES = linkedMapOf(
        "eyes" to "👀", "tick" to "✅", "check" to "✅", "white_check_mark" to "✅", "done" to "✅",
        "+1" to "👍", "thumbsup" to "👍", "-1" to "👎", "thumbsdown" to "👎", "tada" to "🎉", "rocket" to "🚀",
        "heart" to "❤️", "thinking" to "🤔", "thinking_face" to "🤔", "warning" to "⚠️", "x" to "❌",
        "fire" to "🔥", "pray" to "🙏", "smile" to "😄",
    )

    fun assert(raw: String): String {
        val trimmed = raw.jsTrim()
        if (trimmed.isEmpty()) throw invalid("emoji is required")
        SHORTCODES[trimmed.trimStart(':').trimEnd(':').lowercase()]?.let { return it }
        if (trimmed.length > MAX_LENGTH) throw invalid("an emoji is at most $MAX_LENGTH characters")
        if (JsText.hasSpace(trimmed)) throw invalid("an emoji has no spaces in it")
        if (trimmed.all { it.code <= 0x7f }) {
            throw invalid(
                "${JsText.quote(trimmed)} is not an emoji. Send the character itself, or one of: " +
                    SHORTCODES.keys.take(8).joinToString(", "),
            )
        }
        return trimmed
    }
}
