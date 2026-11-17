package dev.wildware.agents

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One answer button in the Inbox. [actionId] is what [DashboardClient.answerFromAction] takes. */
data class InboxChoice(val actionId: String, val title: String, val isDestructive: Boolean = false)

/** What the full view of a question offers to answer it with. */
enum class InboxControl {
    /** One button per answer: Yes and No, or each option. */
    Choices,

    /** A text field, prefilled with the agent's suggested answer. */
    Text,

    /** Too much for a phone screen: several picks or a form. The dashboard page answers it. */
    Dashboard,
}

/** The decisions behind the Inbox: which questions show, in what order, and how each is answered. */
object Inbox {
    /** Under this, the time left shows as urgent. */
    const val UrgentSeconds = 5 * 60L

    /** What a typed answer is sent as. */
    const val TextActionId = RequestActions.Reply

    /** Questions still open at [nowMillis], the one closing soonest first. Ties keep the server's order, oldest first. */
    fun pending(requests: List<PendingRequest>, nowMillis: Long): List<PendingRequest> =
        requests
            .map { it to expiresMillis(it.expiresAt) }
            .filter { (_, expires) -> expires == null || expires > nowMillis }
            .sortedBy { (_, expires) -> expires ?: Long.MAX_VALUE }
            .map { (request, _) -> request }

    /** Seconds until [expiresAt], never below zero. Null when the time can't be read. */
    fun secondsLeft(expiresAt: String, nowMillis: Long): Long? =
        expiresMillis(expiresAt)?.let { maxOf(0L, (it - nowMillis) / 1000) }

    fun isUrgent(expiresAt: String, nowMillis: Long): Boolean =
        secondsLeft(expiresAt, nowMillis)?.let { it < UrgentSeconds } ?: false

    /** "12 min left", "1 hour 5 min left", "3 hours left". Null when the time can't be read. */
    fun timeLeft(expiresAt: String, nowMillis: Long): String? {
        val seconds = secondsLeft(expiresAt, nowMillis) ?: return null
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds <= 0 -> "Expired"
            minutes < 1 -> "Under a minute left"
            minutes < 60 -> "$minutes min left"
            hours < 2 -> if (minutes % 60 == 0L) "1 hour left" else "1 hour ${minutes % 60} min left"
            hours < 24 -> "$hours hours left"
            days == 1L -> "1 day left"
            else -> "$days days left"
        }
    }

    fun control(kind: RequestKind): InboxControl = when (kind) {
        RequestKind.Confirm, RequestKind.Buttons, RequestKind.Choice -> InboxControl.Choices
        RequestKind.Text -> InboxControl.Text
        RequestKind.MultiChoice, RequestKind.Form -> InboxControl.Dashboard
    }

    /** Swiping a row answers it outright, so only a yes-or-no question allows it. */
    fun canSwipe(request: PendingRequest): Boolean = request.kind == RequestKind.Confirm

    /**
     * The answer buttons for the full view. Unlike the lock screen there is room for every option,
     * so a question with many is still answered here rather than sent to the dashboard.
     */
    fun choices(request: PendingRequest): List<InboxChoice> = when (request.kind) {
        RequestKind.Confirm -> listOf(
            InboxChoice(RequestActions.Yes, "Yes"),
            InboxChoice(RequestActions.No, "No", isDestructive = true),
        )
        RequestKind.Buttons, RequestKind.Choice ->
            request.options.orEmpty().mapIndexed { index, option -> InboxChoice(RequestActions.optionActionId(index), option) }
        RequestKind.Text, RequestKind.MultiChoice, RequestKind.Form -> emptyList()
    }

    /** The dashboard page that shows this question, to answer it there. */
    fun dashboardPath(request: PendingRequest): String =
        request.projectSlug?.let { "/projects/$it?focus=${request.id}" } ?: "/"

    /** The badge count: how many questions are still open. */
    fun badge(requests: List<PendingRequest>, nowMillis: Long): Int = pending(requests, nowMillis).size

    @OptIn(ExperimentalTime::class)
    private fun expiresMillis(expiresAt: String): Long? =
        runCatching { Instant.parse(expiresAt).toEpochMilliseconds() }.getOrNull()
}
