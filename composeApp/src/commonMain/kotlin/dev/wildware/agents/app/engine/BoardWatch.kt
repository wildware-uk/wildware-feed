package dev.wildware.agents.app.engine

import dev.wildware.agents.BoardCardView
import dev.wildware.agents.BoardScope
import dev.wildware.agents.BoardSnapshot
import dev.wildware.agents.CardRef
import dev.wildware.agents.DashboardClient
import dev.wildware.agents.DashboardException
import dev.wildware.agents.OwnerActions
import dev.wildware.agents.app.feed.BoardCard
import dev.wildware.agents.app.feed.BoardColumn
import dev.wildware.agents.app.feed.BoardState
import dev.wildware.agents.app.feed.BoardStatuses
import dev.wildware.agents.app.feed.WorkStatus
import dev.wildware.agents.app.feed.boardColumnOf
import dev.wildware.agents.board
import dev.wildware.agents.setBoardAutoRemove
import dev.wildware.agents.setBoardRemoved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * The board while it is on screen: read for its scope, read again whenever the live stream says
 * something that can move a card happened ([changes]), and changed by the owner. A move goes through
 * the outbox's work-state change, as the feed's does, so agents hear it as they always have; it
 * shows in its new column at once and the next read confirms it, or puts it back.
 */
class BoardWatch(
    private val client: DashboardClient,
    private val actions: OwnerActions,
    private val scope: CoroutineScope,
    private val changes: Flow<Unit>,
    private val now: () -> Long,
) {
    private val mutable = MutableStateFlow(BoardState())
    val state: StateFlow<BoardState> = mutable.asStateFlow()

    private var live: Job? = null

    /** Moves still on their way, by card id: shown in their new column until a read agrees. */
    private val moving = HashMap<String, WorkStatus>()
    private val movedAt = HashMap<String, Long>()

    /** Cards removed or put back here and not yet confirmed. */
    private val removing = HashMap<String, Boolean>()

    /** Show the board for [scope], or (null) stop reading it: it is off screen. */
    @OptIn(FlowPreview::class)
    fun show(scope: BoardScope?) {
        if (scope == null) {
            live?.cancel()
            live = null
            return
        }
        val same = scope == mutable.value.scope && live != null
        if (same) return
        live?.cancel()
        if (scope != mutable.value.scope) {
            // Another scope's cards never show under this one, not even for a frame.
            first = emptyMap()
            extra.clear()
            afters.clear()
            more.clear()
            mutable.value = BoardState(scope = scope, showRemoved = mutable.value.showRemoved, autoRemoveDays = mutable.value.autoRemoveDays)
        }
        live = this.scope.launch {
            load()
            changes.debounce(ReReadMillis).collect { load() }
        }
    }

    fun showRemoved(on: Boolean) {
        if (mutable.value.showRemoved == on) return
        mutable.value = mutable.value.copy(showRemoved = on)
        scope.launch { load() }
    }

    /** Moves a card to [status]'s column, through the same work-state change the feed makes. */
    fun move(kind: String, id: String, status: WorkStatus) {
        val card = mutable.value.card(id) ?: return
        if (!card.canMove || card.status == status) return
        moving[id] = status
        movedAt[id] = now()
        publish(mutable.value)
        actions.setStatus(CardRef(kind, id), status.wire)
    }

    /** Takes a finished card off the board for everyone, or ([removed] false) puts it back. */
    fun remove(kind: String, id: String, removed: Boolean) {
        removing[id] = removed
        publish(mutable.value)
        scope.launch {
            try {
                client.setBoardRemoved(CardRef(kind, id), removed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                removing.remove(id)
                mutable.value = mutable.value.copy(problem = problemOf(failed, if (removed) "remove it" else "put it back"))
            }
            load()
        }
    }

    fun setAutoRemove(days: Int?) {
        val before = mutable.value.autoRemoveDays
        mutable.value = mutable.value.copy(autoRemoveDays = days)
        scope.launch {
            try {
                client.setBoardAutoRemove(days)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutable.value = mutable.value.copy(autoRemoveDays = before, problem = problemOf(failed, "change when finished cards leave"))
            }
            load()
        }
    }

    /** Reads the next page of one column. */
    fun more(status: WorkStatus) {
        val board = mutable.value
        val boardScope = board.scope ?: return
        val column = board.column(status)
        if (!column.more || column.loadingMore) return
        val after = afters[column.status] ?: return
        mutable.value = board.copy(columns = board.columns.map { if (it.status == column.status) it.copy(loadingMore = true) else it })
        scope.launch {
            try {
                val page = client.board(boardScope, removed = board.showRemoved, column = column.status.wire, after = after, limit = Page)
                val read = page.columns.firstOrNull() ?: return@launch
                afters[column.status] = read.after
                extra[column.status] = extra[column.status].orEmpty() + read.cards
                more[column.status] = read.more
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutable.value = mutable.value.copy(problem = problemOf(failed, "read more of the board"))
            }
            publish(mutable.value, loadingMore = false)
        }
    }

    // The last read, column by column, with the pages read after it.
    private var first: Map<WorkStatus, List<BoardCardView>> = emptyMap()
    private val extra = HashMap<WorkStatus, List<BoardCardView>>()
    private val afters = HashMap<WorkStatus, String?>()
    private val more = HashMap<WorkStatus, Boolean>()

    private suspend fun load() {
        val boardScope = mutable.value.scope ?: return
        val showRemoved = mutable.value.showRemoved
        // A re-read covers as many cards as are showing, so a page read further down stays.
        val showing = BoardStatuses.maxOf { (first[it]?.size ?: 0) + (extra[it]?.size ?: 0) }.coerceIn(Page, MaxRead)
        mutable.value = mutable.value.copy(loading = true)
        val read: BoardSnapshot = try {
            client.board(boardScope, removed = showRemoved, limit = showing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            mutable.value = mutable.value.copy(loading = false, loaded = true, problem = problemOf(failed, "read the board"))
            return
        }
        if (mutable.value.scope != boardScope) return
        first = read.columns.associate { statusOf(it.status) to it.cards }
        extra.clear()
        read.columns.forEach { c -> afters[statusOf(c.status)] = c.after; more[statusOf(c.status)] = c.more }
        // What the dashboard now says settles what was on its way.
        val all = read.columns.flatMap { it.cards }.associateBy { it.card.id }
        // One the dashboard never took (the outbox gave up on it) stops pretending after a while.
        moving.entries.removeAll { (id, status) -> all[id]?.let { statusOf(it.status) == status } ?: true || now() - (movedAt[id] ?: 0) > GiveUpMillis }
        movedAt.keys.retainAll(moving.keys)
        removing.entries.removeAll { (id, removed) -> all[id]?.removed == removed || (removed && all[id] == null) }
        publish(mutable.value.copy(autoRemoveDays = read.autoRemoveDays, loaded = true, loading = false, problem = null))
    }

    /** The board as read, with the owner's moves and removals still on their way laid over it. */
    private fun publish(base: BoardState, loadingMore: Boolean? = null) {
        val cards = BoardStatuses.flatMap { s -> first[s].orEmpty() + extra[s].orEmpty() }.distinctBy { it.card.id }.map(::cardOf)
        val laid = cards.mapNotNull { card ->
            val moved = moving[card.id]?.let { card.copy(status = it) } ?: card
            when (removing[card.id]) {
                true -> if (base.showRemoved) moved.copy(removed = true, removedByAge = false) else null
                false -> moved.copy(removed = false, removedByAge = false)
                null -> moved
            }
        }
        val columns = BoardStatuses.map { status ->
            val inColumn = laid.filter { boardColumnOf(it.status) == status }
            // A card just moved here goes to the top, where the next read puts it too.
            val (arrived, rest) = inColumn.partition { moving[it.id] != null }
            val old = base.columns.firstOrNull { it.status == status }
            BoardColumn(status, arrived + rest, more = more[status] == true, loadingMore = loadingMore ?: old?.loadingMore ?: false)
        }
        mutable.value = base.copy(columns = columns)
    }

    private fun cardOf(v: BoardCardView): BoardCard {
        val who = v.on.firstOrNull()
        return BoardCard(
            kind = v.card.kind, id = v.card.id, title = v.title.ifBlank { "Untitled" }, status = statusOf(v.status),
            channel = v.channel.name, channelSlug = v.channel.slug,
            who = who?.name?.ifBlank { null }, whoIsAgent = who?.kind != "person", whoMore = (v.on.size - 1).coerceAtLeast(0),
            age = boardAge(now() - (v.statusAt ?: v.createdAt)), waiting = v.waiting, replies = v.replies,
            removed = v.removed, removedByAge = v.removedBy == "age", canMove = v.canMove,
        )
    }

    private fun statusOf(wire: String): WorkStatus = WorkStatus.entries.firstOrNull { it.wire == wire } ?: WorkStatus.Todo

    private fun problemOf(failed: Throwable, what: String) =
        if (failed is DashboardException) "Couldn't $what. ${failed.message}" else "Couldn't $what. Check the connection and try again."

    private companion object {
        const val Page = 50
        const val MaxRead = 200

        /** A burst of live changes (an agent moving several cards) makes one read. */
        const val ReReadMillis = 250L

        /** How long a move waits for the dashboard to agree before the card goes back. */
        const val GiveUpMillis = 60_000L
    }
}

/** How long a card has been in its column, short: "now", "5m", "3h", "2d". */
internal fun boardAge(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}
