package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.Viewer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Who a piece of work is assigned to (migration 072, spec/16): up to [MAX] people
 * (`member:<membership id>`) or agents (`agent:<agent id>`) on an update or a post.
 *
 * Anyone with Post on the card's channel may assign, and an agent may too. Each assignee must be able
 * to read the channel. Assigning a card that is not work yet makes it work ("todo").
 *
 * Every change leaves a line in the card's thread (a message with source [Messages.ASSIGNMENT]) that
 * says who did what. It @mentions the agents it assigns or unassigns, so only they hear it, wherever
 * it is, and it follows the thread for them: that line is the agent's notice. A person assigned is
 * told by the bell and their phones ([Notifications], the push), and follows the thread.
 */
class Assignees(private val feed: Feed) {
    private val db get() = feed.db

    /** One assignee, as reads show it. [kind] is "agent" or "person". */
    data class Who(val principal: String, val kind: String, val id: String, val name: String, val handle: String? = null) {
        val json: JsonObject get() = buildJsonObject {
            put("principal", principal)
            put("kind", kind)
            put("id", id)
            put("name", name)
            handle?.let { put("handle", it) }
        }
    }

    /** What a change did: who is assigned now, who was added and who was taken off, and the thread line. */
    class Change(val card: CardRef, val assignees: List<Who>, val added: List<Who>, val removed: List<Who>, val lineId: String?, val status: String?)

    /** Everyone who could be assigned in this team: live people, then live agents. */
    fun everyone(): List<Who> {
        val people = db.query(
            "SELECT m.id, u.name, m.handle FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.removed_at IS NULL ORDER BY m.seq",
        ) { Who(Viewer.MEMBER + it.getString(1), PERSON, it.getString(1), it.getString(2), it.getString(3)) }
        val agents = db.query("SELECT id, name FROM agents WHERE team_id = :team AND revoked_at IS NULL ORDER BY lower(name), seq") {
            Who(Viewer.AGENT + it.getString(1), AGENT, it.getString(1), it.getString(2))
        }
        return people + agents
    }

    /** The assignees of each card in [ids] (all of one [kind]), oldest assignment first; only live people and agents. */
    fun forCards(kind: String, ids: List<String>): Map<String, List<Who>> {
        if (ids.isEmpty()) return emptyMap()
        val known = everyone().associateBy { it.principal }
        val out = LinkedHashMap<String, MutableList<Who>>()
        for (chunk in ids.distinct().chunked(CHUNK)) {
            db.query(
                "SELECT card_id, principal FROM card_assignees WHERE team_id = :team AND card_kind = ? AND card_id IN (${chunk.joinToString(", ") { "?" }}) ORDER BY seq",
                kind, *chunk.toTypedArray(),
            ) { it.getString(1) to it.getString(2) }.forEach { (card, principal) ->
                known[principal]?.let { out.getOrPut(card) { mutableListOf() }.add(it) }
            }
        }
        return out
    }

    /** The card's assignees. A card this viewer can't read answers as missing. */
    fun on(card: CardRef): List<Who> {
        checkKind(card)
        feed.cards.resolve(card)
        return forCards(card.kind, listOf(card.id))[card.id].orEmpty()
    }

    /** Who may be assigned to [card], each with whether they can read its channel: the picker's list. */
    fun candidates(card: CardRef): List<Pair<Who, Boolean>> {
        checkKind(card)
        val row = feed.cards.resolve(card)
        return everyone().map { it to feed.access.canRead(it.principal, row.projectId) }
    }

    /**
     * Who [text] names, for [actor] (`agent:<id>` or `member:<id>`, which `me` means): `agent:<id>` or
     * `member:<id>`, an agent's id or name, a person's membership id, handle (with or without `@`) or
     * name. Case does not matter. A name that is both a person's and an agent's must be told apart.
     */
    fun resolve(text: String, actor: String, people: List<Who> = everyone()): Who {
        val raw = text.jsTrim()
        if (raw.isEmpty()) throw invalid("an assignee is a name or an id, not blank")
        if (raw.equals(ME, ignoreCase = true)) {
            return people.firstOrNull { it.principal == actor } ?: throw invalid("\"me\" is only for a person or agent on this team")
        }
        people.firstOrNull { it.principal == raw }?.let { return it }
        people.firstOrNull { it.id == raw }?.let { return it }
        val bare = raw.removePrefix("@").lowercase()
        val byName = people.filter { it.name.lowercase() == bare || it.handle?.lowercase() == bare }.distinctBy { it.principal }
        return when (byName.size) {
            1 -> byName.single()
            0 -> throw notFound("nobody on this team is called ${JsText.quote(raw)}: use a person's or agent's name, or their id")
            else -> throw invalid(
                "${JsText.quote(raw)} is more than one of your team: name one by id (" +
                    byName.joinToString(", ") { it.principal } + ")",
            )
        }
    }

    /**
     * Change [card]'s assignees: [MODE_ADD] these, [MODE_REMOVE] these, or [MODE_SET] exactly these
     * (an empty list unassigns everyone). [actor] is who does it, as `agent:<id>` or `member:<id>`.
     * [address] is the team's web address, for the link an assigned agent is sent.
     */
    fun change(card: CardRef, names: List<String>, mode: String, actor: String, address: String?): Change = feed.op {
        checkKind(card)
        if (mode !in MODES) throw invalid("mode must be one of: ${MODES.joinToString(", ")}")
        if (mode != MODE_SET && names.isEmpty()) throw invalid("name at least one assignee")
        if (names.size > MAX * 4) throw invalid("name at most $MAX assignees")
        // Post on the card's channel: the same as moving it.
        val row = feed.cards.resolveToWrite(card)
        val people = everyone()
        val named = names.map { resolve(it, actor, people) }.distinctBy { it.principal }
        val now = forCards(card.kind, listOf(card.id))[card.id].orEmpty()
        val nowSet = now.map { it.principal }.toSet()
        val namedSet = named.map { it.principal }.toSet()
        val added = when (mode) {
            MODE_REMOVE -> emptyList()
            else -> named.filter { it.principal !in nowSet }
        }
        val removed = when (mode) {
            MODE_ADD -> emptyList()
            MODE_REMOVE -> now.filter { it.principal in namedSet }
            else -> now.filter { it.principal !in namedSet }
        }
        val after = now.size - removed.size + added.size
        if (after > MAX) throw invalid("a card can have at most $MAX assignees (it would have $after)")
        requireReaders(added, row.projectId)
        if (added.isEmpty() && removed.isEmpty()) {
            return@op Change(card, now, emptyList(), emptyList(), null, row.status)
        }

        // Assigning makes it work, if it was not yet.
        val status = if (added.isNotEmpty() && row.status == null) feed.cards.setStatus(card, "todo") else row.status

        val at = feed.now()
        for (who in removed) {
            db.update("DELETE FROM card_assignees WHERE team_id = :team AND card_kind = ? AND card_id = ? AND principal = ?", card.kind, card.id, who.principal)
        }
        val newIds = added.map { who ->
            Ulid.next(at).also { id ->
                db.update(
                    "INSERT INTO card_assignees (team_id, id, card_kind, card_id, principal, assigned_by, assigned_at) VALUES (:team, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (team_id, card_kind, card_id, principal) DO NOTHING",
                    id, card.kind, card.id, who.principal, actor, at,
                )
            }
        }

        // The thread line: who did what. It names the agents, so only they hear it.
        val line = postLine(card, row, actor, added, removed, address)
        if (line != null && newIds.isNotEmpty()) {
            db.update(
                "UPDATE card_assignees SET message_id = ? WHERE team_id = :team AND id IN (${newIds.joinToString(", ") { "?" }})",
                line.id, *newIds.toTypedArray(),
            )
        }
        // A person assigned follows the thread from here, as an agent does by being named in it.
        val subs = Subscriptions(db, feed.clock)
        val thread = runCatching { subs.threadAt(card).first }.getOrNull()
        if (thread != null) {
            for (who in added.filter { it.kind == PERSON }) {
                if (subs.followThread(who.principal, thread, row.projectId, line?.seq ?: 0)) {
                    feed.publish("subscriptions.updated", People.followsChanged(db, who.id))
                }
            }
        }

        val result = forCards(card.kind, listOf(card.id))[card.id].orEmpty()
        feed.publish("card.assigned", buildJsonObject {
            put("card", card.json)
            put("projectId", row.projectId)
            put("by", actor)
            put("assignees", JsonArray(result.map { JsonPrimitive(it.principal) }))
            put("added", JsonArray(added.map { JsonPrimitive(it.principal) }))
            put("removed", JsonArray(removed.map { JsonPrimitive(it.principal) }))
        })
        feed.cards.find(card)?.let { feed.cards.announce(card, it) }
        Change(card, result, added, removed, line?.id, status)
    }

    /**
     * Assigned work moved to [status], by [by]: a line in its thread for the agents waiting on it,
     * those that assigned it and those assigned, never [by] itself. Nothing for a card nobody was
     * assigned, or when no agent is waiting, so ordinary moves on the board stay quiet.
     */
    fun moved(card: CardRef, status: String?, by: String) {
        if (card.kind !in WORKABLE) return
        val rows = db.query("SELECT principal, assigned_by FROM card_assignees WHERE team_id = :team AND card_kind = ? AND card_id = ?", card.kind, card.id) {
            it.getString(1) to it.getString(2)
        }
        if (rows.isEmpty()) return
        val live = everyone().associateBy { it.principal }
        val waiting = rows.flatMap { listOf(it.first, it.second) }.distinct()
            .filter { it != by && it.startsWith(Viewer.AGENT) }.mapNotNull { live[it] }
        if (waiting.isEmpty()) return
        val row = feed.cards.find(card) ?: return
        if (!row.projectId.let { p -> waiting.any { feed.access.canRead(it.principal, p) } }) return
        val assignees = rows.mapNotNull { live[it.first] }
        val said = when (status) {
            null -> "took the work mark off this"
            else -> "marked this ${status.replace('_', ' ')}"
        }
        val body = StringBuilder(said)
            .append("\n\n").append(waiting.joinToString(", ") { "@" + it.name }).append(": ")
            .append(quiet(cardTitle(card))).append(" is now ").append(status?.replace('_', ' ') ?: "not work")
            .append(" (assigned to ").append(assignees.joinToString(", ") { quiet(it.name) }.ifEmpty { "nobody now" }).append(").")
        if (status == "done") body.append(" Pick up whatever was waiting on it.")
        feed.messages.post(
            Messages.Post(
                author = if (by.startsWith(Viewer.AGENT)) by else Messages.HUMAN,
                body = body.toString(),
                updateId = if (card.kind == "update") card.id else null,
                replyTo = if (card.kind == "message") card.id else null,
                userId = if (by.startsWith(Viewer.MEMBER)) by.removePrefix(Viewer.MEMBER) else null,
                statusLine = true,
            ),
        )
    }

    /**
     * The people and agents [names] would assign on a new card in [projectId], checked as [change]
     * checks them, before anything is written: a post that would then be refused is never made.
     */
    fun precheck(projectId: String?, names: List<String>, actor: String): List<Who> {
        val people = everyone()
        val named = names.map { resolve(it, actor, people) }.distinctBy { it.principal }
        if (named.size > MAX) throw invalid("a card can have at most $MAX assignees (it would have ${named.size})")
        requireReaders(named, projectId)
        return named
    }

    /** Each one must be able to read the card's channel. */
    private fun requireReaders(who: List<Who>, projectId: String?) {
        for (w in who) {
            if (!feed.access.canRead(w.principal, projectId)) {
                val channel = projectId?.let { id -> db.one("SELECT slug FROM projects WHERE team_id = :team AND id = ?", id) { it.getString(1) } }
                throw forbidden(
                    "${w.name} can't read ${channel?.let { "#$it" } ?: "this channel"}, so they can't be assigned work in it. " +
                        "Someone who manages the channel can give them Read first.",
                )
            }
        }
    }

    /** The line in the card's thread, written as [actor]: a person as themself, an agent as itself. */
    private fun postLine(card: CardRef, row: CardRow, actor: String, added: List<Who>, removed: List<Who>, address: String?): MessageRow? {
        val author = if (actor.startsWith(Viewer.AGENT)) actor else Messages.HUMAN
        val userId = if (actor.startsWith(Viewer.MEMBER)) actor.removePrefix(Viewer.MEMBER) else null
        val title = cardTitle(card)
        val link = link(address, row.projectId, card.id)
        val body = lineBody(added, removed, title, link)
        return feed.messages.post(
            Messages.Post(
                author = author,
                body = body,
                updateId = if (card.kind == "update") card.id else null,
                replyTo = if (card.kind == "message") card.id else null,
                userId = userId,
                assignment = true,
            ),
        )
    }

    /** The card's title, as the board shows it: an update's title, else the first line of its words. */
    fun cardTitle(card: CardRef): String {
        val text = when (card.kind) {
            "update" -> db.one("SELECT title, body FROM updates WHERE team_id = :team AND id = ?", card.id) { (it.getString(1)?.takeIf { t -> t.jsTrim().isNotEmpty() }) ?: it.getString(2) }
            else -> db.one("SELECT body FROM messages WHERE team_id = :team AND id = ?", card.id) { it.getString(1) }
        }
        return Reads.cut(Reads.firstLine(text ?: ""), Board.TITLE_MAX)
    }

    private fun link(address: String?, projectId: String?, cardId: String): String? {
        if (address.isNullOrBlank()) return null
        val slug = projectId?.let { id -> db.one("SELECT slug FROM projects WHERE team_id = :team AND id = ?", id) { it.getString(1) } }
        val focus = "?focus=${java.net.URLEncoder.encode(cardId, Charsets.UTF_8)}"
        return if (slug != null) "${address.trimEnd('/')}/projects/$slug$focus" else "${address.trimEnd('/')}/$focus"
    }

    companion object {
        const val MAX = 5
        const val AGENT = "agent"
        const val PERSON = "person"
        const val ME = "me"
        const val MODE_ADD = "add"
        const val MODE_REMOVE = "remove"
        const val MODE_SET = "set"
        val MODES = listOf(MODE_ADD, MODE_REMOVE, MODE_SET)
        private val WORKABLE = listOf("update", "message")
        private const val CHUNK = 200

        /** A live assignee, in SQL, for rows aliased `a`: an agent not revoked or a person still on the team. */
        const val LIVE_SQL = "(a.principal IN (SELECT 'agent:' || ag.id FROM agents AS ag WHERE ag.team_id = :team AND ag.revoked_at IS NULL) " +
            "OR a.principal IN (SELECT 'member:' || mb.id FROM memberships AS mb WHERE mb.team_id = :team AND mb.removed_at IS NULL))"

        /** SQL for a card id column: assigned to the principal in one `?`. */
        fun assignedToSql(column: String) =
            "$column IN (SELECT a.card_id FROM card_assignees AS a WHERE a.team_id = :team AND a.principal = ?)"

        /** SQL for a card id column: assigned to nobody (live). */
        fun unassignedSql(column: String) =
            "$column NOT IN (SELECT a.card_id FROM card_assignees AS a WHERE a.team_id = :team AND $LIVE_SQL)"

        private fun checkKind(card: CardRef) {
            if (card.kind !in WORKABLE) throw invalid("a ${card.kind} cannot be assigned: only an update or a post, which are work")
        }

        /** `@` in someone else's words must not @mention anyone: a zero-width space after it. */
        internal fun quiet(text: String) = text.replace("@", "@​")

        /**
         * The line's words. The first line is what people see ("assigned @Rover and Sam"); the rest
         * is for the agents it names, who read the whole of it.
         */
        fun lineBody(added: List<Who>, removed: List<Who>, title: String, link: String?): String {
            fun name(w: Who) = if (w.kind == AGENT) "@" + w.name else quiet(w.name)
            fun list(ws: List<Who>): String = when (ws.size) {
                1 -> name(ws[0])
                else -> ws.dropLast(1).joinToString(", ") { name(it) } + " and " + name(ws.last())
            }
            val first = listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { "assigned ${list(it)}" },
                removed.takeIf { it.isNotEmpty() }?.let { "unassigned ${list(it)}" },
            ).joinToString("; ")
            val out = StringBuilder(first)
            val addedAgents = added.filter { it.kind == AGENT }
            val removedAgents = removed.filter { it.kind == AGENT }
            if (addedAgents.isNotEmpty()) {
                out.append("\n\n").append(addedAgents.joinToString(", ") { quiet(it.name) })
                    .append(": you were assigned: ").append(quiet(title))
                if (link != null) out.append(" — ").append(link)
                out.append("\nTake it with set_status in_progress, say so in this thread, then finish it (set_status done) or hand it back.")
            }
            if (removedAgents.isNotEmpty()) {
                out.append("\n\n").append(removedAgents.joinToString(", ") { quiet(it.name) })
                    .append(": you are no longer assigned to this. Stop work on it unless someone asks you to carry on.")
            }
            return out.toString()
        }
    }
}

val Feed.assignees: Assignees get() = Assignees(this)
