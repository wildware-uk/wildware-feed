package dev.wildware.agents.app.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.wildware.agents.BoardScope
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlin.math.roundToInt

/*
 * The board: the work in the channels the sidebar has selected, a column per work state. A card
 * moves by dragging it to another column (mouse), from its menu ("Move to…", and a long press on a
 * phone), or with Ctrl+Shift+←/→ (⌘ on a Mac). Each move is the same work-state change the feed
 * makes, so agents hear it as they always have. Done cards can be taken off the board; the team's
 * setting takes them off by age too.
 */

/** "Feed | Board", at the top of the main pane. */
@Composable
fun ViewToggle(ui: FeedUi, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Row(
        modifier.clip(shape).border(1.dp, colors.border, shape).semantics { paneTitle = "View" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, board) in listOf("Feed" to false, "Board" to true)) {
            val chosen = ui.boardOpen == board
            Box(
                Modifier
                    .semantics { selected = chosen }
                    .pressable(label, shape, role = Role.Tab, labelIsShown = true) { if (!chosen) ui.toggleBoard() }
                    .background(if (chosen) colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.08f) else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (chosen) colors.text else colors.textMuted, style = AgentTheme.type.label, maxLines = 1)
            }
        }
    }
}

/** What is being dragged on the board, and where: in the board's own coordinates. */
@Stable
internal class BoardDrag {
    var card by mutableStateOf<BoardCard?>(null)
    /** Where the pointer is, in the root's coordinates, and where on the card it took hold. */
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var width by mutableStateOf(0f)
    var over by mutableStateOf<WorkStatus?>(null)
    val columns = mutableStateMapOf<WorkStatus, Rect>()
    var origin by mutableStateOf(Offset.Zero)

    fun columnAt(point: Offset): WorkStatus? = columns.entries.firstOrNull { it.value.contains(point) }?.key
}

/** The board in the main pane, for the sidebar's selection. */
@Composable
fun BoardPane(ui: FeedUi, modifier: Modifier = Modifier, phone: Boolean = false, top: @Composable () -> Unit = {}) {
    val state = ui.state
    val board = state.board
    val scope = ui.boardScope
    // The dashboard reads the board while it is on screen, and stops when it goes.
    LaunchedEffect(scope) { ui.emit(FeedEvent.ShowBoard(scope)) }
    DisposableEffect(Unit) { onDispose { ui.emit(FeedEvent.ShowBoard(null)) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!phone) runCatching { focus.requestFocus() } }
    val open: (BoardCard) -> Unit = { card -> ui.openBoardCard(card) }
    val drag = remember { BoardDrag() }
    Column(
        modifier
            .semantics { paneTitle = "Board" }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e -> boardKey(ui, e, open) },
    ) {
        top()
        BoardHeader(ui, board, scope, compact = phone)
        if (phone) PhoneColumns(ui, board, open) else DesktopColumns(ui, board, drag, open)
    }
    ui.boardMenu?.let { BoardCardMenu(ui, it, open) }
}

@Composable
private fun BoardHeader(ui: FeedUi, board: BoardState, scope: BoardScope, compact: Boolean) {
    val state = ui.state
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val group = (scope as? BoardScope.Group)?.let { s -> state.groups.firstOrNull { it.slug == s.slug } }
    val (over, title) = when (scope) {
        is BoardScope.Channel -> "BOARD  ·  CHANNEL" to state.current.name
        is BoardScope.Group -> "BOARD  ·  PROJECT" to (group?.name ?: scope.slug)
        BoardScope.Everything -> "BOARD  ·  EVERYTHING" to "All channels"
    }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 8.dp else 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetaText(over, style = type.overline)
                Text(
                    title,
                    Modifier.semantics { heading() },
                    color = colors.text,
                    style = if (compact) type.title else type.display,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ViewToggle(ui, Modifier.padding(start = 12.dp))
            Box {
                IconAction(Icons.More, "Board options", Modifier.padding(start = 6.dp)) { menuOpen = !menuOpen }
                if (menuOpen) BoardOptions(ui, board) { menuOpen = false }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The scope follows the sidebar; a project can be chosen here to see all of its channels.
            val currentGroup = state.current.groupId?.let { id -> state.groups.firstOrNull { it.id == id } }
            if (state.current.slug != null) {
                Chip("# ${state.current.name}", selected = scope is BoardScope.Channel) { ui.boardGroup = null }
                currentGroup?.let { g -> Chip(g.name, selected = scope is BoardScope.Group) { ui.boardGroup = g.id } }
            } else {
                Chip("Everything", selected = scope is BoardScope.Everything) { ui.boardGroup = null }
                state.groups.filter { !it.archived }.forEach { g -> Chip(g.name, selected = group?.id == g.id) { ui.boardGroup = g.id } }
            }
            if (board.showRemoved) Chip("Showing removed", selected = true) { ui.emit(FeedEvent.BoardShowRemoved(false)) }
        }
        board.problem?.let { MetaText(it, Modifier.padding(top = 8.dp), color = colors.error) }
    }
}

/** The ⋯ menu: when finished cards leave the board (admins), and whether removed ones show. */
@Composable
private fun BoardOptions(ui: FeedUi, board: BoardState, close: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val team = ui.state.team
    val mayChange = team.canManage || !team.supported
    Column(
        Modifier
            .zIndex(10f)
            .offset(x = (-220).dp, y = 40.dp)
            .width(260.dp)
            .lift(AgentTheme.elevation.overlay, shape)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.borderStrong, shape)
            .semantics { paneTitle = "Board options" }
            .padding(vertical = 6.dp),
    ) {
        Text("Finished cards leave the board", Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }, color = colors.text, style = AgentTheme.type.label)
        BoardAutoRemoveChoices.forEach { days ->
            val chosen = board.autoRemoveDays == days
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { selected = chosen }
                    .then(
                        if (mayChange) Modifier.pressable(autoRemoveLabel(days), AgentTheme.shapes.small, role = Role.RadioButton, labelIsShown = true) {
                            ui.emit(FeedEvent.BoardAutoRemove(days))
                            close()
                        } else Modifier.alpha(0.5f),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(autoRemoveLabel(days), Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
                if (chosen) Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
        if (!mayChange) MetaText("An owner or admin can change this.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(if (board.showRemoved) "Hide removed" else "Show removed", AgentTheme.shapes.small, labelIsShown = true) {
                    ui.emit(FeedEvent.BoardShowRemoved(!board.showRemoved))
                    close()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(if (board.showRemoved) "Hide removed" else "Show removed", color = colors.text, style = AgentTheme.type.body)
        }
    }
}

@Composable
private fun DesktopColumns(ui: FeedUi, board: BoardState, drag: BoardDrag, open: (BoardCard) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { drag.origin = it.positionInRoot() },
    ) {
        // All four columns fit beside the sidebar and the agents rail at 1280 wide.
        val gap = 10.dp
        val side = 20.dp
        val fit = (maxWidth - side * 2 - gap * 3) / 4
        val width = if (fit < ColumnMin) ColumnMin else fit
        val scroll = rememberScrollState()
        Row(
            Modifier.fillMaxSize().then(if (fit < ColumnMin) Modifier.horizontalScroll(scroll) else Modifier).padding(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            board.columns.forEach { column ->
                BoardColumnView(ui, board, column, Modifier.width(width).fillMaxHeight(), drag, open)
            }
        }
        // The card being dragged, under the pointer, above every column.
        drag.card?.let { card ->
            val at = drag.pointer - drag.origin - drag.grab
            Box(
                Modifier
                    .zIndex(5f)
                    .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .width(with(density) { drag.width.toDp() })
                    .lift(AgentTheme.elevation.overlay, AgentTheme.shapes.medium),
            ) {
                BoardCardFace(card, multi = ui.boardScope !is BoardScope.Channel, unread = 0, focused = false, lifted = true)
            }
        }
    }
}

@Composable
private fun BoardColumnView(ui: FeedUi, board: BoardState, column: BoardColumn, modifier: Modifier, drag: BoardDrag?, open: (BoardCard) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val target = drag?.card != null && drag.over == column.status && boardColumnOf(drag.card!!.status) != column.status
    val multi = ui.boardScope !is BoardScope.Channel
    Column(
        modifier
            .then(if (drag != null) Modifier.onGloballyPositioned { drag.columns[column.status] = it.boundsInRoot() } else Modifier)
            .clip(shape)
            .background(if (target) colors.accent.copy(alpha = 0.08f) else colors.surfaceSunken)
            .border(1.dp, if (target) colors.accent else Color.Transparent, shape)
            .semantics { testTag = "board-column-${column.status.wire}" },
    ) {
        if (drag != null) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(AgentTheme.shapes.pill).background(colors.status(column.status)))
                Text(
                    column.status.label,
                    Modifier.padding(start = 9.dp).weight(1f).semantics { heading() },
                    color = colors.text,
                    style = AgentTheme.type.label,
                )
                MetaText(column.cards.size.toString() + if (column.more) "+" else "")
            }
        }
        if (column.cards.isEmpty()) {
            MetaText(if (board.loaded) "Nothing here" else "Loading…", Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(column.cards, key = { it.id }) { card ->
                BoardCardItem(ui, card, multi, drag, open, Modifier.animateItem())
            }
            if (column.more) item(key = "more") {
                // Reaching the end of what is read reads the next page.
                LaunchedEffect(column.cards.size) { ui.emit(FeedEvent.BoardMore(column.status)) }
                MetaText("Loading more…", Modifier.padding(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCardItem(ui: FeedUi, card: BoardCard, multi: Boolean, drag: BoardDrag?, open: (BoardCard) -> Unit, modifier: Modifier = Modifier) {
    val unread = remember(card.id) { derivedStateOf { (ui.state.item(card.id) as? Threaded)?.unreadReplies ?: 0 } }.value
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragging = drag?.card?.id == card.id
    val moves = if (card.canMove) BoardStatuses.filter { it != boardColumnOf(card.status) } else emptyList()
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .alpha(if (dragging) 0.35f else if (card.removed) 0.6f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = card.spoken + if (unread > 0) ", $unread unread" else ""
                testTag = "board-card-${card.id}"
                customActions = moves.map { s ->
                    CustomAccessibilityAction("Move to ${s.label}") { ui.emit(FeedEvent.BoardMove(card.kind, card.id, s)); true }
                } + listOfNotNull(
                    CustomAccessibilityAction(if (card.removed) "Put back on the board" else "Remove from board") {
                        ui.emit(FeedEvent.BoardRemove(card.kind, card.id, !card.removed)); true
                    }.takeIf { card.canMove && boardColumnOf(card.status) == WorkStatus.Done },
                )
            }
            .combinedClickable(
                onClickLabel = "Open",
                onLongClickLabel = "Move to…",
                onLongClick = { ui.boardMenu = card },
                onClick = {
                    ui.boardFocus = card.id
                    open(card)
                },
            )
            .then(
                if (drag != null && card.canMove) Modifier.pointerInput(card.id, card.status) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val c = coords ?: return@detectDragGestures
                            drag.grab = at
                            drag.width = c.size.width.toFloat()
                            drag.pointer = c.positionInRoot() + at
                            drag.card = card
                            drag.over = boardColumnOf(card.status)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.pointer += amount
                            drag.over = drag.columnAt(drag.pointer)
                        },
                        onDragEnd = {
                            val to = drag.over
                            drag.card = null
                            if (to != null && to != boardColumnOf(card.status)) ui.emit(FeedEvent.BoardMove(card.kind, card.id, to))
                        },
                        onDragCancel = { drag.card = null },
                    )
                } else Modifier,
            ),
    ) {
        BoardCardFace(card, multi, unread, focused = ui.boardFocus == card.id, onMenu = { ui.boardMenu = card }, onRemove = { removed ->
            ui.emit(FeedEvent.BoardRemove(card.kind, card.id, removed))
        })
    }
}

/** One card's face: what it is, where, who is on it, for how long, and what waits in it. */
@Composable
private fun BoardCardFace(
    card: BoardCard,
    multi: Boolean,
    unread: Int,
    focused: Boolean,
    lifted: Boolean = false,
    onMenu: (() -> Unit)? = null,
    onRemove: ((Boolean) -> Unit)? = null,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.focus else if (lifted) colors.accent else colors.border, shape)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(card.title, Modifier.weight(1f).padding(top = 2.dp), color = colors.text, style = AgentTheme.type.bodyStrong, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (onMenu != null) IconAction(Icons.More, "Card options", iconSize = 16.dp, onClick = onMenu)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (multi && card.channel != null) MetaText("# ${card.channel}", Modifier.widthIn(max = 120.dp), color = colors.textMuted)
            card.who?.let { who ->
                MetaText(if (card.whoMore > 0) "$who +${card.whoMore}" else who, Modifier.weight(1f, fill = false), color = colors.textMuted)
            }
            Spacer(Modifier.weight(1f))
            if (card.status == WorkStatus.Cancelled) MetaText("Cancelled")
            WaitingBadge(card.waiting)
            UnreadBadge(unread)
            MetaText(card.age)
        }
        if (onRemove != null && card.canMove && boardColumnOf(card.status) == WorkStatus.Done) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (card.removed) MetaText(if (card.removedByAge) "Left the board by age" else "Removed from the board", Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                val label = if (card.removed) "Put back" else "Remove from board"
                Text(
                    label,
                    Modifier.clip(AgentTheme.shapes.small).pressable(label, AgentTheme.shapes.small, labelIsShown = true) { onRemove(!card.removed) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = colors.textMuted,
                    style = AgentTheme.type.meta,
                )
            }
        }
    }
}

/** A phone: one column at a time, chosen by its tab. Cards move from their menu (or a long press). */
@Composable
private fun PhoneColumns(ui: FeedUi, board: BoardState, open: (BoardCard) -> Unit) {
    var showing by remember { mutableStateOf(WorkStatus.InProgress) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        board.columns.forEach { c -> Chip(c.status.label, selected = showing == c.status, count = c.cards.size) { showing = c.status } }
    }
    BoardColumnView(ui, board, board.column(showing), Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp), drag = null, open = open)
}

/** A card's menu: open it, move it to another column, or take a finished one off the board. */
@Composable
private fun BoardCardMenu(ui: FeedUi, card: BoardCard, open: (BoardCard) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val close = { ui.boardMenu = null }
    Box(Modifier.fillMaxSize().zIndex(20f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(16.dp)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Card options" }
                .padding(vertical = 6.dp),
        ) {
            Text(card.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }, color = colors.text, style = AgentTheme.type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MenuRow("Open") { close(); open(card) }
            if (card.canMove) {
                BoardStatuses.filter { it != boardColumnOf(card.status) }.forEach { s ->
                    MenuRow("Move to ${s.label}", dot = colors.status(s)) {
                        close()
                        ui.emit(FeedEvent.BoardMove(card.kind, card.id, s))
                    }
                }
                if (boardColumnOf(card.status) == WorkStatus.Done) {
                    MenuRow(if (card.removed) "Put back on the board" else "Remove from board") {
                        close()
                        ui.emit(FeedEvent.BoardRemove(card.kind, card.id, !card.removed))
                    }
                }
            } else {
                MetaText("You can read this channel but not change it.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, dot: Color? = null, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(label, AgentTheme.shapes.small, labelIsShown = true, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.padding(end = 12.dp).size(9.dp).clip(AgentTheme.shapes.pill).background(dot))
        Text(label, color = colors.text, style = AgentTheme.type.body)
    }
}

/**
 * The board's keys: arrows move between cards (↑↓ in a column, ←→ across), Ctrl+Shift+←/→ (⌘⇧ on
 * a Mac) moves the card a column, Enter opens it, Delete takes a finished one off the board.
 */
internal fun boardKey(ui: FeedUi, e: KeyEvent, open: (BoardCard) -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val board = ui.state.board
    val columns = board.columns
    val current = ui.boardFocus?.let { board.card(it) }
    val col = current?.let { c -> columns.indexOfFirst { it.status == boardColumnOf(c.status) } } ?: -1
    val row = current?.let { c -> columns.getOrNull(col)?.cards?.indexOfFirst { it.id == c.id } } ?: -1
    fun focus(c: Int, r: Int): Boolean {
        val cards = columns.getOrNull(c)?.cards ?: return false
        if (cards.isEmpty()) return false
        ui.boardFocus = cards[r.coerceIn(0, cards.size - 1)].id
        return true
    }
    fun firstFrom(start: Int, step: Int): Boolean {
        var c = start
        while (c in columns.indices) {
            if (focus(c, row.coerceAtLeast(0))) return true
            c += step
        }
        return false
    }
    val command = e.isCtrlPressed || e.isMetaPressed
    if (current == null) {
        return when (e.key) {
            Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight -> firstFrom(0, 1)
            else -> false
        }
    }
    if (command && e.isShiftPressed && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
        val to = columns.getOrNull(col + if (e.key == Key.DirectionLeft) -1 else 1)?.status ?: return true
        if (current.canMove) ui.emit(FeedEvent.BoardMove(current.kind, current.id, to))
        return true
    }
    if (command) return false
    return when (e.key) {
        Key.DirectionDown -> focus(col, row + 1) || true
        Key.DirectionUp -> focus(col, row - 1) || true
        Key.DirectionLeft -> firstFrom(col - 1, -1) || true
        Key.DirectionRight -> firstFrom(col + 1, 1) || true
        Key.Enter, Key.NumPadEnter -> run { open(current); true }
        Key.Delete, Key.Backspace -> {
            if (current.canMove && boardColumnOf(current.status) == WorkStatus.Done && !current.removed) {
                ui.emit(FeedEvent.BoardRemove(current.kind, current.id, true))
            }
            true
        }
        else -> false
    }
}

/** Narrower than this, the columns scroll sideways rather than squeeze. */
private val ColumnMin = 168.dp
