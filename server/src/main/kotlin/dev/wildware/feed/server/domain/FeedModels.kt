package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Rows as the owner API prints them (`Rows`), with typed reads of the fields the rules need. The
 * JSON is the source: the owner API sends it as it is, the MCP views are built from the fields.
 */

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"

class ProjectRow(val json: JsonObject) {
    val id get() = json.text("id")!!
    val slug get() = json.text("slug")!!
    val name get() = json.text("name")!!
    val description get() = json.text("description")
    val status get() = json.text("status")!!
    val pinned get() = json.flag("pinned")
    val createdAt get() = json.number("createdAt")!!
    val updatedAt get() = json.number("updatedAt")!!
    val theme: JsonElement get() = json["theme"] ?: JsonNull
}

class UpdateRow(val json: JsonObject) {
    val id get() = json.text("id")!!
    val projectId get() = json.text("projectId")!!
    val agentId get() = json.text("agentId")!!
    val sessionId get() = json.text("sessionId")
    val title get() = json.text("title")
    val body get() = json.text("body")!!
    val level get() = json.text("level")!!
    val pinned get() = json.flag("pinned")
    val createdAt get() = json.number("createdAt")!!
    val deletedAt get() = json.number("deletedAt")
    val editedAt get() = json.number("editedAt")
    val status get() = json.text("status")
}

class MessageRow(val json: JsonObject) {
    val seq get() = json.number("seq")!!
    val id get() = json.text("id")!!
    val projectId get() = json.text("projectId")
    val updateId get() = json.text("updateId")
    val taskId get() = json.text("taskId")
    val author get() = json.text("author")!!
    val body get() = json.text("body")!!
    val createdAt get() = json.number("createdAt")!!
    val replyTo get() = json.text("replyTo")
    val deletedAt get() = json.number("deletedAt")
    val answers get() = json.text("answers")
    val requestId get() = json.text("requestId")
    val editedAt get() = json.number("editedAt")

    /** `threadKeys`: the four keys events carry after the author. */
    fun threadKeys(): List<Pair<String, String?>> =
        listOf("updateId" to updateId, "replyTo" to replyTo, "requestId" to requestId, "taskId" to taskId)
}

class TagRow(val json: JsonObject) {
    val id get() = json.text("id")!!
    val projectId get() = json.text("projectId")!!
    val name get() = json.text("name")!!
    val slug get() = json.text("slug")!!
    val colour get() = json.text("colour")
    val agentId get() = json.text("agentId")
    val createdAt get() = json.number("createdAt")!!
}

class FieldRow(val json: JsonObject) {
    val name get() = json.text("name")!!
    val value get() = json.text("value")!!
    val author get() = json.text("author")!!
    val createdAt get() = json.number("createdAt")!!
    val updatedAt get() = json.number("updatedAt")!!
}

class ReactionRow(val json: JsonObject) {
    val id get() = json.text("id")!!
    val card get() = json["card"] as JsonObject
    val messageId get() = json.text("messageId")
    val actor get() = json.text("actor")!!
    val emoji get() = json.text("emoji")!!
    val createdAt get() = json.number("createdAt")!!
}

class AckRow(val json: JsonObject) {
    val id get() = json.text("id")!!
    val agentId get() = json.text("agentId")!!
    val card get() = json["card"] as JsonObject
    val messageId get() = json.text("messageId")
    val taskId get() = json.text("taskId")
    val state get() = json.text("state")!!
    val createdAt get() = json.number("createdAt")!!
    val updatedAt get() = json.number("updatedAt")!!
}

/** `{kind, id}`, the one way the wire names a card. */
data class CardRef(val kind: String, val id: String) {
    val json: JsonObject get() = buildJsonObject { put("kind", kind); put("id", id) }
    val anchor: String get() = ANCHOR.getValue(kind)
    val table: String get() = TABLE.getValue(kind)

    companion object {
        val KINDS = listOf("update", "message", "request")
        private val ANCHOR = mapOf("update" to "update_id", "message" to "message_id", "request" to "request_id")
        private val TABLE = mapOf("update" to "updates", "message" to "messages", "request" to "approvals")
    }
}

/** What every kind of card has: `findCard` (TS `src/db/cards.ts`). */
data class CardRow(
    val id: String,
    val projectId: String?,
    val pinned: Boolean,
    val status: String?,
    val statusAt: Long?,
    val deletedAt: Long?,
)

/** JavaScript text behaviour the rules lean on. */
object JsText {
    /** `JSON.stringify(text)`. */
    fun quote(text: String): String {
        val out = StringBuilder("\"")
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                c.isSurrogate() -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }

    /** `/\s/.test(text)`. */
    fun hasSpace(text: String) = text.any { it.isJsWhitespace() }

    /** `text.replace(/\s+/g, replacement)`. */
    fun collapseSpaces(text: String, replacement: String): String {
        val out = StringBuilder()
        var inRun = false
        for (c in text) {
            if (c.isJsWhitespace()) {
                if (!inRun) out.append(replacement)
                inRun = true
            } else {
                out.append(c)
                inRun = false
            }
        }
        return out.toString()
    }

    /** `String(value)` for a JSON value (`undefined` is Kotlin null). */
    fun string(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" || value.content == "false" -> value.content
            else -> Js.numberToString(value.content.toDouble())
        }
        is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else string(it) }
        is JsonObject -> "[object Object]"
    }

    /** `text.trimEnd()`. */
    fun trimEnd(text: String): String {
        var end = text.length
        while (end > 0 && text[end - 1].isJsWhitespace()) end--
        return text.substring(0, end)
    }
}
