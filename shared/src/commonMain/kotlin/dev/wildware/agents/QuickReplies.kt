package dev.wildware.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Quick replies (server migration 081, docs/QUICK_REPLIES.md): up to 4 buttons, or a small form, an
 * agent puts on an ordinary message. Not a question: nothing waits on them. A tap is sent as the
 * person's own reply in the thread ([DashboardClient.quickReply]); ignoring them does nothing.
 */

/** The buttons or form a message offers, and who chose what so far. */
@Serializable
data class QuickRepliesView(
    val buttons: List<String> = emptyList(),
    /** A form to fill in, with request_input's fields; null for plain buttons, which are then the choices. */
    val fields: List<FormFieldView>? = null,
    val answers: List<QuickAnswerView> = emptyList(),
) {
    /** What [person] chose, or null when they haven't answered. */
    fun answerOf(person: String): QuickAnswerView? = answers.firstOrNull { it.userId == person }
}

/** One person's answer: the button they pressed and, for a form, what they filled in. */
@Serializable
data class QuickAnswerView(
    val userId: String,
    /** Their name, on a team of several; null on a team of one. */
    val name: String? = null,
    val button: String,
    val values: JsonObject? = null,
    /** Their reply in the thread that carried it. */
    val messageId: String,
    /** When they answered (epoch millis). */
    val at: Long = 0,
)

/** `POST /api/messages/{id}/reply`'s answer: the reply, and the message it answered as it now stands. */
@Serializable
internal data class QuickReplyBody(val message: MessageView, val answered: MessageView? = null)
