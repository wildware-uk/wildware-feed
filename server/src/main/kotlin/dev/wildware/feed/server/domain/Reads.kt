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
import dev.wildware.feed.server.http.Wire
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

    private val access get() = ChannelAccess(db, clock)

    /** A channel this work's viewer can't read answers as missing (spec/15). */
    private fun projectRef(sql: String, value: String) =
        db.one("SELECT id, slug, name FROM projects WHERE team_id = :team AND $sql = ?", value) { ProjectRef(it.getString(1), it.getString(2), it.getString(3)) }
            ?.takeIf { access.visible(it.id) }

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

    fun projectSlugsById(): Map<String, String> = db.query("SELECT id, slug FROM projects WHERE team_id = :team") { it.getString(1) to it.getString(2) }.toMap()

    fun listProjects(status: String? = null): List<JsonObject> = db.query(
        "SELECT ${Rows.PROJECT} FROM projects WHERE team_id = :team AND (? IS NULL OR status = ?)${access.notHidden("id")} ORDER BY ${Rows.PROJECT_ORDER}",
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
            "SELECT ${Rows.UPDATE} FROM updates WHERE team_id = :team AND (? IS NULL OR project_id = ?) AND (? IS NULL OR seq < ?) " +
                "AND deleted_at IS NULL${access.notHidden("project_id")} ORDER BY seq DESC LIMIT ?",
            projectId, projectId, beforeSeq, beforeSeq, pageLimit + 1,
        ) { Rows.update(it) }
        val hasMore = rows.size > pageLimit
        val updates = if (hasMore) rows.take(pageLimit) else rows
        val ids = updates.map { it.str("id")!! }
        val reactions = reactionsFor("update", ids)
        val media = mediaForMany("update_id", ids)
        val embeds = embedsForMany("update_id", ids)
        val refs = refsFor("update", ids)
        val blocks = blocksFor("update", ids)
        val secrets = secretsFor("update", ids)
        val items = updates.zip(ids).map { (update, id) ->
            val extra = mutableListOf<Pair<String, JsonElement>>(
                "media" to JsonArray(media[id].orEmpty()),
                "embeds" to JsonArray(embeds[id].orEmpty()),
                "reactions" to JsonArray(reactions.filter { it["card"].str("id") == id }),
                // Migration 056: the cards this one points at, after everything the TS server sent.
                "refs" to JsonArray(refs[id].orEmpty()),
                // Migration 057: the blocks drawn on it, last.
                "blocks" to JsonArray(blocks[id].orEmpty()),
                // Migration 066: secrets the agent attached, by name, and whether each can still be opened.
                "secrets" to JsonArray(secrets[id].orEmpty()),
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
                "SELECT ${Rows.MESSAGE} FROM messages WHERE team_id = :team AND reply_to = ? AND deleted_at IS NULL${access.notHidden("project_id")} ORDER BY seq ASC",
                post.str("id"),
            ) { Rows.message(it) }
            // `slice(-(limit - 1))`: a limit of 1 is `slice(-0)`, which is all of them.
            val kept = if (limit == 1) replies else replies.takeLast(limit - 1)
            return listOf(post) + kept
        }
        val projectId = query.project?.let { resolveProject(it).id }
        val window = db.query(
            "SELECT ${Rows.MESSAGE} FROM messages WHERE team_id = :team AND (? IS NULL OR project_id = ?) AND (? IS NULL OR update_id = ?) " +
                "AND (? IS NULL OR task_id = ?) AND (? IS NULL OR request_id = ?) AND deleted_at IS NULL${access.notHidden("project_id")} ORDER BY seq DESC LIMIT ?",
            projectId, projectId, query.updateId, query.updateId, query.taskId, query.taskId, query.requestId, query.requestId, limit,
        ) { Rows.message(it) }.reversed()
        return withParents(window)
    }

    private fun messageById(id: String): JsonObject? =
        db.one("SELECT ${Rows.MESSAGE} FROM messages WHERE team_id = :team AND id = ?", id) { Rows.message(it) }?.takeIf { access.visible(it.str("projectId")) }

    private fun withParents(window: List<JsonObject>): List<JsonObject> {
        val present = window.map { it.str("id") }.toSet()
        val wanted = LinkedHashSet<String>()
        for (m in window) m.str("replyTo")?.takeIf { it.isNotEmpty() && it !in present }?.let(wanted::add)
        if (wanted.isEmpty()) return window
        val byId = inChunks(wanted.toList()) { chunk ->
            db.query("SELECT ${Rows.MESSAGE} FROM messages WHERE team_id = :team AND id IN (${marks(chunk)})${access.notHidden("project_id")}", *chunk.toTypedArray()) { Rows.message(it) }
        }.associateBy { it.str("id") }
        val heads = wanted.mapNotNull { byId[it] }.filter { it["deletedAt"] is JsonNull }
        return (heads + window).sortedBy { it["seq"]!!.toString().toLong() }
    }

    fun acksForMessages(ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.ACK} FROM acknowledgements WHERE team_id = :team AND message_id IN (${marks(ids)}) ORDER BY seq ASC",
            *ids.toTypedArray(),
        ) { Rows.ack(it) }
    }

    fun deliveriesFor(ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.DELIVERY} FROM message_deliveries WHERE team_id = :team AND seq IN (SELECT min(seq) FROM message_deliveries " +
                "WHERE team_id = :team AND message_id IN (${marks(ids)}) GROUP BY message_id, agent_id) ORDER BY seq",
            *ids.toTypedArray(),
        ) { Rows.delivery(it) }
    }

    fun reactionsFor(kind: String, ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        return db.query(
            "SELECT ${Rows.REACTION} FROM reactions WHERE team_id = :team AND ${anchor(kind)} IN (${marks(ids)}) ORDER BY seq",
            *ids.toTypedArray(),
        ) { Rows.reaction(it) }
    }

    /** `{messageId: [...]}` for the messages that have some, in message order. */
    fun byMessage(ids: List<String>, read: (String) -> List<JsonObject>): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply {
            for (id in ids) read(id).takeIf { it.isNotEmpty() }?.let { put(id, JsonArray(it)) }
        })

    /** The blocks on each of [ids] (migration 057), by card id. */
    fun blocksFor(kind: String, ids: List<String>): Map<String, List<JsonObject>> = BlockReads(db, clock).views(kind, ids)

    /** The secrets attached to each of [ids] (migration 066), by card id: names only, never values. */
    fun secretsFor(kind: String, ids: List<String>): Map<String, List<JsonObject>> =
        CardSecrets(db, clock).on(kind, ids).mapValues { (_, shared) -> shared.map(CardSecrets::json) }

    /** The summaries of the cards each of [ids] points at (migration 056), by card id. */
    fun refsFor(kind: String, ids: List<String>): Map<String, List<JsonObject>> = CardRefs(db, clock).summaries(kind, ids)

    // ---- media and embeds -----------------------------------------------------------------------

    /** `MediaAttachment` rows on one card, by seq (TS `src/domain/media.ts`). */
    fun mediaFor(anchorColumn: String, cardId: String): List<JsonObject> = mediaForMany(anchorColumn, listOf(cardId))[cardId].orEmpty()

    /** [mediaFor] for many cards at once: two queries, whatever the count. Cards without media are left out. */
    fun mediaForMany(anchorColumn: String, cardIds: List<String>): Map<String, List<JsonObject>> {
        if (cardIds.isEmpty()) return emptyMap()
        val rows = inChunks(cardIds.distinct()) { chunk ->
            db.query("SELECT ${Rows.MEDIA}, removed_at, removed_after_days FROM media WHERE team_id = :team AND $anchorColumn IN (${marks(chunk)})", *chunk.toTypedArray()) { rs ->
                rs.getLong("seq") to MediaRow(
                    removedAt = rs.longOrNull("removed_at"),
                    removedAfterDays = rs.longOrNull("removed_after_days"),
                    card = rs.getString(anchorColumn),
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
        }.sortedBy { it.first }.map { it.second }
        val derivatives = derivativesOf(rows.filter { it.removedAt == null }.map { it.id })
        val out = LinkedHashMap<String, MutableList<JsonObject>>()
        for (row in rows) {
            val removedAt = row.removedAt
            out.getOrPut(row.card) { mutableListOf() } += if (removedAt == null) {
                row.json.plus("variants" to JsonArray(variantsOf(row, derivatives[row.id].orEmpty()).map(::JsonPrimitive)))
            } else {
                // Removed for its age (migration 048): no addresses, and a note the apps show instead.
                row.json.plus("variants" to JsonArray(emptyList())).plus(
                    "removed" to buildJsonObject { put("at", Wire.isoOf(removedAt)); put("afterDays", row.removedAfterDays) },
                )
            }
        }
        return out
    }

    /** Each media's derivatives as (kind, width), in `kind, width, seq` order. */
    private fun derivativesOf(mediaIds: List<String>): Map<String, List<Pair<String, Long?>>> =
        inChunks(mediaIds) { chunk ->
            db.query(
                "SELECT media_id, kind, width, seq FROM derivatives WHERE team_id = :team AND media_id IN (${marks(chunk)})",
                *chunk.toTypedArray(),
            ) { Triple(it.getString(1), it.getString(2) to it.longOrNull("width"), it.getLong(4)) }
        }.sortedWith(compareBy({ it.second.first }, { it.second.second ?: Long.MIN_VALUE }, { it.third }))
            .groupBy({ it.first }, { it.second })

    private class MediaRow(
        val id: String, val kind: String, val mime: String, val status: String, val sha256: String, val json: JsonObject, val card: String,
        val removedAt: Long? = null, val removedAfterDays: Long? = null,
    )

    /** `derivativesFor` (TS `src/media/serve.ts`): the addresses that answer right now, from rows alone. */
    private fun variantsOf(media: MediaRow, derivatives: List<Pair<String, Long?>>): List<String> {
        if (media.status == "failed") return emptyList()
        val download = media.kind == "file"
        if (!download && media.mime !in RENDERABLE_MIMES) return emptyList()
        if (download) return if (media.sha256.isEmpty()) emptyList() else listOf("original")
        val variants = mutableListOf<String>()
        if (media.sha256.isNotEmpty()) variants += "original"
        derivatives.forEach { (kind, width) ->
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
    fun embedsFor(anchorColumn: String, cardId: String): List<JsonObject> = embedsForMany(anchorColumn, listOf(cardId))[cardId].orEmpty()

    /** [embedsFor] for many cards at once: two queries, whatever the count. */
    fun embedsForMany(anchorColumn: String, cardIds: List<String>): Map<String, List<JsonObject>> {
        if (cardIds.isEmpty()) return emptyMap()
        val rows = inChunks(cardIds.distinct()) { chunk ->
            db.query("SELECT ${Rows.EMBED} FROM embeds WHERE team_id = :team AND $anchorColumn IN (${marks(chunk)})", *chunk.toTypedArray()) { rs ->
                rs.getLong("seq") to EmbedRow(
                    rs.getString("id"), rs.getString("type_id"), rs.getString("update_id"), rs.getString("message_id"),
                    rs.getString("request_id"), rs.getString("task_id"), rs.getString("data"), rs.getString("source_url"),
                    rs.getLong("created_at"), rs.getString(anchorColumn),
                )
            }
        }.sortedBy { it.first }.map { it.second }
        val typeIds = rows.map { it.typeId }.distinct()
        val types = inChunks(typeIds) { chunk ->
            db.query("SELECT id, slug, name, spec FROM embed_types WHERE team_id = :team AND id IN (${marks(chunk)})", *chunk.toTypedArray()) {
                it.getString(1) to Triple(it.getString(2), it.getString(3), it.getString(4))
            }
        }.toMap()
        val out = LinkedHashMap<String, MutableList<JsonObject>>()
        for (row in rows) {
            val type = types[row.typeId] ?: continue
            val spec = Embeds.specOf(type.third) ?: continue
            out.getOrPut(row.card) { mutableListOf() } += buildJsonObject {
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
        return out
    }

    private class EmbedRow(
        val id: String, val typeId: String, val updateId: String?, val messageId: String?, val requestId: String?,
        val taskId: String?, val data: String, val sourceUrl: String?, val createdAt: Long, val card: String,
    )

    // ---- tags, fields, apps ---------------------------------------------------------------------

    /** `listTags`: one project's (a truthy reference is resolved) or every project's, by seq. */
    fun listTags(project: String?): List<JsonObject> {
        if (project.isNullOrEmpty()) return db.query("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team${access.notHidden("project_id")} ORDER BY seq") { Rows.tag(it) }
        val id = resolveProject(project).id
        return db.query("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team AND project_id IN (?) ORDER BY seq", id) { Rows.tag(it) }
    }

    private fun <T> perKind(scope: CardScope, read: (anchor: String, ids: List<String>) -> List<T>): List<T> =
        listOf("update_id" to scope.update, "message_id" to scope.message, "request_id" to scope.request)
            .filter { it.second.isNotEmpty() }
            .flatMap { (anchor, ids) -> read(anchor, ids) }

    fun cardTags(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.CARD_TAG} FROM card_tags WHERE team_id = :team AND $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { Rows.cardTag(it) }
    }

    fun cardFields(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.FIELD} FROM card_fields WHERE team_id = :team AND $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { Rows.field(it) }
    }

    /** AppView (TS `src/domain/apps.ts` `viewData`); a view whose app is gone is dropped. */
    fun cardApps(scope: CardScope): List<JsonObject> = perKind(scope) { anchor, ids ->
        db.query("SELECT ${Rows.VIEW} FROM app_views WHERE team_id = :team AND $anchor IN (${marks(ids)}) ORDER BY seq", *ids.toTypedArray()) { rs ->
            ViewRow(
                rs.getString("id"), Rows.cardUpdateFirst(rs), rs.getString("app_id"), rs.getString("title"), rs.getString("data"),
                rs.getString("state"), rs.getString("author"), rs.getString("agent_id"), rs.getLong("created_at"), rs.getLong("updated_at"),
            )
        }
    }.let { views ->
        val apps = inChunks(views.map { it.appId }.distinct()) { chunk ->
            db.query("SELECT id, uri, name, description, url FROM apps WHERE team_id = :team AND id IN (${marks(chunk)})", *chunk.toTypedArray()) {
                it.getString(1) to listOf(it.getString(2), it.getString(3), it.getString(4), it.getString(5))
            }
        }.toMap()
        views.map { it to apps[it.appId] }
    }.mapNotNull { (view, app) ->
        app ?: return@mapNotNull null
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

    /** `{}` too for a value stored before the depth limit that nests past it, so old rows can't stop the apps loading. */
    private fun jsonObjectOrEmpty(text: String): JsonObject =
        runCatching { Js.parse(text) as? JsonObject }.getOrNull()?.takeUnless(FeedAttachments::tooDeep) ?: Rows.empty

    // ---- agents ---------------------------------------------------------------------------------

    /** Every agent, revoked too, id → name, by seq. */
    fun agentNames(): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply {
        db.query("SELECT id, name FROM agents WHERE team_id = :team ORDER BY seq") { it.getString(1) to it.getString(2) }
            .forEach { (id, name) -> put(id, JsonPrimitive(name)) }
    })

    /** `listLiveAgents` (TS `src/domain/sessions.ts`): one row per agent with a fresh open session. */
    fun liveAgents(at: Long = clock()): List<JsonObject> {
        val sessions = db.query(
            "SELECT seq, id, agent_id, started_at, last_heartbeat_at, ended_at, meta FROM sessions " +
                "WHERE team_id = :team AND ended_at IS NULL AND last_heartbeat_at >= ? ORDER BY last_heartbeat_at DESC",
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
            val name = db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", agentId) { it.getString(1) } ?: continue
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
        "SELECT ${Rows.APPROVAL} FROM approvals WHERE team_id = :team AND state = 'pending'${access.notHidden("project_id")} ORDER BY seq ASC LIMIT ?",
        PENDING_REQUEST_LIMIT,
    ) { Rows.request(it) }.filter { (it["expiresAt"] as JsonPrimitive).content.toLong() > now }

    /**
     * Questions answered in the last [ANSWERED_WINDOW_MS], newest answer first, at most
     * [ANSWERED_LIMIT]: on a team the apps keep them in the feed, saying who answered (`answeredBy`).
     */
    fun answeredRequests(now: Long = clock()): List<JsonObject> = withCards(
        db.query(
            "SELECT ${Rows.APPROVAL} FROM approvals WHERE team_id = :team AND state IN ('approved', 'rejected') AND decided_at >= ?${access.notHidden("project_id")} ORDER BY decided_at DESC, seq DESC LIMIT ?",
            now - ANSWERED_WINDOW_MS, ANSWERED_LIMIT,
        ) { Rows.request(it) },
    )

    /** The banner's list: each request with its media, embeds and reactions. */
    fun requestsSnapshot(): List<JsonObject> = withCards(pendingRequests())

    /** Each request with its media, embeds and reactions. */
    private fun withCards(requests: List<JsonObject>): List<JsonObject> {
        val ids = requests.map { it.str("id")!! }
        val media = mediaForMany("request_id", ids)
        val embeds = embedsForMany("request_id", ids)
        val reactions = reactionsFor("request", ids)
        val refs = refsFor("request", ids)
        val blocks = blocksFor("request", ids)
        return requests.zip(ids).map { (request, id) ->
            request.plus(
                "media" to JsonArray(media[id].orEmpty()),
                "embeds" to JsonArray(embeds[id].orEmpty()),
                "reactions" to JsonArray(reactions.filter { it["card"].str("id") == id }),
                "refs" to JsonArray(refs[id].orEmpty()),
                "blocks" to JsonArray(blocks[id].orEmpty()),
            )
        }
    }

    // ---- whole pages ----------------------------------------------------------------------------

    data class SnapshotRequest(val project: String?, val status: String?, val limit: Int, val cursor: String?)

    /** `GET /api/snapshot`'s body after `seq` and `at`: a fixed number of queries, whatever its size. */
    fun snapshot(query: SnapshotRequest): List<Pair<String, JsonElement>> {
        val projects = listProjects(query.status)
        val messages = listThread(ThreadQuery(project = query.project))
        val updates = updatesPage(query.project, query.limit, query.cursor)
        val messageIds = messages.map { (it["id"] as JsonPrimitive).content }
        val scope = CardScope(update = updates.ids, message = messageIds, request = pendingRequests().map { (it["id"] as JsonPrimitive).content })
        val cardTags = cardTags(scope)
        val cardFields = cardFields(scope)
        val cardApps = cardApps(scope)
        val media = mediaForMany("message_id", messageIds)
        val embeds = embedsForMany("message_id", messageIds)
        val refs = refsFor("message", messageIds)
        val blocks = blocksFor("message", messageIds)
        val secrets = secretsFor("message", messageIds)
        return listOf(
            "projects" to JsonArray(projects),
            "groups" to JsonArray(db.query("SELECT ${Groups.COLUMNS} FROM project_groups WHERE team_id = :team${access.groupNotHidden("id")} ORDER BY ${Groups.ORDER}") { Groups.json(it) }),
            "tags" to JsonArray(listTags(query.project)),
            "cardTags" to JsonArray(cardTags),
            "cardFields" to JsonArray(cardFields),
            "cardApps" to JsonArray(cardApps),
            "updates" to updates.json,
            "agentNames" to agentNames(),
            "messages" to JsonArray(messages),
            "acks" to JsonArray(acksForMessages(messageIds)),
            "messageMedia" to byMessage(messageIds) { media[it].orEmpty() },
            "messageEmbeds" to byMessage(messageIds) { embeds[it].orEmpty() },
            "messageRefs" to byMessage(messageIds) { refs[it].orEmpty() },
            "messageBlocks" to byMessage(messageIds) { blocks[it].orEmpty() },
            "messageSecrets" to byMessage(messageIds) { secrets[it].orEmpty() },
        )
    }

    /** `GET /api/messages`'s body after `seq` and `at`. */
    fun messagesPage(query: ThreadQuery): List<Pair<String, JsonElement>> {
        val messages = listThread(query)
        val ids = messages.map { (it["id"] as JsonPrimitive).content }
        val media = mediaForMany("message_id", ids)
        val embeds = embedsForMany("message_id", ids)
        val refs = refsFor("message", ids)
        val blocks = blocksFor("message", ids)
        val secrets = secretsFor("message", ids)
        return listOf(
            "messages" to JsonArray(messages),
            "acks" to JsonArray(acksForMessages(ids)),
            "media" to byMessage(ids) { media[it].orEmpty() },
            "embeds" to byMessage(ids) { embeds[it].orEmpty() },
            "deliveries" to JsonArray(deliveriesFor(ids)),
            "reactions" to JsonArray(reactionsFor("message", ids)),
            "refs" to byMessage(ids) { refs[it].orEmpty() },
            "blocks" to byMessage(ids) { blocks[it].orEmpty() },
            "secrets" to byMessage(ids) { secrets[it].orEmpty() },
        )
    }

    // ---- summary --------------------------------------------------------------------------------

    /** `ownerSummary` (TS `src/domain/summary.ts`), the iOS app's contract. */
    fun summary(isoOf: (Long) -> String): JsonObject {
        val now = clock()
        val active = db.query(
            "SELECT id, slug, name, pinned FROM projects WHERE team_id = :team AND status = 'active'${access.notHidden("id")} ORDER BY ${Rows.PROJECT_ORDER}",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1) }
        val counts = workCounts(active.map { it[0] as String })
        val requests = pendingRequests().map { request ->
            val project = request.str("projectId")?.takeIf { it.isNotEmpty() }?.let { id ->
                db.one("SELECT slug, name FROM projects WHERE team_id = :team AND id = ?", id) { it.getString(1) to it.getString(2) }
            }
            val agentName = db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", request.str("agentId")) { it.getString(1) } ?: "An agent"
            val options = request["options"] as? JsonArray
            buildJsonObject {
                put("id", request["id"]!!)
                put("agentId", request["agentId"]!!)
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
            put("working", JsonArray(workingCards(active.map { it[0] as String })))
        }
    }

    /**
     * Up to three cards in progress in active projects, most recently started first: who, doing
     * what, since when (TS `workingCards`, `src/domain/summary.ts`). `since` is fixed, so the Live
     * Activity's state only changes when something real does.
     */
    private fun workingCards(projectIds: List<String>): List<JsonObject> {
        if (projectIds.isEmpty()) return emptyList()
        val scope = marks(projectIds)
        class Row(val kind: String, val id: String, val statusAt: Long?, val createdAt: Long, val title: String?, val body: String, val author: String?)
        val rows = db.query(
            "SELECT 'update' AS kind, id, project_id, status, status_at, created_at, title, body, 'agent:' || agent_id AS author, " +
                "COALESCE(status_at, created_at) AS moved_at FROM updates WHERE team_id = :team AND status IN (?) AND deleted_at IS NULL AND project_id IN ($scope) " +
                "UNION ALL SELECT 'message' AS kind, id, project_id, status, status_at, created_at, NULL AS title, body, author, " +
                "COALESCE(status_at, created_at) AS moved_at FROM messages WHERE team_id = :team AND status IN (?) AND deleted_at IS NULL AND project_id IN ($scope) " +
                "ORDER BY moved_at DESC, id DESC LIMIT ?",
            "in_progress", *projectIds.toTypedArray(), "in_progress", *projectIds.toTypedArray(), WORKING_MAX,
        ) { rs ->
            Row(rs.getString("kind"), rs.getString("id"), rs.longOrNull("status_at"), rs.getLong("created_at"), rs.getString("title"), rs.getString("body"), rs.getString("author"))
        }
        fun agentOf(author: String?) = author?.takeIf { it.startsWith("agent:") }?.removePrefix("agent:")
        return rows.mapNotNull { row -> oneCardOrNull(row.id) {
            var agentId = agentOf(row.author)
            if (agentId == null && row.kind == "message") {
                agentId = db.query(
                    "SELECT author FROM messages WHERE team_id = :team AND reply_to = ? AND deleted_at IS NULL ORDER BY seq ASC", row.id,
                ) { it.getString(1) }.asReversed().firstNotNullOfOrNull(::agentOf)
            }
            val name = agentId?.let { id -> db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", id) { it.getString(1) } } ?: "An agent"
            val title = if (row.kind == "update" && row.title != null && row.title.jsTrim().isNotEmpty()) firstLine(row.title) else ""
            buildJsonObject {
                put("agentId", agentId)
                put("agent", cut(name, NAME_MAX))
                put("task", cut(title.ifEmpty { firstLine(row.body) }, TASK_MAX))
                put("since", Math.floorDiv(row.statusAt ?: row.createdAt, 1000L))
            }
        } }
    }

    /** One card's part of the summary, or null with the failure logged: one bad card never breaks the page. */
    private inline fun <T> oneCardOrNull(id: String, part: () -> T): T? = try {
        part()
    } catch (e: Exception) {
        System.err.println("summary: card $id left out: $e")
        null
    } catch (e: StackOverflowError) {
        System.err.println("summary: card $id left out: nested too deep")
        null
    }

    private fun workCounts(projectIds: List<String>): Map<String, Long> {
        if (projectIds.isEmpty()) return emptyMap()
        val scope = marks(projectIds)
        return db.query(
            "SELECT status, COUNT(*) AS total FROM (SELECT status FROM updates WHERE team_id = :team AND status IS NOT NULL AND deleted_at IS NULL " +
                "AND project_id IN ($scope) UNION ALL SELECT status FROM messages WHERE team_id = :team AND status IS NOT NULL AND deleted_at IS NULL " +
                "AND project_id IN ($scope)) GROUP BY status",
            *(projectIds + projectIds).toTypedArray(),
        ) { it.getString(1) to it.getLong(2) }.toMap()
    }

    // ---- notifications and push -----------------------------------------------------------------

    /** [member]'s bell (migration 055), newest first, each with its project and the path it opens. */
    fun notifications(unseenOnly: Boolean, limit: Int, member: String = People.FOUNDER): List<JsonObject> = db.query(
        "SELECT ${Rows.NOTIFICATION} FROM notifications WHERE team_id = :team AND member_id = ? AND (? = 0 OR seen_at IS NULL)${access.notHidden("project_id")} ORDER BY seq DESC LIMIT ?",
        member, if (unseenOnly) 1 else 0, limit,
    ) { Rows.notification(it) }.map { n ->
        val project = n.str("projectId")?.takeIf { it.isNotEmpty() }?.let { id ->
            db.one("SELECT slug, name FROM projects WHERE team_id = :team AND id = ?", id) { it.getString(1) to it.getString(2) }
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

    fun unseenNotifications(member: String = People.FOUNDER): Long =
        db.one("SELECT count(*) AS n FROM notifications WHERE team_id = :team AND member_id = ? AND seen_at IS NULL${access.notHidden("project_id")}", member) { it.getLong(1) } ?: 0

    companion object {
        const val WORKING_MAX = 3
        const val NAME_MAX = 40
        const val TASK_MAX = 60
        private val JS_SPACE = Regex("[\\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]+")

        /** [text] in at most [max] UTF-16 units: whole, or cut, trimmed and ended with an ellipsis; never splits a pair. */
        fun cut(text: String, max: Int): String {
            if (text.length <= max) return text
            var end = max - 1
            if (end >= 1 && text[end - 1].isHighSurrogate()) end -= 1
            return text.substring(0, end).jsTrimEnd() + "\u2026"
        }

        /** The first line of some markdown, as plain text with its whitespace collapsed. */
        fun firstLine(markdown: String): String =
            dev.wildware.feed.server.push.PlainText.of(markdown).split('\n')[0].replace(JS_SPACE, " ").jsTrim()

        /** JS `trimEnd`: drop trailing JS whitespace and line terminators. */
        private fun String.jsTrimEnd(): String {
            var e = length
            while (e > 0 && JS_SPACE.matches(this[e - 1].toString())) e--
            return substring(0, e)
        }

        val ID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
        const val UPDATES_MAX = 200
        const val MESSAGES_MAX = 200
        const val PENDING_REQUEST_LIMIT = 200
        const val ANSWERED_LIMIT = 50
        const val ANSWERED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val PRESENCE_WINDOW_MS = Sessions.PRESENCE_WINDOW_MS
        val RENDERABLE_MIMES = setOf(
            "image/png", "image/jpeg", "image/webp", "image/gif",
            "video/mp4", "video/webm", "video/quicktime",
            "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav",
        )

        private fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }

        /** Runs [read] over [ids] in slices small enough for any engine's parameter limit. */
        private fun <T> inChunks(ids: List<String>, read: (List<String>) -> List<T>): List<T> =
            if (ids.isEmpty()) emptyList() else ids.chunked(500).flatMap(read)

        private fun anchor(kind: String) = when (kind) {
            "update" -> "update_id"
            "message" -> "message_id"
            "request" -> "request_id"
            else -> error("no such card kind: $kind")
        }
    }
}
