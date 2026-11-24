package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.NewEmbed
import dev.wildware.feed.server.domain.NewRequest
import dev.wildware.feed.server.domain.OwnerRequest
import dev.wildware.feed.server.domain.RequestEdit
import dev.wildware.feed.server.domain.RequestResult
import dev.wildware.feed.server.domain.Requests
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.jsonStringifyJs
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `request_input`, `await_request`, `edit_request`, `withdraw_request` (spec/05-mcp.md §3.2, §4, §7.28-7.31). */
fun ToolRegistry.registerRequestTools(app: App) {
    register("request_input") { call ->
        val a = call.args
        val input = NewRequest(
            kind = a["kind"].jsString!!,
            question = a["question"].jsString!!,
            detail = a["detail"].jsString,
            options = (a["options"] as? JsonArray)?.map { it.jsString!! },
            placeholder = a["placeholder"].jsString,
            multiline = a["multiline"].jsBoolean,
            label = a["label"].jsString,
            default = a["default"].jsString,
            min = a["min"].jsNumber,
            max = a["max"].jsNumber,
            project = call.channel(),
            update = a["update"].jsString,
            message = a["message_id"].jsString,
            timeoutS = a["timeout_s"].jsNumber,
            priority = a["priority"].jsString,
            mediaIds = (a["media_ids"] as? JsonArray)?.map { it.jsString!! },
            optionMedia = (a["option_media"] as? JsonObject)?.let { pictures ->
                Js.orderedKeys(pictures.keys).map { it to pictures.getValue(it).jsString!! }
            },
            embeds = (a["embeds"] as? JsonArray)?.map { e ->
                val embed = e as JsonObject
                NewEmbed(embed["type"].jsString!!, embed["data"], embed["source_url"].jsString)
            },
            refs = refsArg(a),
            blocks = a["blocks"] as? JsonArray,
        )
        requestResult(app.requests.requestInput(call.agent.id, input), app.db)
    }

    register("await_request") { call ->
        requestResult(app.requests.awaitRequest(call.args["request_id"].jsString!!, call.agent.id), app.db)
    }

    register("edit_request") { call ->
        val a = call.args
        val request = app.requests.edit(
            a["request_id"].jsString!!,
            call.agent.id,
            RequestEdit(
                question = a["question"].jsString,
                detailGiven = "detail" in a,
                detail = a["detail"].jsString,
                options = (a["options"] as? JsonArray)?.map { it.jsString!! },
                defaultGiven = "default" in a,
                default = a["default"].jsString,
                refs = refsArg(a),
            ),
        )
        ToolResult.ok("Edited \"${request.question}\". Your owner sees the new question.", buildJsonObject {
            put("request", requestView(request))
            refsView(app, dev.wildware.feed.server.domain.CardRef("request", request.id), a)?.let { put("refs", it) }
        })
    }

    register("withdraw_request") { call ->
        val request = app.requests.withdraw(call.args["request_id"].jsString!!, call.agent.id)
        ToolResult.ok("Withdrew \"${request.question}\". It is off your owner's dashboard.", buildJsonObject {
            put("request_id", request.id)
            put("state", "withdrawn")
        })
    }
}

/** `requestView` (TS `src/mcp/results.ts:126`): `edited_at` only once it has been edited. */
internal fun requestView(request: OwnerRequest): JsonObject = buildJsonObject {
    put("id", request.id)
    put("kind", request.kind)
    put("question", request.question)
    put("detail", request.detail)
    put("options", request.json["options"] ?: JsonNull)
    put("project_id", request.projectId)
    put("update_id", request.updateId)
    put("message_id", request.messageId)
    put("expires_at", Wire.isoOf(request.expiresAt))
    request.editedAt?.let { put("edited_at", Wire.isoOf(it)) }
}

/** `requestResult` (TS `src/mcp/results.ts:149`). */
internal fun requestResult(result: RequestResult, db: dev.wildware.feed.server.db.Database? = null): ToolResult {
    val request = requestView(result.request)
    val id = result.request.id
    // On a team (migration 055), which person answered; a team of one reads as it always has.
    val by = result.request.answeredBy
    val byName = db?.let { dev.wildware.feed.server.domain.People.teamName(it, by) }
    return when (result.state) {
        "answered" -> ToolResult.ok(answeredSummary(result, byName), buildJsonObject {
            put("state", "answered")
            put("request_id", id)
            put("request", request)
            put("response", result.response!!)
            put("answered_at", Wire.isoOf(result.answeredAt))
            if (byName != null) put("answered_by", buildJsonObject { put("user_id", by); put("name", byName) })
        })
        "pending" -> ToolResult.ok(
            "Nobody has answered yet. This is not a refusal — call await_request({request_id: \"$id\"}) and keep calling it while state is \"pending\".",
            buildJsonObject {
                put("state", "pending")
                put("request_id", id)
                put("request", request)
                put("poll_after_ms", Requests.POLL_AFTER_MS)
            },
        )
        else -> {
            val why = if (result.state == "timeout") "Nobody answered before this request timed out." else "Your owner dismissed this request without answering."
            ToolResult.ok("$why That is not permission: do not proceed as though you had an answer.", buildJsonObject {
                put("state", result.state)
                put("request_id", id)
                put("request", request)
            })
        }
    }
}

private fun answeredSummary(result: RequestResult, byName: String? = null): String {
    val value = result.response!!["value"]!!
    val who = byName ?: "Your owner"
    if (result.request.kind == "secret") {
        if (value is JsonArray) {
            val skipped = (result.response!!["skipped"] as? JsonArray).orEmpty()
            val left = if (skipped.isEmpty()) "" else " They left ${skipped.joinToString(", ") { jsonStringifyJs(it) }} empty."
            return "$who saved ${value.size} secret(s): ${value.joinToString(", ") { jsonStringifyJs(it) }}.$left " +
                "Fetch each with secret_url into a file; never print a value."
        }
        return "$who saved the secret ${jsonStringifyJs(value)}. Fetch it with secret_url into a file; never print its value."
    }
    result.request.secretAccessName?.let { name ->
        return if (value.jsBoolean == true) {
            "$who allowed you to use the secret ${jsonStringifyJs(kotlinx.serialization.json.JsonPrimitive(name))}. Fetch it with secret_url into a file; never print its value."
        } else {
            "$who denied you the secret ${jsonStringifyJs(kotlinx.serialization.json.JsonPrimitive(name))}. That is not permission: do not try to get it another way."
        }
    }
    return "$who answered: ${describe(value)}"
}

/** `describe` (TS `src/mcp/results.ts:188`): the answer in one line of the summary. */
private fun describe(value: JsonElement): String {
    value.jsBoolean?.let { return if (it) "yes." else "no." }
    if (value is JsonArray) return if (value.isEmpty()) "nothing." else value.joinToString(", ") { jsonStringifyJs(it) }
    if (value is JsonObject) {
        val text = value["text"].jsString ?: ""
        return "${jsonStringifyJs(value["action"])} on text of ${text.length} characters."
    }
    return jsonStringifyJs(value)
}
