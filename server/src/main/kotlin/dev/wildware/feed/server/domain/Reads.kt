package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.domain.Rows.plus
import dev.wildware.feed.server.domain.Rows.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The read side of the owner API (spec/02, spec/03 §5, §6, §9). Every list is read with the TS
 * server's own SQL, so ties come out in the same order, and every shape is built in its key order.
 */
class Reads(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    data class ProjectRef(val id: String, val slug: String, val name: String = "")

    /** Card ids per kind, in the order the TS `Object.entries` walks them. */
    data class CardScope(val update: List<String>, val message: List<String>, val request: List<String>)

    data class UpdatesPage(val json: JsonObject, val ids: List<String>)

    // ---- projects -------------------------------------------------------------------------------

    private fun projectRef(sql: String, value: String) =
        db.one("SELECT id, slug, name FROM projects WHERE $sql = ?", value) { ProjectRef(it.getString(1), it.getString(2), it.getString(3)) }

    /**
     * `findProject` (TS `src/domain/projects.ts`): an id-shaped reference tries the id first, anything
     * else the slug first (lowercased); each falls back to the other. Blank is refused.
     */
    fun findProject(reference: String): ProjectRef? {
        val ref = reference.jsTrim()
        if (ref.isEmpty()) throw invalid("project is required")
        return if (ID.matches(ref)) {
            projectRef("id", ref) ?: projectRef("slug", ref.lowercase())
        } else {
            projectRef("slug", ref.lowercase()) ?: projectRef("id", ref)
        }
    }

    fun resolveProject(reference: String): ProjectRef =
        findProject(reference) ?: throw notFound("no such project: ${reference.jsTrim()}")

    /** `findProjectBySlug`: the exact slug, nothing else (device alert lists use this). */
    fun projectIdBySlug(slug: String): String? = projectRef("slug", slug)?.id

    fun projectSlugsById(): Map<String, String> = db.query("SELECT id, slug FROM projects") { it.getString(1) to it.getString(2) }.toMap()

    fun listProjects(status: String? = null): List<JsonObject> = db.query(
        "SELECT ${Rows.PROJECT} FROM projects WHERE (? IS NULL OR status = ?) ORDER BY pinned DESC, seq DESC",
        status, status,
    ) { Rows.project(it) }

    // ---- updates --------------------------------------------------------------------------------

    /**
     * `listUpdates` (TS `src/domain/updates.ts`) and the snapshot's dressing of it: the limit is
     * checked, then the cursor, then the project.
     */
    fun updatesPage(project: String?, limit: Int, cursor: String?): UpdatesPage {
        val pageLimit = minOf(limit, UPDATES_MAX)
        val beforeSeq: Any? = when {
            cursor.isNullOrEmpty() -> null
            !Regex("^[0-9]+$").matches(cursor) -> throw invalid("cursor is not one this server issued")
            else -> cursor.toLongOrNull() ?: cursor.toDouble()
        }
        val projectId = project?.let { resolveProject(it).id }
        val rows = db.query(
            "SELECT ${Rows.UPDATE} FROM updates WHERE (? IS NULL OR project_id = ?) AND (? IS NULL OR seq < ?) " +
                "AND deleted_at IS NULL ORDER BY seq DESC LIMIT ?",
            projectId, projectId, beforeSeq, beforeSeq, pageLimit + 1,
        ) { Rows.update(it) }
        val hasMore = rows.size > pageLimit
        val updates = if (hasMore) rows.take(pageLimit) else rows
        val ids = updates.map { it.str("id")!! }
        val reactions = reactionsFor("update", ids)
        val items = updates.zip(ids).map { (update, id) ->
            val extra = mutableListOf<Pair<String, JsonElement>>(
                "media" to JsonArray(mediaFor("update_id", id)),
                "embeds" to JsonArray(embedsFor("update_id", id)),
                "reactions" to JsonArray(reactions.filter { it["card"].str("id") == id }),
            )
            update.plus(*extra.toTypedArray())
        }
        val nextCursor = if (hasMore && updates.isNotEmpty()) JsonPrimitive(updates.last()["seq"].toString()) else JsonNull
        return UpdatesPage(
            buildJsonObject {
                put("items", JsonArray(items))
                put("nextCursor", nextCursor)
                put("hasMore", hasMore)
            },
            ids,
        )
    }

    // ---- messages -------------------------------------------------------------------------------

    data class ThreadQuery(
        val updateId: String? = null,
        val requestId: String? = null,
        val taskId: String? = null,
        val replyTo: String? = null,
        val project: String? = null,
        /** JavaScript's `Number(limit)`; null for the default. */
        val limit: Double? = null,
    )

    /** `listThread` (TS `src/domain/messages.ts`): the newest live messages, oldest first, plus their posts. */
    fun listThread(query: ThreadQuery): List<JsonObject> {
        val limit = when (val asked = query.limit) {
            null -> MESSAGES_MAX
            else -> {
                if (!Js.isInteger(asked) || asked < 1) throw invalid("limit must be a positive integer")
                minOf(asked, MESSAGES_MAX.toDouble()).toInt()
            }
        }
        if (query.replyTo != null) {
            val post = messageById(query.replyTo) ?: return emptyList()
            if (post["deletedAt"] !is JsonNull) return emptyList()
            val replies = db.query(
                "SELECT ${Rows.MESSAGE} FROM messages WHERE reply_to = ? AND deleted_at IS NULL ORDER BY seq ASC",
                post.str("id"),
            ) { Rows.message(it) }
            // `slice(-(limit - 1))`: a limit of 1 is `slice(-0)`, which is all of them.
            val kept = if (limit == 1) replies else replies.takeLast(limit - 1)
            return listOf(post) + kept
        }
        val projectId = query.project?.let { resolveProject(it).id }
        val window = db.query(
            "SELECT ${Rows.MESSAGE} FROM messages WHERE (? IS NULL OR project_id = ?) AND (? IS NULL OR update_id = ?) " +
                "AND (? IS NULL OR task_id = ?) AND (? IS NULL OR request_id = ?) AND deleted_at IS NULL ORDER BY seq DESC LIMIT ?",
            projectId, projectId, query.updateId, query.updateId, query.taskId, query.taskId, query.requestId, query.requestId, limit,
        ) { Rows.message(it) }.reversed()
        return withParents(window)
    }

    private fun messageById(id: String): JsonObject? =
        db.one("SELECT ${Rows.MESSAGE} FROM messages WHERE id = ?", id) { Rows.message(it) }

    private fun withParents(window: List<JsonObject>): List<JsonObject> {
        val present = window.map { it.str("id") }.toSet()
        val wanted = LinkedHashSet<String>()
        for (m in window) m.str("replyTo")?.takeIf { it.isNotEmpty() && it !in present }?.let(wanted::add)
        if (wanted.isEmpty()) return window
        val heads = wanted.mapNotNull { messageById(it) }.filter { it["deletedAt"] is JsonNull }
        return (heads + window).sortedBy { it["seq"]!!.toString().toLong() }
    }

    fun acksForMessages(ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.ACK} FROM acknowledgements WHERE message_id IN (${marks(ids)}) ORDER BY seq ASC",
            *ids.toTypedArray(),
        ) { Rows.ack(it) }
    }

    fun deliveriesFor(ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.DELIVERY} FROM message_deliveries WHERE seq IN (SELECT min(seq) FROM message_deliveries " +
                "WHERE message_id IN (${marks(ids)}) GROUP BY message_id, agent_id) ORDER BY seq",
            *ids.toTypedArray(),
        ) { Rows.delivery(it) }
    }

    fun reactionsFor(kind: String, ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.REACTION} FROM reactions WHERE ${anchor(kind)} IN (${marks(ids)}) ORDER BY seq",
            *ids.toTypedArray(),
        ) { Rows.reaction(it) }
    }

    /** `{messageId: [...]}` for the messages that have some, in message order. */
    fun byMessage(ids: List<String>, read: (String) -> List<JsonObject>): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply {
            for (id in ids) read(id).takeIf { it.isNotEmpty() }?.let { put(id, JsonArray(it)) }
        })

    // ---- media and embeds -----------------------------------------------------------------------

    /** `MediaAttachment` rows on one card, by seq (TS `src/domain/media.ts`). */
    fun mediaFor(anchorColumn: String, cardId: String): List<JsonObject> {
        val rows = db.query("SELECT ${Rows.MEDIA} FROM media WHERE $anchorColumn = ? ORDER BY seq", cardId) { rs ->
            MediaRow(
                id = rs.getString("id"),
                kind = rs.getString("kind"),
                mime = rs.getString("mime"),
                status = rs.getString("status"),
                sha256 = rs.getString("sha256"),
                json = buildJsonObject {
                    put("id", rs.getString("id"))
                    put("updateId", rs.getString("update_id"))
                    put("messageId", rs.getString("message_id"))
                    put("requestId", rs.getString("request_id"))
                    put("taskId", rs.getString("task_id"))
                    put("kind", rs.getString("kind"))
                    put("mime", rs.getString("mime"))
                    put("status", rs.getString("status"))
                    put("bytes", rs.longOrNull("bytes"))
                    put("width", rs.longOrNull("width"))
                    put("height", rs.longOrNull("height"))
                    put("durationMs", rs.longOrNull("duration_ms"))
                    put("filename", rs.getString("filename"))
                },
            )
        }
        return rows.map { it.json.plus("variants" to JsonArray(variantsOf(it).map(::JsonPrimitive))) }
    }

    private class MediaRow(val id: String, val kind: String, val mime: String, val status: String, val sha256: String, val json: JsonObject)

    /** `derivativesFor` (TS `src/media/serve.ts`): the addresses that answer right now, from rows alone. */
    private fun variantsOf(media: MediaRow): List<String> {
        if (media.status == "failed") return emptyList()
        val download = media.kind == "file"
        if (!download && media.mime !in RENDERABLE_MIMES) return emptyList()
        if (download) return if (media.sha256.isEmpty()) emptyList() else listOf("original")
        val variants = mutableListOf<String>()
        if (media.sha256.isNotEmpty()) variants += "original"
        db.query("SELECT kind, width FROM derivatives WHERE media_id = ? ORDER BY kind, width, seq", media.id) {
            it.getString(1) to it.longOrNull("width")
        }.forEach { (kind, width) ->
            when {
                kind == "thumb" && width == 640L -> variants += "thumb-640"
                kind == "thumb" && width == 1600L -> variants += "thumb-1600"
                kind == "poster" -> variants += "poster"
                kind == "mp4" -> variants += "video"
            }
        }
        return variants
    }

    /** `EmbedView`s on one card, by seq; one whose type is gone or unreadable is dropped. */
    fun embedsFor(anchorColumn: String, cardId: String): List<JsonObject> =
        db.query("SELECT ${Rows.EMBED} FROM embeds WHERE $anchorColumn = ? ORDER BY seq", cardId) { rs ->
            EmbedRow(
                rs.getString("id"), rs.getString("type_id"), rs.getString("update_id"), rs.getString("message_id"),
                rs.getString("request_id"), rs.getString("task_id"), rs.getString("data"), rs.getString("source_url"),
                rs.getLong("created_at"),
            )
        }.mapNotNull { row ->
            val type = db.one("SELECT slug, name, spec FROM embed_types WHERE id = ?", row.typeId) {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            } ?: return@mapNotNull null
            val spec = Embeds.specOf(type.third) ?: return@mapNotNull null
            buildJsonObject {
                put("id", row.id)
                put("updateId", row.updateId)
                put("messageId", row.messageId)
                put("requestId", row.requestId)
                put("taskId", row.taskId)
                put("type", type.first)
                put("typeName", type.second)
                put("spec", spec)
                put("data", Embeds.dataOf(row.data))
                put("sourceUrl", row.sourceUrl)
                put("createdAt", row.createdAt)
            }
        }

    private class EmbedRow(
        val id: String, val typeId: String, val updateId: String?, val messageId: String?, val requestId: String?,
        val taskId: String?, val data: String, val sourceUrl: String?, val createdAt: Long,
    )

    // ---- tags, fields, apps ---------------------------------------------------------------------

    /** `listTags`: one project's (a truthy reference is resolved) or every project's, by seq. */
    fun listTags(project: String?): List<JsonObject> {
        if (project.isNullOrEmpty()) return db.query("SELECT ${Rows.TAG} FROM tags ORDER BY seq") { Rows.tag(it) }
        val id = resolveProject(project).id
        return db.query("SELECT ${Rows.TAG} FROM tags WHERE project_id IN (?) ORDER BY seq", id) { Rows.tag(it) }
    }

    private fun <T> perKind(scope: CardScope, read: (anchor: String, ids: List<String>) -> List<T>): List<T> =
        listOf("update_id" to scope.update, "message_id" to scope.message, "request_id" to scope.request)
            .filter { it.second.isNotEmpty() }
            .flatMap { (anchor, ids) -> read(anchor, ids) }

    fun cardTags(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.CARD_TAG} FROM card_tags WHERE $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { Rows.cardTag(it) }
    }

    fun cardFields(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.FIELD} FROM card_fields WHERE $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { Rows.field(it) }
    }

    /** AppView (TS `src/domain/apps.ts` `viewData`); a view whose app is gone is dropped. */
    fun cardApps(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.VIEW} FROM app_views WHERE $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { rs ->
            ViewRow(
                rs.getString("id"), Rows.cardUpdateFirst(rs), rs.getString("app_id"), rs.getString("title"), rs.getString("data"),
                rs.getString("state"), rs.getString("author"), rs.getString("agent_id"), rs.getLong("created_at"), rs.getLong("updated_at"),
            )
        }
    }.mapNotNull { view ->
        val app = db.one("SELECT uri, name, description, url FROM apps WHERE id = ?", view.appId) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        } ?: return@mapNotNull null
        buildJsonObject {
            put("id", view.id)
            put("card", view.card)
            put("uri", app[0])
            put("name", app[1])
            put("description", app[2])
            put("url", app[3])
            put("title", view.title)
            put("data", jsonObjectOrEmpty(view.data))
            put("state", view.state?.let(::jsonObjectOrEmpty) ?: JsonNull)
            put("author", view.author)
            put("agentId", view.agentId)
            put("createdAt", view.createdAt)
            put("updatedAt", view.updatedAt)
        }
    }

    private class ViewRow(
        val id: String, val card: JsonObject, val appId: String, val title: String?, val data: String, val state: String?,
        val author: String, val agentId: String?, val createdAt: Long, val updatedAt: Long,
    )

    private fun jsonObjectOrEmpty(text: String): JsonObject =
        runCatching { Js.parse(text) as? JsonObject }.getOrNull() ?: Rows.empty

    // ---- agents ---------------------------------------------------------------------------------

    /** Every agent, revoked too, id → name, by seq. */
    fun agentNames(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        db.query("SELECT id, name FROM agents ORDER BY seq") { it.getString(1) to it.getString(2) }
            .forEach { (id, name) -> put(id, JsonPrimitive(name)) }
    })

    /** `listLiveAgents` (TS `src/domain/sessions.ts`): one row per agent with a fresh open session. */
    fun liveAgents(at: Long = clock()): List<JsonObject> {
        val sessions = db.query(
            "SELECT seq, id, agent_id, started_at, last_heartbeat_at, ended_at, meta FROM sessions " +
                "WHERE ended_at IS NULL AND last_heartbeat_at >= ? ORDER BY last_heartbeat_at DESC",
            at - PRESENCE_WINDOW_MS,
        ) { rs ->
            listOf(rs.getString("id"), rs.getString("agent_id"), rs.getLong("started_at"), rs.getLong("last_heartbeat_at"), rs.getString("meta"))
        }
        val byAgent = LinkedHashMap<String, MutableMap<String, JsonElement>>()
        for ((id, agentId, startedAt, beat, meta) in sessions) {
            agentId as String
            byAgent[agentId]?.let { known ->
                known["sessions"] = JsonPrimitive((known["sessions"] as JsonPrimitive).content.toLong() + 1)
                continue
            }
            val name = db.one("SELECT name FROM agents WHERE id = ?", agentId) { it.getString(1) } ?: continue
            val parsed = (meta as String?)?.let { Js.parse(it) } as? JsonObject
            fun metaField(field: String): JsonElement =
                parsed?.get(field).jsString?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull
            byAgent[agentId] = linkedMapOf(
                "agentId" to JsonPrimitive(agentId),
                "name" to JsonPrimitive(name),
                "sessionId" to JsonPrimitive(id as String),
                "startedAt" to JsonPrimitive(startedAt as Long),
                "lastHeartbeatAt" to JsonPrimitive(beat as Long),
                "sessions" to JsonPrimitive(1),
                "host" to metaField("host"),
                "cwd" to metaField("cwd"),
                "model" to metaField("model"),
            )
        }
        return byAgent.values.map { JsonObject(it) }
    }

    // ---- requests -------------------------------------------------------------------------------

    /** `listPendingRequests`: the oldest 200 pending, minus the ones already due, by seq. */
    fun pendingRequests(now: Long = clock()): List<JsonObject> = db.query(
        "SELECT ${Rows.APPROVAL} FROM approvals WHERE state = 'pending' ORDER BY seq ASC LIMIT ?",
        PENDING_REQUEST_LIMIT,
    ) { Rows.request(it) }.filter { (it["expiresAt"] as JsonPrimitive).content.toLong() > now }

    /** The banner's list: each request with its media, embeds and reactions. */
    fun requestsSnapshot(): List<JsonObject> = pendingRequests().map { request ->
        val id = request.str("id")!!
        request.plus(
            "media" to JsonArray(mediaFor("request_id", id)),
            "embeds" to JsonArray(embedsFor("request_id", id)),
            "reactions" to JsonArray(reactionsFor("request", listOf(id))),
        )
    }

    // ---- summary --------------------------------------------------------------------------------

    /** `ownerSummary` (TS `src/domain/summary.ts`), the iOS app's contract. */
    fun summary(isoOf: (Long) -> String): JsonObject {
        val now = clock()
        val active = db.query(
            "SELECT id, slug, name, pinned FROM projects WHERE status = 'active' ORDER BY pinned DESC, seq DESC",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1) }
        val counts = workCounts(active.map { it[0] as String })
        val requests = pendingRequests().map { request ->
            val project = request.str("projectId")?.takeIf { it.isNotEmpty() }?.let { id ->
                db.one("SELECT slug, name FROM projects WHERE id = ?", id) { it.getString(1) to it.getString(2) }
            }
            val agentName = db.one("SELECT name FROM agents WHERE id = ?", request.str("agentId")) { it.getString(1) } ?: "An agent"
            val options = request["options"] as? JsonArray
            buildJsonObject {
                put("id", request["id"]!!)
                put("agentName", agentName)
                put("projectSlug", project?.first)
                put("projectName", project?.second)
                put("question", request["question"]!!)
                put("detail", request["detail"]!!)
                put("default", (request["config"] as? JsonObject)?.get("default") ?: JsonNull)
                put("kind", request["kind"]!!)
                put("options", if (options != null && options.isNotEmpty()) options else JsonNull)
                put("expiresAt", isoOf((request["expiresAt"] as JsonPrimitive).content.toLong()))
            }
        }
        val sorted = active.sortedWith { a, b ->
            val pinned = (if (b[3] as Boolean) 1 else 0) - (if (a[3] as Boolean) 1 else 0)
            if (pinned != 0) pinned else LocaleCompare.compare(a[2] as String, b[2] as String)
        }
        return buildJsonObject {
            put("at", isoOf(now))
            put("onlineAgents", JsonArray(liveAgents(now).map { agent ->
                buildJsonObject {
                    put("agentId", agent["agentId"]!!)
                    put("name", agent["name"]!!)
                    put("host", agent["host"]!!)
                    put("cwd", agent["cwd"]!!)
                }
            }))
            put("requests", JsonArray(requests))
            put("work", buildJsonObject {
                put("todo", counts["todo"] ?: 0)
                put("inProgress", counts["in_progress"] ?: 0)
                put("blocked", counts["blocked"] ?: 0)
            })
            put("projects", JsonArray(sorted.map { p -> buildJsonObject { put("slug", p[1] as String); put("name", p[2] as String) } }))
        }
    }

    private fun workCounts(projectIds: List<String>): Map<String, Long> {
        if (projectIds.isEmpty()) return emptyMap()
        val scope = marks(projectIds)
        return db.query(
            "SELECT status, COUNT(*) AS total FROM (SELECT status FROM updates WHERE status IS NOT NULL AND deleted_at IS NULL " +
                "AND project_id IN ($scope) UNION ALL SELECT status FROM messages WHERE status IS NOT NULL AND deleted_at IS NULL " +
                "AND project_id IN ($scope)) GROUP BY status",
            *(projectIds + projectIds).toTypedArray(),
        ) { it.getString(1) to it.getLong(2) }.toMap()
    }

    // ---- notifications and push -----------------------------------------------------------------

    /** The bell, newest first, each with its project and the path it opens. */
    fun notifications(unseenOnly: Boolean, limit: Int): List<JsonObject> = db.query(
        "SELECT ${Rows.NOTIFICATION} FROM notifications WHERE (? = 0 OR seen_at IS NULL) ORDER BY seq DESC LIMIT ?",
        if (unseenOnly) 1 else 0, limit,
    ) { Rows.notification(it) }.map { n ->
        val project = n.str("projectId")?.takeIf { it.isNotEmpty() }?.let { id ->
            db.one("SELECT slug, name FROM projects WHERE id = ?", id) { it.getString(1) to it.getString(2) }
        }
        val slug = project?.first
        val focus = n.str("updateId") ?: n.str("messageId") ?: n.str("requestId")
        val base = if (!slug.isNullOrEmpty()) "/projects/$slug" else "/"
        val path = if (!focus.isNullOrEmpty()) "$base?focus=${Js.encodeURIComponent(focus)}" else base
        n.plus(
            "projectSlug" to (slug?.let(::JsonPrimitive) ?: JsonNull),
            "projectName" to (project?.second?.let(::JsonPrimitive) ?: JsonNull),
            "path" to JsonPrimitive(path),
        )
    }

    fun unseenNotifications(): Long = db.one("SELECT count(*) AS n FROM notifications WHERE seen_at IS NULL") { it.getLong(1) } ?: 0

    /** `GET /api/push`'s device list, by `created_at`; null prefs read as the default. */
    fun pushDevices(): List<JsonObject> = db.query(
        "SELECT seq, id, endpoint, p256dh, auth, label, created_at, last_sent_at, failures, prefs FROM push_subscriptions ORDER BY created_at",
    ) { rs ->
        val prefs = Js.parseOrNull(rs.getString("prefs"))
        buildJsonObject {
            put("endpoint", rs.getString("endpoint"))
            put("label", rs.getString("label"))
            put("last_sent_at", rs.longOrNull("last_sent_at"))
            put("prefs", if (prefs == null || prefs is JsonNull) DEFAULT_PUSH_PREFS else prefs)
        }
    }

    companion object {
        val ID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
        const val UPDATES_MAX = 200
        const val MESSAGES_MAX = 200
        const val PENDING_REQUEST_LIMIT = 200
        const val PRESENCE_WINDOW_MS = 90_000L
        val RENDERABLE_MIMES = setOf(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "video/mp4", "video/webm", "video/quicktime",
            "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav",
        )
        val DEFAULT_PUSH_PREFS = buildJsonObject {
            put("types", JsonArray(listOf("request", "update", "message", "comment").map(::JsonPrimitive)))
        }

        private fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }

        private fun anchor(kind: String) = when (kind) {
            "update" -> "update_id"
            "message" -> "message_id"
            "request" -> "request_id"
            else -> error("no such card kind: $kind")
        }
    }
}
