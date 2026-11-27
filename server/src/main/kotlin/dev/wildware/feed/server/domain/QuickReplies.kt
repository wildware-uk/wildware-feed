package dev.wildware.feed.server.domain

import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Quick replies (migration 081, docs/QUICK_REPLIES.md): up to [BUTTONS_MAX] buttons, or a small form,
 * on an agent's ordinary message. Nothing waits on them: a person may tap one, and that tap is sent
 * as their own reply in the same thread, answering the message. If nobody taps, nothing happens.
 *
 * The offer is kept on the agent's message as `{ buttons, fields?, answers }`. Each answer is a
 * message by the person with `reply_for` (the message answered) and `reply_value` (`{ button,
 * values? }`); `answers` on the offer is worked out again from those after every answer and every
 * unsend, so it always says who chose what. One live answer per person per message: a unique index
 * holds that, whichever server takes the tap.
 */
object QuickReplies {
    const val BUTTONS_MAX = 4
    const val BUTTON_MAX = 80

    /** A form's one button when the agent names none. */
    const val SEND = "Send"

    /** The offer a post brings, checked; null when it brings none. [fields] is request_input's form model. */
    fun check(buttons: JsonElement?, fields: JsonElement?): JsonObject? {
        if (buttons == null && fields == null) return null
        val form = fields?.let { FormFields.check(it as? JsonArray ?: throw invalid("fields must be a list of form fields")) }
        val labels = buttons?.let { given ->
            val list = given as? JsonArray ?: throw invalid("buttons must be a list of labels")
            if (list.isEmpty()) throw invalid("buttons needs at least one label, or leave it out")
            if (list.size > BUTTONS_MAX) throw invalid("a message has at most $BUTTONS_MAX buttons")
            val clean = list.mapIndexed { i, b -> requiredText(b.jsString ?: throw invalid("buttons[$i] must be a string"), "buttons[$i]", BUTTON_MAX) }
            if (clean.toSet().size != clean.size) throw invalid("buttons must be distinct")
            clean
        } ?: listOf(SEND)
        return buildJsonObject {
            put("buttons", JsonArray(labels.map(::JsonPrimitive)))
            form?.let { put("fields", it) }
            put("answers", JsonArray(emptyList()))
        }
    }

    private fun buttonsOf(offer: JsonObject) = (offer["buttons"] as JsonArray).map { it.jsString!! }

    /**
     * [personId] taps [button] on [messageId] (with [values] for a form). Posts their reply in the
     * thread, answering the message, and returns it. The same [sendKey] again gets that reply back,
     * so a double tap sends once; a second answer by the same person is refused.
     */
    fun answer(feed: Feed, messageId: String, button: String, values: JsonElement?, personId: String, sendKey: String?): MessageRow = feed.op {
        val target = feed.messages.find(messageId)
        if (target == null || target.deletedAt != null) throw notFound("no such message: $messageId")
        val offer = target.replies ?: throw invalid("that message has no quick replies")
        val buttons = buttonsOf(offer)
        val fields = FormFields.of(offer)
        if (button !in buttons) throw invalid("${jsonStringifyJs(JsonPrimitive(button))} is not one of the buttons offered")
        val value = if (fields != null) {
            val checked = FormFields.answer(fields, buttons, buildJsonObject { put("action", button); put("values", values ?: JsonObject(emptyMap())) })
            buildJsonObject { put("button", button); put("values", checked["values"]!!) }
        } else {
            if (values != null) throw invalid("that message offers buttons, not a form: send button alone")
            buildJsonObject { put("button", button) }
        }
        // The same tap again (its answer was lost on the way back): the first reply, not a refusal.
        val earlier = feed.db.one(
            "SELECT id, send_key FROM messages WHERE team_id = :team AND reply_for = ? AND user_id = ? AND deleted_at IS NULL", target.id, personId,
        ) { it.getString(1) to it.getString(2) }
        if (earlier != null) {
            if (sendKey != null && earlier.second == sendKey) return@op feed.messages.find(earlier.first)!!
            throw conflict(ANSWERED)
        }
        val reply = feed.messages.post(
            Messages.Post(
                author = Messages.HUMAN, body = body(buttons, fields, value), answers = target.id, userId = personId, sendKey = sendKey,
                replyFor = target.id, replyValue = value,
            ),
        )
        refresh(feed, target.id)
        reply
    }

    const val ANSWERED = "you have already answered that message"

    /** Work out again who chose what on [messageId], from the live answers, and tell the apps. */
    fun refresh(feed: Feed, messageId: String) {
        val db = feed.db
        val target = feed.messages.find(messageId) ?: return
        val offer = target.replies ?: return
        data class Answer(val id: String, val userId: String, val value: JsonObject, val at: Long)
        val answers = db.query(
            "SELECT id, user_id, reply_value, created_at FROM messages WHERE team_id = :team AND reply_for = ? AND deleted_at IS NULL ORDER BY seq",
            messageId,
        ) { Answer(it.getString(1), it.getString(2) ?: People.FOUNDER, Js.parse(it.getString(3)) as JsonObject, it.getLong(4)) }
        val next = JsonObject(offer + ("answers" to JsonArray(answers.map { a ->
            buildJsonObject {
                put("userId", a.userId)
                People.teamName(db, a.userId)?.let { put("name", it) }
                for ((k, v) in a.value) put(k, v)
                put("messageId", a.id)
                put("at", a.at)
            }
        })))
        if (next == offer) return
        db.update("UPDATE messages SET replies = ? WHERE team_id = :team AND id = ?", Wire.encode(next), messageId)
        feed.publish("message.updated", buildJsonObject {
            put("messageId", target.id)
            put("projectId", target.projectId)
            put("author", target.author)
            for ((k, v) in target.threadKeys()) put(k, v)
        })
    }

    /**
     * The reply's words, as both the person and the agent read them: the button for buttons; for a
     * form, each field filled in as "- **Label:** value", under the button when there are several.
     */
    fun body(buttons: List<String>, fields: JsonArray?, value: JsonObject): String {
        val button = value["button"].jsString!!
        if (fields == null) return button
        val values = value["values"] as? JsonObject ?: JsonObject(emptyMap())
        val lines = fields.mapNotNull { f ->
            f as JsonObject
            val v = values[f["id"].jsString!!] ?: return@mapNotNull null
            "- **${f["label"].jsString}:** ${shown(v)}"
        }
        val text = listOfNotNull(button.takeIf { buttons.size > 1 || lines.isEmpty() }, lines.joinToString("\n").ifEmpty { null }).joinToString("\n\n")
        return if (text.length > Messages.BODY_MAX) text.substring(0, Messages.BODY_MAX - 1) + "…" else text
    }

    private fun shown(v: JsonElement): String = when {
        v is JsonArray -> if (v.isEmpty()) "none" else v.joinToString(", ") { it.jsString ?: it.toString() }
        v.jsBoolean != null -> if (v.jsBoolean == true) "yes" else "no"
        v.jsString != null -> v.jsString!!.replace("\n", "\n  ")
        else -> v.toString()
    }

    /** The offer as an agent reads it: snake_case, times as ISO, a name only on a team. */
    fun forAgents(offer: JsonObject): JsonObject = buildJsonObject {
        put("buttons", offer["buttons"]!!)
        offer["fields"]?.let { put("fields", it) }
        put("answers", JsonArray((offer["answers"] as? JsonArray).orEmpty().map { a ->
            a as JsonObject
            buildJsonObject {
                put("user_id", a["userId"]!!)
                a["name"]?.let { put("name", it) }
                put("button", a["button"]!!)
                a["values"]?.let { put("values", it) }
                put("message_id", a["messageId"]!!)
                (a["at"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { put("answered_at", Wire.isoOf(it)) }
            }
        }))
    }

    /** A person's answer as an agent reads it: which message it answers and what they chose. */
    fun replyForAgents(to: String, value: JsonObject?): JsonObject = buildJsonObject {
        put("to", to)
        value?.forEach { (k, v) -> put(k, v) }
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
