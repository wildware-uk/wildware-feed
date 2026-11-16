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

    /**
     * The notification category for a request's buttons, or null when it has none. There is one per
     * kind and button count, a fixed set the notification content extension can list by name.
     * Requests with the same count share a category, so its buttons carry no question's own titles.
     */
    fun categoryId(kind: RequestKind, options: List<String>?): String? {
        val count = actionsFor(kind, options).size
        return when {
            count == 0 -> null
            kind == RequestKind.Buttons || kind == RequestKind.Choice -> "$CategoryPrefix.options.$count"
            else -> "$CategoryPrefix.${kind.wireName}"
        }
    }

    val categoryIds: List<String> =
        listOf("$CategoryPrefix.confirm", "$CategoryPrefix.text") +
            (1..MaxOptionButtons).map { "$CategoryPrefix.options.$it" }

    /**
     * The buttons a shared category is registered with. They must be true for every question in it,
     * so options are numbered rather than named: a named button could answer another question's option.
     */
    fun categoryActions(categoryId: String): List<NotificationAction> = when (categoryId) {
        "$CategoryPrefix.confirm" -> actionsFor(RequestKind.Confirm, null)
        "$CategoryPrefix.text" -> actionsFor(RequestKind.Text, null)
        else -> categoryId.removePrefix("$CategoryPrefix.options.").toIntOrNull()
            ?.let { count -> List(count) { NotificationAction("$OptionPrefix$it", optionName(it)) } }
            .orEmpty()
    }

    /** What each numbered button means, for alert text shown beside the numbered buttons. */
    fun optionsLegend(kind: RequestKind, options: List<String>?): String? =
        actionsFor(kind, options)
            .takeIf { kind == RequestKind.Buttons || kind == RequestKind.Choice }
            ?.takeIf { it.isNotEmpty() }
            ?.mapIndexed { index, action -> "${optionName(index)}: ${action.title}" }
            ?.joinToString("\n")

    private fun optionName(index: Int) = "Option ${index + 1}"

    private const val CategoryPrefix = "REQUEST"
}
