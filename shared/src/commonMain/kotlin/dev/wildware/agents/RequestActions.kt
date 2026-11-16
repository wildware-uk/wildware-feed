package dev.wildware.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One button on a notification. A text input action shows a reply field instead of answering outright. */
data class NotificationAction(
    val id: String,
    val title: String,
    val isTextInput: Boolean = false,
    val isDestructive: Boolean = false,
)

/**
 * Which buttons a request gets on the lock screen, and what each button answers.
 *
 * Only answers that fit a tap or a short typed reply are offered. Multi-select and form requests
 * need the full card, so they get none and tapping the notification opens the dashboard.
 */
object RequestActions {
    /** iOS shows at most this many buttons on an expanded notification before it starts hiding them. */
    const val MaxOptionButtons = 4

    const val Yes = "confirm-yes"
    const val No = "confirm-no"
    const val Reply = "reply"
    private const val OptionPrefix = "option-"

    fun actionsFor(kind: RequestKind, options: List<String>?): List<NotificationAction> = when (kind) {
        RequestKind.Confirm -> listOf(
            NotificationAction(Yes, "Yes"),
            NotificationAction(No, "No", isDestructive = true),
        )

        RequestKind.Buttons, RequestKind.Choice -> {
            val choices = options.orEmpty()
            if (choices.isEmpty() || choices.size > MaxOptionButtons) {
                emptyList()
            } else {
                choices.mapIndexed { index, option -> NotificationAction("$OptionPrefix$index", option) }
            }
        }

        RequestKind.Text -> listOf(NotificationAction(Reply, "Reply", isTextInput = true))
        RequestKind.MultiChoice, RequestKind.Form -> emptyList()
    }

    /**
     * The `value` to send to `POST /api/requests/{id}/answer` for a tapped action,
     * or null when the action does not answer this request (a stale or unknown button).
     */
    fun answerFor(kind: RequestKind, options: List<String>?, actionId: String, typedText: String?): JsonElement? =
        when (kind) {
            RequestKind.Confirm -> when (actionId) {
                Yes -> JsonPrimitive(true)
                No -> JsonPrimitive(false)
                else -> null
            }

            RequestKind.Buttons, RequestKind.Choice -> actionId
                .removePrefix(OptionPrefix)
                .takeIf { actionId.startsWith(OptionPrefix) }
                ?.toIntOrNull()
                ?.let { options?.getOrNull(it) }
                ?.let(::JsonPrimitive)

            RequestKind.Text -> typedText
                ?.trim()
                ?.takeIf { actionId == Reply && it.isNotEmpty() }
                ?.let(::JsonPrimitive)

            RequestKind.MultiChoice, RequestKind.Form -> null
        }

    /** A category per distinct button set, so two requests with the same options share one. */
    fun categoryId(kind: RequestKind, options: List<String>?): String =
        "REQUEST." + kind.wireName + "." + actionsFor(kind, options).joinToString("|") { it.title }.hashCode().toUInt()
}
