package dev.wildware.feed.server.domain

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * Feedback and feature requests (migration 067, server/spec/14-feedback.md).
 *
 * A person sends one from the app, and it is sent at once. An agent's is first a question to its
 * own team (a `form` request with [sendLabel] and [DISCARD], the text editable): only once an owner
 * or an admin sends it does it go. Sent means handed to [FeedbackReports] (Sentry's user feedback,
 * on SENTRY_DSN); a server with none keeps it here, `saved`. The team's own list is kept either way.
 */
class Feedback(private val app: App) {
    private val db get() = app.db
    private val clock get() = app.clock

    data class Item(
        val id: String,
        val teamId: String,
        val kind: String,
        val area: String?,
        val title: String,
        val body: String,
        val authorKind: String,
        val authorId: String,
        val authorName: String,
        val card: CardRef?,
        val status: String,
        val requestId: String?,
        val approvedBy: String?,
        val sentryEventId: String?,
        val createdAt: Long,
        val sentAt: Long?,
        val updatedAt: Long,
    )

    /** What a person or an agent sends, before it is checked. */
    data class Input(
        val kind: String,
        val area: String? = null,
        val title: String,
        val body: String,
        val mediaIds: List<String>? = null,
        val card: CardRef? = null,
        /** A person may let the people it goes to write back: their email goes with it. */
        val includeEmail: Boolean = false,
        /** The app's release, such as "wildware-feed@1.0.19", for the tags. */
        val client: String? = null,
    )

    /** Who the Send button names: FEEDBACK_RECIPIENT, or the people who run this server. */
    val recipient: String get() = app.config.feedbackRecipient ?: DEFAULT_RECIPIENT

    /** Whether sent feedback leaves the server (SENTRY_DSN), or is only saved here. */
    val sends: Boolean get() = dev.wildware.feed.server.FeedbackReports.sends

    /** The label on the agent's approval card that sends it; "Save it" where nothing leaves the server. */
    val sendLabel: String get() = if (sends) "Send to $recipient" else SAVE

    // ---- sending ------------------------------------------------------------------------------------

    /** A person's feedback: checked, kept and sent at once. */
    fun submitByPerson(person: People.Person, input: Input): Item {
        val checked = check(input, "human")
        limit("person", person.id)
        duplicateOf("person", person.id, checked.title)?.let {
            throw conflict("you already sent \"${it.title}\" (${it.status})")
        }
        val media = checkedMedia(input.mediaIds, "human")
        val id = Ulid.next(clock())
        insert(id, checked, "person", person.id, person.name, status = "pending", requestId = null, sentAt = null)
        attachMedia(id, media)
        val email = if (input.includeEmail) person.email else null
        return send(find(id)!!, email, input.client?.take(CLIENT_MAX))
    }

    /**
     * An agent's feedback: kept as pending, with a question to the team's owner or admins that
     * carries the text to send. Returns the item and the question.
     */
    fun submitByAgent(agent: Agents.Agent, input: Input, channel: String?): Pair<Item, OwnerRequest> {
        val checked = check(input, "agent:${agent.id}")
        limit("agent", agent.id)
        duplicateOf("agent", agent.id, checked.title)?.let {
            throw conflict("you already sent \"${it.title}\" (feedback ${it.id}, ${it.status}); list_feedback shows it")
        }
        val id = Ulid.next(clock())
        val what = if (checked.kind == "feature_request") "feature request" else "feedback"
        val (request, _) = app.requests.create(
            agent.id,
            NewRequest(
                kind = "form",
                question = "Send this $what to $recipient? “${checked.title}”",
                detail = "${agent.name} would like to send this $what${checked.area?.let { " (about ${areaLabel(it)})" }.orEmpty()} to $recipient. " +
                    "Edit the text if you like, then send it, or discard it. Only an owner or an admin can send it.",
                options = listOf(sendLabel, DISCARD),
                multiline = true,
                label = "What gets sent",
                default = checked.body,
                min = 1.0,
                max = BODY_MAX.toDouble(),
                project = channel,
                timeoutS = Requests.MAX_TIMEOUT_S.toDouble(),
                priority = "low",
                mediaIds = input.mediaIds?.takeIf { it.isNotEmpty() },
                refs = checked.card?.let { listOf(it) },
                feedbackId = id,
            ),
        )
        try {
            insert(id, checked, "agent", agent.id, agent.name, status = "pending", requestId = request.id, sentAt = null)
            attachMedia(id, input.mediaIds.orEmpty().distinct())
        } catch (e: Exception) {
            runCatching { app.requests.withdraw(request.id, agent.id) }
            throw e
        }
        val item = find(id)!!
        announce(item)
        return item to request
    }

    /**
     * Before an owner answers an agent's question: only an owner or an admin may, and the text they
     * send is checked as feedback is. Null when [requestId] is not a feedback question.
     */
    fun checkAnswer(requestId: String, person: People.Person, value: kotlinx.serialization.json.JsonElement?) {
        val request = app.requests.find(requestId) ?: return
        if (request.config?.get("feedbackId") == null) return
        if (person.role.rank < People.Role.ADMIN.rank) throw forbidden()
        val answer = value as? JsonObject ?: return
        if (answer["action"].jsString != request.options?.firstOrNull()) return
        val text = answer["text"].jsString ?: return
        noSecrets(text, "the text")
    }

    /** Before an owner dismisses a question: only an owner or an admin may dismiss a feedback one. */
    fun checkDismiss(requestId: String, person: People.Person) {
        val request = app.requests.find(requestId) ?: return
        if (request.config?.get("feedbackId") == null) return
        if (person.role.rank < People.Role.ADMIN.rank) throw forbidden()
    }

    /** [Requests.onSettled]: a feedback question answered with [sendLabel] sends it; anything else discards it. */
    fun settled(request: OwnerRequest, state: String) {
        val id = (request.config?.get("feedbackId") as? JsonPrimitive)?.content ?: return
        val item = find(id)?.takeIf { it.status == "pending" } ?: return
        val value = request.answer?.get("value") as? JsonObject
        val now = clock()
        val send = state == "answered" && value?.get("action").jsString == request.options?.firstOrNull()
        if (send) {
            val text = value?.get("text").jsString?.jsTrim()?.takeIf { it.isNotEmpty() && it.length <= BODY_MAX } ?: item.body
            db.update(
                "UPDATE feedback SET body = ?, approved_by = ?, updated_at = ? WHERE team_id = :team AND id = ? AND status = 'pending'",
                text, request.answeredBy, now, id,
            )
            send(find(id)!!, email = null, client = null)
        } else {
            db.update("UPDATE feedback SET status = 'discarded', updated_at = ? WHERE team_id = :team AND id = ? AND status = 'pending'", now, id)
            announce(find(id)!!)
        }
    }

    // ---- reading ------------------------------------------------------------------------------------

    fun find(id: String): Item? = db.one("SELECT $COLUMNS FROM feedback WHERE team_id = :team AND id = ?", id, row = ::item)

    /** This team's items, newest first; [status] narrows, [agentId] keeps one agent's. */
    fun list(status: String? = null, agentId: String? = null, limit: Int = LIST_MAX): List<Item> {
        if (status != null && status !in STATUSES) throw invalid("status must be one of: ${STATUSES.joinToString(", ")}")
        val where = StringBuilder("team_id = :team")
        val args = mutableListOf<Any?>()
        if (status != null) { where.append(" AND status = ?"); args += status }
        if (agentId != null) { where.append(" AND author_kind = 'agent' AND author_id = ?"); args += agentId }
        args += limit.coerceIn(1, LIST_MAX)
        return db.query("SELECT $COLUMNS FROM feedback WHERE $where ORDER BY seq DESC LIMIT ?", *args.toTypedArray(), row = ::item)
    }

    /** The media sent with [id], as the owner API shows media. */
    fun media(id: String): List<JsonObject> =
        db.query("SELECT id FROM media WHERE team_id = :team AND feedback_id = ? AND removed_at IS NULL ORDER BY seq", id) { it.getString(1) }
            .mapNotNull { app.uploads.mediaView(it) }

    // ---- views --------------------------------------------------------------------------------------

    /** The owner API's form (camelCase, times in ms). */
    fun ownerView(item: Item): JsonObject = buildJsonObject {
        put("id", item.id)
        put("kind", item.kind)
        put("area", item.area)
        put("title", item.title)
        put("body", item.body)
        put("status", item.status)
        put("author", buildJsonObject { put("kind", item.authorKind); put("id", item.authorId); put("name", item.authorName) })
        put("card", item.card?.json ?: JsonNull)
        put("requestId", item.requestId)
        put("approvedBy", item.approvedBy?.let { by -> buildJsonObject { put("id", by); put("name", app.people.nameOf(by)) } } ?: JsonNull)
        put("media", JsonArray(media(item.id)))
        put("createdAt", item.createdAt)
        put("sentAt", item.sentAt)
        put("updatedAt", item.updatedAt)
    }

    /** An agent's form (snake_case, ISO times); the body cut to [AGENT_BODY_MAX] in a list. */
    fun agentView(item: Item, full: Boolean): JsonObject = buildJsonObject {
        put("id", item.id)
        put("kind", item.kind)
        put("area", item.area)
        put("title", item.title)
        put("body", if (full || item.body.length <= AGENT_BODY_MAX) item.body else item.body.take(AGENT_BODY_MAX) + "…")
        put("status", item.status)
        put("author", buildJsonObject { put("kind", item.authorKind); put("name", item.authorName) })
        put("card", item.card?.json ?: JsonNull)
        put("request_id", item.requestId)
        put("created_at", Wire.isoOf(item.createdAt))
        put("sent_at", item.sentAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
    }

    // ---- checks -------------------------------------------------------------------------------------

    private data class Checked(val kind: String, val area: String?, val title: String, val body: String, val card: CardRef?)

    private fun check(input: Input, by: String): Checked {
        if (input.kind !in KINDS) throw invalid("kind must be one of: ${KINDS.joinToString(", ")}")
        val area = input.area?.jsTrim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (area != null && area !in AREAS) throw invalid("area must be one of: ${AREAS.joinToString(", ")}")
        val title = requiredText(input.title, "title", TITLE_MAX)
        val body = requiredText(input.body, "body", BODY_MAX)
        noSecrets(title, "the title")
        noSecrets(body, "the body")
        if ((input.mediaIds?.distinct()?.size ?: 0) > MEDIA_MAX) throw invalid("feedback carries at most $MEDIA_MAX pictures or files")
        val card = input.card?.let { CardRefs(db, clock).check(by, listOf(it)).single() }
        return Checked(input.kind, area, title, body, card)
    }

    /** Refuse text that looks like it holds a key, a token or a password. */
    private fun noSecrets(text: String, what: String) {
        if (SECRET_SHAPES.any { it.containsMatchIn(text) } || TOKEN_RUN.findAll(text).any { looksRandom(it.value) }) {
            throw invalid("$what looks like it holds a secret (a key, a token or a password). Take it out: feedback never carries secrets.")
        }
    }

    private fun looksRandom(run: String) = run.any(Char::isUpperCase) && run.any(Char::isLowerCase) && run.any(Char::isDigit)

    /** At most [PER_AUTHOR_PER_DAY] a day from one person or agent, and [PER_TEAM_PER_DAY] from one team. */
    private fun limit(kind: String, id: String) {
        val since = clock() - DAY_MS
        val mine = db.one("SELECT count(*) FROM feedback WHERE team_id = :team AND author_kind = ? AND author_id = ? AND created_at > ?", kind, id, since) { it.getLong(1) } ?: 0
        if (mine >= PER_AUTHOR_PER_DAY) throw DomainError("rate_limited", "That's $PER_AUTHOR_PER_DAY in a day already. Try again tomorrow.", 429)
        val team = db.one("SELECT count(*) FROM feedback WHERE team_id = :team AND created_at > ?", since) { it.getLong(1) } ?: 0
        if (team >= PER_TEAM_PER_DAY) throw DomainError("rate_limited", "Your team has sent $PER_TEAM_PER_DAY in a day already. Try again tomorrow.", 429)
    }

    /** This author's item with the same title from the last day that wasn't discarded. */
    private fun duplicateOf(kind: String, id: String, title: String): Item? = db.one(
        "SELECT $COLUMNS FROM feedback WHERE team_id = :team AND author_kind = ? AND author_id = ? AND lower(title) = ? AND status <> 'discarded' AND created_at > ? ORDER BY seq DESC LIMIT 1",
        kind, id, title.lowercase(), clock() - DAY_MS, row = ::item,
    )

    /** A person's uploads: each theirs (`human`), unattached, with its bytes in. */
    private fun checkedMedia(ids: List<String>?, author: String): List<String> {
        val unique = ids.orEmpty().distinct()
        for (id in unique) {
            if (!Reads.ID.matches(id)) throw invalid("not a media id: $id")
            val row = db.one(
                "SELECT author, sha256, update_id, message_id, request_id, task_id, feedback_id FROM media WHERE team_id = :team AND id = ? AND removed_at IS NULL", id,
            ) { rs -> Triple(rs.getString(1), rs.getString(2), (3..7).any { rs.getString(it) != null }) } ?: throw notFound("no such media: $id")
            if (row.first != author) throw invalid("media $id was uploaded by somebody else")
            if (row.second.isEmpty()) throw invalid("media $id has no bytes yet: upload the file first")
            if (row.third) throw invalid("media $id is already on another card")
        }
        return unique
    }

    private fun attachMedia(id: String, media: List<String>) {
        for (m in media) db.update("UPDATE media SET feedback_id = ? WHERE team_id = :team AND id = ? AND feedback_id IS NULL", id, m)
    }

    private fun insert(id: String, c: Checked, authorKind: String, authorId: String, authorName: String, status: String, requestId: String?, sentAt: Long?) {
        val now = clock()
        db.update(
            "INSERT INTO feedback (team_id, id, kind, area, title, body, author_kind, author_id, author_name, card_kind, card_id, status, request_id, created_at, sent_at, updated_at) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, c.kind, c.area, c.title, c.body, authorKind, authorId, authorName, c.card?.kind, c.card?.id, status, requestId, now, sentAt, now,
        )
    }

    private fun announce(item: Item) {
        app.bus.publish("feedback.updated", buildJsonObject {
            put("feedbackId", item.id)
            put("status", item.status)
        })
    }


    // ---- sending on -----------------------------------------------------------------------------------

    /**
     * Hands a pending [item] on: to Sentry's user feedback when the server has SENTRY_DSN (`sent`),
     * or keeps it here (`saved`). A failure to hand it on is logged and the item kept as `saved`:
     * never the sender's problem.
     */
    private fun send(item: Item, email: String?, client: String?): Item {
        val eventId = if (!sends) null else try {
            dev.wildware.feed.server.FeedbackReports.send(outgoing(item, email, client))
        } catch (e: Exception) {
            System.err.println("feedback: couldn't send ${item.id} on: $e")
            null
        }
        val now = clock()
        db.update(
            "UPDATE feedback SET status = ?, sentry_event_id = ?, sent_at = ?, updated_at = ? WHERE team_id = :team AND id = ? AND status = 'pending'",
            if (eventId != null) "sent" else "saved", eventId, now, now, item.id,
        )
        val sent = find(item.id)!!
        announce(sent)
        return sent
    }

    private fun outgoing(item: Item, email: String?, client: String?) = dev.wildware.feed.server.FeedbackReports.Outgoing(
        kind = item.kind,
        area = item.area,
        title = item.title,
        body = item.body,
        name = item.authorName,
        email = email,
        source = item.authorKind,
        team = teamHash(item.teamId),
        client = client,
        attachments = pictures(item.id),
    )

    /** The team as Sentry sees it: the same for every item of one team, and no way back to its id or name. */
    internal fun teamHash(teamId: String): String =
        dev.wildware.feed.server.auth.Tokens.hex(dev.wildware.feed.server.auth.Tokens.hmac(app.config.tokenSecret, "feedback-team:$teamId")).take(16)

    /** The pictures sent with [id], small enough to attach: at most [FeedbackReports.ATTACHMENTS_MAX], each at most 2 MB. */
    private fun pictures(id: String): List<dev.wildware.feed.server.FeedbackReports.File> {
        val rows = db.query(
            "SELECT id, mime, bytes FROM media WHERE team_id = :team AND feedback_id = ? AND kind = 'image' AND status = 'ready' AND removed_at IS NULL ORDER BY seq",
            id,
        ) { Triple(it.getString(1), it.getString(2), it.getLong(3)) }
        return rows.filter { it.third <= dev.wildware.feed.server.FeedbackReports.ATTACHMENT_MAX_BYTES }
            .take(dev.wildware.feed.server.FeedbackReports.ATTACHMENTS_MAX)
            .mapIndexedNotNull { i, (mediaId, mime, _) ->
                val key = app.mediaPaths.originalKey(mediaId, mime)
                runCatching { app.mediaPaths.store.local(key) { java.nio.file.Files.readAllBytes(it) } }.getOrNull()?.let { bytes ->
                    dev.wildware.feed.server.FeedbackReports.File("picture-${i + 1}.${dev.wildware.feed.server.media.Mime.extensionOf(mime)}", mime, bytes)
                }
            }
    }

    private fun item(rs: ResultSet): Item = Item(
        id = rs.getString("id"),
        teamId = rs.getString("team_id"),
        kind = rs.getString("kind"),
        area = rs.getString("area"),
        title = rs.getString("title"),
        body = rs.getString("body"),
        authorKind = rs.getString("author_kind"),
        authorId = rs.getString("author_id"),
        authorName = rs.getString("author_name"),
        card = rs.getString("card_kind")?.let { CardRef(it, rs.getString("card_id")) },
        status = rs.getString("status"),
        requestId = rs.getString("request_id"),
        approvedBy = rs.getString("approved_by"),
        sentryEventId = rs.getString("sentry_event_id"),
        createdAt = rs.getLong("created_at"),
        sentAt = rs.longOrNull("sent_at"),
        updatedAt = rs.getLong("updated_at"),
    )

    companion object {
        val KINDS = listOf("feedback", "feature_request")
        val AREAS = listOf("feed", "threads", "voice", "secrets", "apps", "agents", "billing", "other")
        val STATUSES = listOf("pending", "discarded", "sent", "saved")
        const val DISCARD = "Discard"
        const val SAVE = "Save it"
        const val DEFAULT_RECIPIENT = "the people who run this server"
        const val CLIENT_MAX = 80
        const val TITLE_MAX = 120
        const val BODY_MAX = 5_000
        const val MEDIA_MAX = 6
        const val LIST_MAX = 200
        const val AGENT_BODY_MAX = 300
        const val PER_AUTHOR_PER_DAY = 20L
        const val PER_TEAM_PER_DAY = 200L
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val COLUMNS = "seq, team_id, id, kind, area, title, body, author_kind, author_id, author_name, card_kind, card_id, status, " +
            "request_id, approved_by, sentry_event_id, created_at, sent_at, updated_at"

        fun areaLabel(area: String) = when (area) {
            "agents" -> "agents and the plugin"
            else -> area
        }

        private fun forbidden() = DomainError("forbidden", "Only an owner or an admin can send or discard an agent's feedback.", 403)

        /** Shapes of well-known keys and tokens. */
        private val SECRET_SHAPES = listOf(
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Regex("\\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{10,}"),
            Regex("\\bsk-[A-Za-z0-9_-]{20,}"),
            Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
            Regex("\\bgithub_pat_[A-Za-z0-9_]{30,}"),
            Regex("\\bAKIA[0-9A-Z]{16}\\b"),
            Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Regex("\\bAIza[0-9A-Za-z_-]{35}"),
        )

        /** A long run of token characters; it counts when it mixes cases and digits, as random keys do. */
        private val TOKEN_RUN = Regex("[A-Za-z0-9_-]{32,}")
    }
}
