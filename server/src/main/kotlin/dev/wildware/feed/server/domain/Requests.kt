package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.events.Ring
import dev.wildware.feed.server.http.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One owner request, as `toRequest` (TS `src/domain/requests.ts:892`) shapes it. [json] is the wire
 * form, in its key order (spec/03 §1.7); the properties read it back.
 */
class OwnerRequest(val json: JsonObject) {
    private fun s(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun l(key: String) = s(key)?.toLong()

    val id get() = s("id")!!
    val agentId get() = s("agentId")!!
    val projectId get() = s("projectId")
    val updateId get() = s("updateId")
    val messageId get() = s("messageId")
    val kind get() = s("kind")!!
    val question get() = s("question")!!
    val detail get() = s("detail")
    val options: List<String>? get() = (json["options"] as? JsonArray)?.map { (it as JsonPrimitive).content }
    val config: JsonObject? get() = json["config"] as? JsonObject
    val state get() = s("state")!!
    val expiresAt get() = l("expiresAt")!!
    val answeredAt get() = l("answeredAt")
    val answer: JsonObject? get() = json["answer"] as? JsonObject
    val editedAt get() = l("editedAt")
    /** Reads leave out "medium", so a request with none is medium. */
    val priority get() = s("priority") ?: "medium"
}

/** What a wait hands back, whichever tool called it (TS `RequestResult`). */
data class RequestResult(val state: String, val request: OwnerRequest, val response: JsonObject? = null, val answeredAt: Long = 0)

/** What `request_input` asks for, after the MCP schema has checked the types. */
data class NewRequest(
    val kind: String,
    val question: String,
    val detail: String? = null,
    val options: List<String>? = null,
    val placeholder: String? = null,
    val multiline: Boolean? = null,
    val label: String? = null,
    val default: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val project: String? = null,
    val update: String? = null,
    val message: String? = null,
    val timeoutS: Double? = null,
    /** How much it needs the owner now (migration 049); `critical` pages their phones. */
    val priority: String? = null,
    val mediaIds: List<String>? = null,
    /** In the order JavaScript would walk the object (integer-like keys first). */
    val optionMedia: List<Pair<String, String>>? = null,
    val embeds: List<NewEmbed>? = null,
)

data class NewEmbed(val type: String, val data: JsonElement?, val sourceUrl: String?)

/** An `edit_request`. A `*Given` flag false means the field was left out; a null value clears it. */
data class RequestEdit(
    val question: String? = null,
    val detailGiven: Boolean = false,
    val detail: String? = null,
    val options: List<String>? = null,
    val defaultGiven: Boolean = false,
    val default: String? = null,
)

/**
 * Owner requests (spec/03-owner-api-requests-devices.md §1-2, spec/05-mcp.md §4, §7.28-7.31): an
 * agent asks, waits a bounded while, and resumes by id. The row is the state; the bus only wakes.
 *
 * Never call into this while holding the database lock: it publishes, and bus listeners (the
 * notification recorder) take the database lock under the bus lock.
 */
class Requests(
    private val db: Database,
    private val bus: Bus,
    private val reads: Reads,
    private val clock: () -> Long = System::currentTimeMillis,
    private val holdMs: () -> Long = { DEFAULT_HOLD_MS },
) {
    // ---- asking and waiting ---------------------------------------------------------------------

    suspend fun requestInput(agentId: String, input: NewRequest): RequestResult {
        val (request, seq) = create(agentId, input)
        return hold(request, seq)
    }

    /** Resume a wait by id. The bus seq is read before the row, so an answer in between still counts. */
    suspend fun awaitRequest(requestId: String, agentId: String): RequestResult {
        val seq = bus.lastSeq
        val request = own(requestId, agentId)
        return hold(request, seq)
    }

    /** Write the row and announce it; returns the request and the seq of `request.created`. */
    fun create(agentId: String, input: NewRequest): Pair<OwnerRequest, Long> {
        val kind = assertKind(input.kind)
        val question = requiredText(input.question, "question", QUESTION_MAX_LENGTH)
        val detail = optionalText(input.detail, "detail", DETAIL_MAX_LENGTH)
        val options = assertOptions(kind, input.options)
        val baseConfig = assertConfig(kind, options, input)
        val anchor = anchor(input)
        val timeoutS = assertTimeout(input.timeoutS)
        val priority = input.priority ?: "medium"
        if (priority !in Updates.PRIORITIES) throw invalid("priority must be one of: ${Updates.PRIORITIES.joinToString(", ")}")

        val author = "agent:$agentId"
        val mediaIds = if (!input.mediaIds.isNullOrEmpty()) assertAttachable(input.mediaIds, author).toMutableList() else mutableListOf()
        val embeds = if (!input.embeds.isNullOrEmpty()) checkedEmbeds(input.embeds) else emptyList()
        val optionMedia = checkedOptionMedia(kind, options, input.optionMedia, author)
        if (optionMedia != null) {
            val pictures = optionMedia.map { it.second }
            if (pictures.any { it in mediaIds }) throw invalid("a picture on an option cannot also be in media_ids")
            mediaIds.addAll(pictures)
            if (mediaIds.size > MEDIA_PER_UPDATE_MAX) {
                throw invalid("a request may carry at most $MEDIA_PER_UPDATE_MAX media items, pictures included")
            }
        }
        val config: Map<String, JsonElement>? = if (optionMedia != null) {
            LinkedHashMap(baseConfig ?: emptyMap()).apply {
                put("optionMedia", JsonObject(optionMedia.associate { (option, id) -> option to JsonPrimitive(id) }))
            }
        } else baseConfig

        val now = clock()
        val id = Ulid.next(now)
        val row = db.one(
            "INSERT INTO approvals (id, agent_id, project_id, update_id, message_id, kind, question, detail, options, config, state, expires_at, priority) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?) RETURNING ${Rows.APPROVAL}",
            id, agentId, anchor.projectId, anchor.updateId, anchor.messageId, kind, question, detail,
            options?.let { Wire.encode(JsonArray(it.map(::JsonPrimitive))) }, config?.let { jsonText(JsonObject(it)) },
            now + (timeoutS * 1000).toLong(), priority,
        ) { Rows.request(it) }!!

        if (mediaIds.isNotEmpty()) {
            val marks = mediaIds.joinToString(", ") { "?" }
            db.update(
                "UPDATE media SET request_id = ? WHERE id IN ($marks) AND update_id IS NULL AND message_id IS NULL " +
                    "AND request_id IS NULL AND task_id IS NULL AND (? IS NULL OR author = ?)",
                id, *mediaIds.toTypedArray(), author, author,
            )
        }
        for (embed in embeds) {
            db.update(
                "INSERT INTO embeds (id, type_id, update_id, message_id, request_id, task_id, agent_id, author, data, source_url, created_at) " +
                    "VALUES (?, ?, NULL, NULL, ?, NULL, ?, ?, ?, ?, ?)",
                Ulid.next(clock()), embed.typeId, id, agentId, author, embed.data, embed.sourceUrl, clock(),
            )
        }

        val request = OwnerRequest(row)
        val event = bus.publish("request.created", buildJsonObject {
            put("requestId", request.id)
            put("agentId", request.agentId)
            put("projectId", request.projectId)
            put("kind", request.kind)
        })
        return request to event.seq
    }

    // ---- settling ---------------------------------------------------------------------------------

    /** The owner answers (`POST /api/requests/:id/answer`). */
    fun answer(requestId: String, value: JsonElement): OwnerRequest {
        val request = expireIfDue(mustFind(requestId))
        if (request.state != "pending") throw settledAlready(request)
        val answer = validateAnswer(request, value)
        val answerValue = answer["value"]!!
        val state = if (request.kind == "confirm" && answerValue.jsBoolean == false) "rejected" else "approved"
        val row = decide(request.id, state, scalarOf(answerValue), jsonText(answer), clock())
            ?: throw settledAlready(mustFind(request.id))
        return settle(row, "answered")
    }

    /** The owner dismisses (`DELETE /api/requests/:id`). Not idempotent: the second is a 409. */
    fun cancel(requestId: String): OwnerRequest {
        val request = expireIfDue(mustFind(requestId))
        if (request.state != "pending") throw settledAlready(request)
        val row = decide(requestId, "cancelled", null, null, clock()) ?: throw settledAlready(mustFind(requestId))
        return settle(row, "cancelled")
    }

    /** The asking agent takes its question back. */
    fun withdraw(requestId: String, agentId: String): OwnerRequest {
        val request = expireIfDue(own(requestId, agentId))
        if (request.state != "pending") throw settledAlready(request)
        val row = decide(requestId, "withdrawn", null, null, clock()) ?: throw settledAlready(mustFind(requestId))
        return settle(row, "withdrawn")
    }

    /** The asking agent rewrites its pending question (TS `editRequest`, `requests.ts:498`). */
    fun edit(requestId: String, agentId: String, input: RequestEdit): OwnerRequest {
        val request = expireIfDue(own(requestId, agentId))
        if (request.state != "pending") throw settledAlready(request)

        val question = input.question?.let { requiredText(it, "question", QUESTION_MAX_LENGTH) }
        val detail = if (input.detailGiven) optionalText(input.detail, "detail", DETAIL_MAX_LENGTH) else null
        val optionsGiven = input.options != null
        val newOptions = if (optionsGiven) assertOptions(request.kind, input.options) else null
        val options = if (optionsGiven) newOptions ?: request.options else request.options

        // config: unset (Absent), cleared (null) or a new object.
        var configGiven = false
        var config: LinkedHashMap<String, JsonElement>? = null
        val current = request.config

        if (input.defaultGiven) {
            val fallback = optionalText(input.default, "default", TEXT_ANSWER_MAX_LENGTH)
            if (fallback != null) {
                if (request.kind == "multi_choice") throw invalid("a multi_choice takes no default")
                if (request.kind == "confirm" && fallback != "true" && fallback != "false") {
                    throw invalid("the default for a confirm is \"true\" or \"false\"")
                }
                if (options != null && request.kind != "form" && fallback !in options) throw invalid("the default must be one of the options")
            }
            val merged = LinkedHashMap<String, JsonElement>(current ?: emptyMap())
            if (fallback == null) merged.remove("default") else merged["default"] = JsonPrimitive(fallback)
            configGiven = true
            config = merged.takeIf { it.isNotEmpty() }
        } else if (optionsGiven && current?.containsKey("default") == true) {
            val fallback = (current["default"] as? JsonPrimitive)?.content
            if (options != null && request.kind != "form" && fallback !in options) throw invalid("the default must be one of the options")
        }

        val pictured = current?.get("optionMedia") as? JsonObject
        if (newOptions != null && pictured != null) {
            val kept = pictured.filterKeys { it in newOptions }
            if (kept.size != pictured.size) {
                val base = LinkedHashMap<String, JsonElement>(if (!configGiven) current else config ?: emptyMap())
                if (kept.isNotEmpty()) base["optionMedia"] = JsonObject(kept) else base.remove("optionMedia")
                configGiven = true
                config = base.takeIf { it.isNotEmpty() }
            }
        }

        if (question == null && !input.detailGiven && !optionsGiven && !configGiven) {
            throw invalid("an edit must change the question, the detail, the options or the default")
        }

        val sets = mutableListOf("edited_at = ?")
        val args = mutableListOf<Any?>(clock())
        if (question != null) { sets += "question = ?"; args += question }
        if (input.detailGiven) { sets += "detail = ?"; args += detail }
        if (optionsGiven) { sets += "options = ?"; args += newOptions?.let { Wire.encode(JsonArray(it.map(::JsonPrimitive))) } }
        if (configGiven) { sets += "config = ?"; args += config?.let { jsonText(JsonObject(it)) } }
        args += request.id
        val row = db.one(
            "UPDATE approvals SET ${sets.joinToString(", ")} WHERE id = ? AND state = 'pending' RETURNING ${Rows.APPROVAL}",
            *args.toTypedArray(),
        ) { Rows.request(it) } ?: throw settledAlready(mustFind(request.id))

        val edited = OwnerRequest(row)
        bus.publish("request.updated", buildJsonObject {
            put("requestId", edited.id)
            put("agentId", edited.agentId)
            put("projectId", edited.projectId)
        })
        return edited
    }

    /** Flip every pending request past its deadline, one `request.answered` each (the sweeper). */
    fun expireDue(): List<OwnerRequest> {
        val now = clock()
        return db.query(
            "UPDATE approvals SET state = 'timeout', decided_at = ? WHERE state = 'pending' AND expires_at <= ? RETURNING ${Rows.APPROVAL}",
            now, now,
        ) { Rows.request(it) }.map { settle(it, "timeout") }
    }

    fun find(requestId: String): OwnerRequest? =
        db.one("SELECT ${Rows.APPROVAL} FROM approvals WHERE id = ?", requestId) { OwnerRequest(Rows.request(it)) }

    // ---- the wait ---------------------------------------------------------------------------------

    private suspend fun hold(request: OwnerRequest, since: Long): RequestResult {
        val current = expireIfDue(request)
        if (current.state != "pending") return resultFor(current)

        val timeoutMs = maxOf(0L, minOf(holdMs(), current.expiresAt - clock()))
        waitForSettle(current.id, since, timeoutMs)

        val settled = expireIfDue(mustFind(current.id))
        if (settled.state != "pending") return resultFor(settled)
        return RequestResult("pending", settled)
    }

    /**
     * Park until `request.answered` for [requestId], or [timeoutMs]. Events after [since] still in
     * the replay ring count, so an answer that landed before the park is not missed.
     */
    private suspend fun waitForSettle(requestId: String, since: Long, timeoutMs: Long) {
        fun matches(event: AppEvent) = event.type == "request.answered" &&
            (event.payload["requestId"] as? JsonPrimitive)?.content == requestId
        val found = CompletableDeferred<Unit>()
        val (already, stop) = bus.subscribeFrom(since, { if (matches(it)) found.complete(Unit) }) { replay ->
            (replay as? Ring.Replay.Hit)?.events?.any(::matches) == true
        }
        try {
            if (already) return
            withTimeoutOrNull(timeoutMs) { found.await() }
        } finally {
            stop()
        }
    }

    private fun resultFor(request: OwnerRequest): RequestResult {
        val answer = request.answer
        return when {
            request.state == "answered" && answer != null -> RequestResult("answered", request, answer, request.answeredAt ?: 0)
            request.state in setOf("timeout", "cancelled", "withdrawn") -> RequestResult(request.state, request)
            else -> RequestResult("pending", request)
        }
    }

    private fun settle(row: JsonObject, state: String): OwnerRequest {
        val request = OwnerRequest(row)
        bus.publish("request.answered", buildJsonObject {
            put("requestId", request.id)
            put("agentId", request.agentId)
            put("state", state)
            put("settledAt", Wire.isoOf(request.answeredAt ?: clock()))
        })
        return request
    }

    /** Time out a due request where it is read; whoever settled it first wins. */
    private fun expireIfDue(request: OwnerRequest): OwnerRequest {
        if (request.state != "pending" || clock() < request.expiresAt) return request
        val row = decide(request.id, "timeout", null, null, clock()) ?: return mustFind(request.id)
        return settle(row, "timeout")
    }

    private fun decide(id: String, state: String, value: String?, answer: String?, at: Long): JsonObject? = db.one(
        "UPDATE approvals SET state = ?, decided_value = ?, answer = ?, decided_at = ? WHERE id = ? AND state = 'pending' RETURNING ${Rows.APPROVAL}",
        state, value, answer, at, id,
    ) { Rows.request(it) }

    private fun mustFind(requestId: String): OwnerRequest = find(requestId) ?: throw notFound("no such request: $requestId")

    private fun own(requestId: String, agentId: String): OwnerRequest {
        val request = mustFind(requestId)
        if (request.agentId != agentId) throw invalid("request belongs to another agent")
        return request
    }

    private fun settledAlready(request: OwnerRequest): DomainError {
        val said = when (request.state) {
            "answered" -> "has already been answered"
            "timeout" -> "timed out"
            "cancelled" -> "was dismissed"
            "withdrawn" -> "was withdrawn"
            else -> "is still pending"
        }
        return conflict("request ${request.id} $said")
    }

    // ---- validation -------------------------------------------------------------------------------

    private fun assertKind(kind: String): String {
        if (kind !in KINDS) throw invalid("kind must be one of ${KINDS.joinToString(", ")}")
        return kind
    }

    private fun assertOptions(kind: String, options: List<String>?): List<String>? {
        if (kind !in LISTED) {
            if (!options.isNullOrEmpty()) throw invalid("a $kind request takes no options")
            return null
        }
        val list = (options ?: emptyList()).mapIndexed { i, option -> requiredText(option, "options[$i]", OPTION_MAX_LENGTH) }
        if (list.isEmpty()) throw invalid("a $kind request needs at least one option")
        if (list.size > OPTIONS_MAX) throw invalid("at most $OPTIONS_MAX options")
        if (list.toSet().size != list.size) throw invalid("options must be distinct")
        return list
    }

    private fun assertConfig(kind: String, options: List<String>?, input: NewRequest): Map<String, JsonElement>? {
        val config = LinkedHashMap<String, JsonElement>()
        optionalText(input.placeholder, "placeholder", OPTION_MAX_LENGTH)?.let {
            if (kind !in TEXTUAL) throw invalid("placeholder is for a text or form request")
            config["placeholder"] = JsonPrimitive(it)
        }
        if (input.multiline == true) {
            if (kind !in TEXTUAL) throw invalid("multiline is for a text or form request")
            config["multiline"] = JsonPrimitive(true)
        }
        optionalText(input.label, "label", OPTION_MAX_LENGTH)?.let {
            if (kind != "form") throw invalid("label names a form request’s field")
            config["label"] = JsonPrimitive(it)
        }
        optionalText(input.default, "default", TEXT_ANSWER_MAX_LENGTH)?.let { fallback ->
            if (kind == "multi_choice") throw invalid("a multi_choice takes no default")
            if (kind == "confirm" && fallback != "true" && fallback != "false") throw invalid("the default for a confirm is \"true\" or \"false\"")
            if (options != null && kind != "form" && fallback !in options) throw invalid("the default must be one of the options")
            config["default"] = JsonPrimitive(fallback)
        }
        if (input.min != null || input.max != null) {
            if (kind !in TEXTUAL && kind != "multi_choice") throw invalid("min and max are for a text, form or multi_choice request")
            val ceiling = if (kind in TEXTUAL) TEXT_ANSWER_MAX_LENGTH else options?.size ?: 0
            val min = bound(input.min, "min", ceiling)
            val max = bound(input.max, "max", ceiling)
            if (min != null && max != null && min > max) throw invalid("min must not exceed max")
            if (min != null) config["min"] = Js.numberLiteral(min)
            if (max != null) config["max"] = Js.numberLiteral(max)
        }
        return config.takeIf { it.isNotEmpty() }
    }

    private fun bound(value: Double?, field: String, ceiling: Int): Double? {
        if (value == null) return null
        if (!Js.isInteger(value) || value < 0) throw invalid("$field must be a whole number")
        if (value > ceiling) throw invalid("$field must be at most $ceiling")
        return value
    }

    private fun assertTimeout(timeoutS: Double?): Double {
        if (timeoutS == null) return DEFAULT_TIMEOUT_S.toDouble()
        if (!Js.isInteger(timeoutS) || timeoutS < MIN_TIMEOUT_S || timeoutS > MAX_TIMEOUT_S) {
            throw invalid("timeout_s must be a whole number of seconds between $MIN_TIMEOUT_S and $MAX_TIMEOUT_S")
        }
        return timeoutS
    }

    private data class Anchor(val projectId: String?, val updateId: String?, val messageId: String?)

    private fun anchor(input: NewRequest): Anchor {
        val named = input.project?.takeIf { it.jsTrim().isNotEmpty() }?.let { reads.resolveProject(it) }
        val messageRef = input.message?.jsTrim()
        if (!messageRef.isNullOrEmpty()) {
            val message = db.one("SELECT id, project_id, update_id, deleted_at FROM messages WHERE id = ?", messageRef) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getObject(4))
            }
            if (message == null || message[3] != null) throw notFound("no such message: $messageRef")
            val projectId = message[1] as String?
            if (named != null && projectId != null && named.id != projectId) throw invalid("the message named belongs to a different project")
            return Anchor(projectId ?: named?.id, message[2] as String?, message[0] as String)
        }
        val updateRef = input.update?.jsTrim()
        if (updateRef.isNullOrEmpty()) return Anchor(named?.id, null, null)
        val update = db.one("SELECT id, project_id FROM updates WHERE id = ?", updateRef) { it.getString(1) to it.getString(2) }
            ?: throw notFound("no such update: $updateRef")
        if (named != null && named.id != update.second) throw invalid("the update named belongs to a different project")
        return Anchor(update.second, update.first, null)
    }

    private class MediaRow(val author: String, val attachedTo: String?, val sha256: String, val kind: String)

    private fun media(id: String): MediaRow? = db.one(
        "SELECT author, update_id, message_id, request_id, sha256, kind FROM media WHERE id = ?", id,
    ) {
        val on = when {
            it.getString("update_id") != null -> "an update"
            it.getString("message_id") != null -> "another message"
            it.getString("request_id") != null -> "an approval"
            else -> null
        }
        MediaRow(it.getString("author"), on, it.getString("sha256"), it.getString("kind"))
    }

    /** `assertAttachableToMessage` (TS `src/domain/media.ts:374`): ids are checked, then each upload. */
    private fun assertAttachable(mediaIds: List<String>, author: String): List<String> {
        val unique = mediaIds.distinct()
        if (unique.isEmpty()) throw invalid("media_ids must name at least one upload")
        if (unique.size > MEDIA_PER_UPDATE_MAX) throw invalid("an update may carry at most $MEDIA_PER_UPDATE_MAX media items")
        for (id in unique) if (!Reads.ID.matches(id)) throw invalid("not a media id: ${jsonStringifyJs(JsonPrimitive(id))}")
        for (id in unique) {
            val media = media(id) ?: throw notFound("no such media: $id")
            if (media.author != author) throw invalid("media $id was uploaded by somebody else")
            if (media.attachedTo != null) throw invalid("media $id is already on ${media.attachedTo}")
            if (media.sha256 == "") throw invalid("media $id has no bytes yet: upload the file first")
        }
        return unique
    }

    private fun checkedOptionMedia(kind: String, options: List<String>?, given: List<Pair<String, String>>?, author: String): List<Pair<String, String>>? {
        if (given.isNullOrEmpty()) return null
        if (kind !in PICTURED) throw invalid("pictures on options are for a buttons, choice or multi_choice request")
        for ((option, _) in given) if (options == null || option !in options) throw invalid("$option is not one of the options")
        val ids = given.map { it.second }
        if (ids.toSet().size != ids.size) throw invalid("each picture may be used once")
        assertAttachable(ids, author)
        for (id in ids) if (media(id)?.kind != "image") throw invalid("media $id must be an image to go on an option")
        return given
    }

    private class CheckedEmbed(val typeId: String, val data: String, val sourceUrl: String?)

    /** `checkedEmbeds` (TS `src/domain/embeds.ts:641`). */
    private fun checkedEmbeds(embeds: List<NewEmbed>): List<CheckedEmbed> {
        if (embeds.size > EMBEDS_PER_POST_MAX) throw invalid("a post may carry at most $EMBEDS_PER_POST_MAX embeds")
        return embeds.mapIndexed { at, embed ->
            val slug = embed.type.jsTrim().lowercase()
            val typeId = db.one("SELECT id FROM embed_types WHERE slug = ?", slug) { it.getString(1) }
                ?: throw invalid("embeds[$at]: no embed type \"$slug\". Call list_embed_types to see what is registered, or register_embed_type to add it.")
            CheckedEmbed(typeId, Wire.encode(Embeds.parseData(embed.data, "embeds[$at].data")), sourceUrl(embed.sourceUrl, "embeds[$at].source_url"))
        }
    }

    /**
     * `parseSourceUrl`: an absolute http(s) URL, written back as WHATWG `URL#toString` would — the
     * common normalisations (lower-case scheme and host, `/` for an empty path, default port dropped).
     */
    private fun sourceUrl(raw: String?, field: String): String? {
        if (raw.isNullOrEmpty()) return null
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(?://|\\\\\\\\)([^/?#\\\\]*)(.*)$", RegexOption.DOT_MATCHES_ALL).find(raw.jsTrim())
            ?: throw invalid("$field must be an absolute http or https URL")
        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") throw invalid("$field must be an absolute http or https URL")
        var authority = match.groupValues[2]
        val userinfo = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        authority = authority.substringAfterLast('@')
        var host = authority
        var port = ""
        val colon = authority.lastIndexOf(':')
        if (colon >= 0 && !authority.endsWith("]")) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
            if (port.isNotEmpty() && (!port.all(Char::isDigit) || port.toLong() > 65535)) throw invalid("$field must be an absolute http or https URL")
            if (port.isNotEmpty()) port = port.toLong().toString()
            if ((scheme == "http" && port == "80") || (scheme == "https" && port == "443")) port = ""
        }
        if (host.isEmpty() || host.any { it in " <>^|%\"`{}" }) throw invalid("$field must be an absolute http or https URL")
        var rest = match.groupValues[3].replace('\\', '/')
        if (rest.isEmpty() || rest[0] == '?' || rest[0] == '#') rest = "/$rest"
        return "$scheme://$userinfo${host.lowercase()}${if (port.isEmpty()) "" else ":$port"}$rest"
    }

    /** `validateAnswer` (TS `requests.ts:703`): the answer checked against the request that asked. */
    fun validateAnswer(request: OwnerRequest, value: JsonElement): JsonObject {
        val kind = request.kind
        val config = request.config ?: JsonObject(emptyMap())
        val options = request.options ?: emptyList()
        fun configInt(key: String) = config[key]?.jsNumber
        fun n(d: Double) = Js.numberToString(d)
        fun answer(v: JsonElement) = buildJsonObject { put("kind", kind); put("value", v) }

        when (kind) {
            "confirm" -> {
                val b = value.jsBoolean ?: throw invalid("a confirm is answered true or false")
                return answer(JsonPrimitive(b))
            }
            "text" -> {
                val s = value.jsString ?: throw invalid("a text request is answered with a string")
                val text = s.jsTrim()
                val min = configInt("min") ?: 1.0
                val max = configInt("max") ?: TEXT_ANSWER_MAX_LENGTH.toDouble()
                if (text.length < min) throw invalid("the answer must be at least ${n(min)} characters")
                if (text.length > max) throw invalid("the answer must be at most ${n(max)} characters")
                return answer(JsonPrimitive(text))
            }
            "multi_choice" -> {
                if (value !is JsonArray) throw invalid("a multi_choice is answered with a list of options")
                for (item in value) {
                    val s = item.jsString
                    if (s == null || s !in options) throw invalid("${jsonStringifyJs(item)} is not one of the options offered")
                }
                val picked = value.map { it.jsString!! }
                if (picked.toSet().size != picked.size) throw invalid("an option was chosen twice")
                val min = configInt("min") ?: 0.0
                val max = configInt("max") ?: options.size.toDouble()
                if (picked.size < min) throw invalid("choose at least ${n(min)}")
                if (picked.size > max) throw invalid("choose at most ${n(max)}")
                return answer(JsonArray(picked.map(::JsonPrimitive)))
            }
            "form" -> {
                if (value !is JsonObject) throw invalid("a form is answered with { action, text }")
                val action = value["action"]
                val actionText = action.jsString
                if (actionText == null || actionText !in options) throw invalid("${jsonStringifyJs(action)} is not one of the actions offered")
                val raw = value["text"].jsString ?: throw invalid("a form’s text is a string")
                val text = raw.jsTrim()
                val min = configInt("min") ?: 0.0
                val max = configInt("max") ?: TEXT_ANSWER_MAX_LENGTH.toDouble()
                if (text.length < min) throw invalid("the text must be at least ${n(min)} characters")
                if (text.length > max) throw invalid("the text must be at most ${n(max)} characters")
                return answer(buildJsonObject { put("action", actionText); put("text", text) })
            }
        }
        val s = value.jsString
        if (s == null || s !in options) throw invalid("${jsonStringifyJs(value)} is not one of the options offered")
        return answer(JsonPrimitive(s))
    }

    /** The scalar `decided_value` keeps (TS `scalarOf`). */
    private fun scalarOf(value: JsonElement): String? = when {
        value.jsBoolean != null -> value.jsBoolean.toString()
        value is JsonArray -> null
        value is JsonObject -> value["action"].jsString
        else -> value.jsString
    }

    companion object {
        val KINDS = listOf("text", "confirm", "buttons", "choice", "multi_choice", "form")
        private val LISTED = setOf("buttons", "choice", "multi_choice", "form")
        private val TEXTUAL = setOf("text", "form")
        private val PICTURED = setOf("buttons", "choice", "multi_choice")
        const val QUESTION_MAX_LENGTH = 500
        const val DETAIL_MAX_LENGTH = 4_000
        const val OPTION_MAX_LENGTH = 200
        const val OPTIONS_MAX = 25
        const val TEXT_ANSWER_MAX_LENGTH = 10_000
        const val DEFAULT_TIMEOUT_S = 3_600
        const val MIN_TIMEOUT_S = 5
        const val MAX_TIMEOUT_S = 86_400
        const val DEFAULT_HOLD_MS = 55_000L
        const val POLL_AFTER_MS = 1_000
        const val SWEEP_INTERVAL_MS = 30_000L
        const val MEDIA_PER_UPDATE_MAX = 24
        const val EMBEDS_PER_POST_MAX = 8
    }
}

/** `optionalText`: trimmed, blank is null, at most [max] UTF-16 units. */
/** `JSON.stringify(value)`, with `undefined` for a missing value. */
internal fun jsonStringifyJs(value: JsonElement?): String = if (value == null) "undefined" else Wire.encode(Js.normalize(value))

/** `JSON.stringify` of an object built in JavaScript, for a JSON text column. */
private fun jsonText(value: JsonObject): String = Wire.encode(Js.normalize(value))
