package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable
import dev.wildware.agents.BoardScope

/*
 * What the board draws: the cards marked as work in the channels the sidebar has selected, a column
 * per work state. Mapped from the dashboard's `GET /api/board` in `engine/BoardWatch.kt`.
 */

/** The board's columns, in order. Cancelled cards sit in Done, saying so. */
val BoardStatuses: List<WorkStatus> = listOf(WorkStatus.Todo, WorkStatus.InProgress, WorkStatus.Blocked, WorkStatus.Done)

/** The column a card in [status] sits in. */
fun boardColumnOf(status: WorkStatus): WorkStatus = if (status == WorkStatus.Cancelled) WorkStatus.Done else status

/** How many days a finished card can stay on the board, as the ⋯ menu offers them; null is never. */
val BoardAutoRemoveChoices: List<Int?> = listOf(1, 3, 7, 30, null)

/** The words for an auto-remove age: "After 7 days", "Never". */
fun autoRemoveLabel(days: Int?): String = when (days) {
    null -> "Never"
    1 -> "After 1 day"
    else -> "After $days days"
}

/** One card on the board. */
@Immutable
data class BoardCard(
    /** "update" or "message". */
    val kind: String,
    val id: String,
    val title: String,
    val status: WorkStatus,
    /** Its channel's name and slug, shown as a chip when the board spans channels. */
    val channel: String?,
    val channelSlug: String?,
    val channelHue: Int = 0,
    /** Who is on it, by name ("scout", "Sam"), and how many more there are. */
    val who: String?,
    val whoIsAgent: Boolean = true,
    val whoMore: Int = 0,
    /** How long it has been in its column: "3h", "2d". */
    val age: String,
    /** Questions waiting in its thread. */
    val waiting: Int = 0,
    val replies: Int = 0,
    /** Off the board: shown only with "Show removed", with Restore. [removedByAge] when it left by itself. */
    val removed: Boolean = false,
    val removedByAge: Boolean = false,
    /** The viewer may change its work state. */
    val canMove: Boolean = true,
    /** Who it is assigned to, oldest first (server migration 073). */
    val assignees: List<Assignee> = emptyList(),
) {
    /** What a screen reader says for it. */
    val spoken: String
        get() = listOfNotNull(
            title,
            if (status == WorkStatus.Cancelled) "cancelled" else null,
            channel?.let { "in $it" },
            who?.let { if (whoMore > 0) "$it and $whoMore more on it" else "$it on it" },
            assignees.takeIf { it.isNotEmpty() }?.let { a -> "assigned to " + a.joinToString(", ") { it.name } },
            "$age in ${boardColumnOf(status).label}",
            "$waiting waiting".takeIf { waiting > 0 },
            "removed from the board".takeIf { removed },
            "read only".takeIf { !canMove },
        ).joinToString(", ")
}

/** One column: its cards, newest move first, and whether there are more to read. */
@Immutable
data class BoardColumn(
    val status: WorkStatus,
    val cards: List<BoardCard> = emptyList(),
    val more: Boolean = false,
    val loadingMore: Boolean = false,
)

/**
 * The board, as the dashboard last answered it for [scope]. [loaded] once the first read is in;
 * [problem] says why the last read or change didn't work. [autoRemoveDays] is the team's; null is never.
 */
@Immutable
data class BoardState(
    val scope: BoardScope? = null,
    val columns: List<BoardColumn> = BoardStatuses.map { BoardColumn(it) },
    val autoRemoveDays: Int? = 7,
    val showRemoved: Boolean = false,
    /** Which cards show by who they are assigned to. */
    val assigned: AssignedFilter = AssignedFilter.All,
    /** Everyone seen assigned on this board, for its filter chips. */
    val assignees: List<Assignee> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val problem: String? = null,
) {
    fun column(status: WorkStatus): BoardColumn = columns.firstOrNull { it.status == boardColumnOf(status) } ?: BoardColumn(boardColumnOf(status))

    fun card(id: String): BoardCard? = columns.firstNotNullOfOrNull { c -> c.cards.firstOrNull { it.id == id } }

    val isEmpty: Boolean get() = columns.all { it.cards.isEmpty() }
}

/**
 * Where the board looks, from what the sidebar has selected: a channel, or everything. [group] is a
 * project (group of channels) picked on the board itself, which wins while it holds the channel.
 */
fun boardScopeFor(channelSlug: String?, group: ProjectGroup?): BoardScope = when {
    group != null -> BoardScope.Group(group.slug)
    channelSlug != null -> BoardScope.Channel(channelSlug)
    else -> BoardScope.Everything
}
