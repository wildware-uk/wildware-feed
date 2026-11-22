package dev.wildware.agents.app.feed

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min

/*
 * Arranging the channel list by hand: drag a channel into a project, between channels, or out to
 * "Channels" (in no project); drag a project above or below another. A mouse drags at once; a finger
 * holds first (a hold that lets go without moving opens the row's menu instead). Escape, or letting
 * go outside the list, puts it back. Every move is also a menu item ("Move to project…"), a screen
 * reader action, and Ctrl+Shift+Up / Down (⌘⇧ on a Mac) on the focused row.
 */

/** Whether this person may arrange the list: rename, move and delete channels and projects. */
val FeedState.mayArrange: Boolean get() = team.me?.role?.let { it != "member" } ?: true

/** What is being dragged. */
@Immutable
sealed interface DragThing {
    data class Channel(val id: String) : DragThing
    data class Group(val id: String) : DragThing
}

/** Where a drop would land. */
@Immutable
sealed interface DropSpot {
    /** A channel, into project [groupId] (null: none), at [index] among its channels, not counting itself. */
    data class Channel(val groupId: String?, val index: Int) : DropSpot

    /** A project, at [index] among the projects, not counting itself. */
    data class Group(val index: Int) : DropSpot
}

/** The rows a drag measures. */
internal sealed interface RowKey {
    data class Channel(val id: String) : RowKey
    data class Header(val groupId: String) : RowKey
    data class Empty(val groupId: String) : RowKey
    data object Loose : RowKey
}

/**
 * One drag in the channel list: what is held, where it would land, and what the list draws for it.
 * Rows report where they are as they are laid out; nothing here is read while composing but the
 * held thing and the project a channel is over, so a drag redraws the list without recomposing it.
 */
@Stable
class SidebarDrag {
    var thing by mutableStateOf<DragThing?>(null)
        private set
    var spot by mutableStateOf<DropSpot?>(null)
        private set

    /** How far the held row has moved from where it was picked up, in pixels. Read while drawing. */
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Where the drop line is, in the list's own coordinates; NaN for none. Read while drawing. */
    var lineY by mutableFloatStateOf(Float.NaN)
        private set
    var lineStart by mutableFloatStateOf(0f)
        private set

    /** The project whose heading a channel is held over: let go there, it goes in at the end. */
    var intoGroup by mutableStateOf<String?>(null)
        private set

    /** The pointer is near the top or bottom of the list, so the list scrolls each frame. */
    var nearEdge by mutableStateOf(false)
        private set
    private var edge = 0f

    internal var content: LayoutCoordinates? = null
    internal var viewport: LayoutCoordinates? = null
    internal val rows = HashMap<RowKey, LayoutCoordinates>()

    /** The pointer, in window coordinates. */
    internal var pointer = Offset.Zero
        private set
    private var startY = 0f
    private var sections: ChannelSections? = null
    private var folded: (String) -> Boolean = { false }
    private var slack = 0f

    internal fun start(held: DragThing, at: Offset, sections: ChannelSections, folded: (String) -> Boolean, slack: Float, edge: Float) {
        this.sections = sections
        this.folded = folded
        this.slack = slack
        this.edge = edge
        pointer = at
        startY = content?.takeIf { it.isAttached }?.windowToLocal(at)?.y ?: 0f
        offsetY = 0f
        thing = held
        update()
    }

    internal fun move(at: Offset) {
        if (thing == null) return
        pointer = at
        update()
    }

    /** What was held and where it lands, or null when it lands nowhere; the drag is over either way. */
    internal fun end(): Pair<DragThing, DropSpot>? {
        val held = thing
        val at = spot
        clear()
        return if (held != null && at != null) held to at else null
    }

    fun cancel() = clear()

    private fun clear() {
        thing = null
        spot = null
        offsetY = 0f
        lineY = Float.NaN
        intoGroup = null
        nearEdge = false
    }

    /** Works out where a drop would land, from the pointer and where each row is now. */
    internal fun update() {
        val held = thing ?: return
        val list = content?.takeIf { it.isAttached } ?: return
        val local = list.windowToLocal(pointer)
        offsetY = local.y - startY
        val bounds = viewport?.takeIf { it.isAttached }?.boundsInWindow()
        nearEdge = bounds != null && (pointer.y < bounds.top + edge || pointer.y > bounds.bottom - edge)
        if (bounds != null && (pointer.x < bounds.left - slack || pointer.x > bounds.right + slack)) {
            // Off the list: letting go here puts it back.
            spot = null
            lineY = Float.NaN
            intoGroup = null
            return
        }
        when (held) {
            is DragThing.Channel -> channelSpot(list, local.y, held.id)
            is DragThing.Group -> groupSpot(list, local.y, held.id)
        }
    }

    private fun rect(list: LayoutCoordinates, key: RowKey): Rect? =
        rows[key]?.takeIf { it.isAttached }?.let { list.localBoundingBoxOf(it, clipBounds = false) }

    private class Candidate(val rect: Rect, val groupId: String?, val kind: Int, val index: Int)

    private fun channelSpot(list: LayoutCoordinates, y: Float, id: String) {
        val s = sections ?: return
        val candidates = ArrayList<Candidate>()
        fun section(groupId: String?, channels: List<Project>) {
            val others = channels.filter { it.id != id }
            others.forEachIndexed { i, c -> rect(list, RowKey.Channel(c.id))?.let { candidates += Candidate(it, groupId, ROW, i) } }
            if (groupId != null && others.isEmpty()) rect(list, RowKey.Empty(groupId))?.let { candidates += Candidate(it, groupId, HEADING, 0) }
        }
        for ((g, channels) in s.groups) {
            val count = channels.count { it.id != id }
            rect(list, RowKey.Header(g.id))?.let { candidates += Candidate(it, g.id, HEADING, count) }
            if (!folded(g.id)) section(g.id, channels)
        }
        rect(list, RowKey.Loose)?.let { candidates += Candidate(it, null, LOOSE, 0) }
        section(null, s.loose)
        val best = candidates.minByOrNull { distance(y, it.rect) } ?: return
        when (best.kind) {
            HEADING -> {
                spot = DropSpot.Channel(best.groupId, best.index)
                intoGroup = best.groupId
                lineY = Float.NaN
            }
            LOOSE -> {
                spot = DropSpot.Channel(null, 0)
                intoGroup = null
                lineY = best.rect.bottom
                lineStart = best.rect.left
            }
            else -> {
                val before = y < best.rect.center.y
                spot = DropSpot.Channel(best.groupId, if (before) best.index else best.index + 1)
                intoGroup = null
                lineY = if (before) best.rect.top else best.rect.bottom
                lineStart = best.rect.left
            }
        }
    }

    private fun groupSpot(list: LayoutCoordinates, y: Float, id: String) {
        val s = sections ?: return
        val blocks = s.groups.filter { it.first.id != id }.mapIndexedNotNull { i, (g, channels) ->
            val head = rect(list, RowKey.Header(g.id)) ?: return@mapIndexedNotNull null
            var bottom = head.bottom
            if (!folded(g.id)) {
                channels.forEach { c -> rect(list, RowKey.Channel(c.id))?.let { bottom = maxOf(bottom, it.bottom) } }
                rect(list, RowKey.Empty(g.id))?.let { bottom = maxOf(bottom, it.bottom) }
            }
            Candidate(Rect(head.left, head.top, head.right, bottom), g.id, ROW, i)
        }
        intoGroup = null
        val best = blocks.minByOrNull { distance(y, it.rect) }
        if (best == null) {
            spot = DropSpot.Group(0)
            lineY = Float.NaN
            return
        }
        val before = y < best.rect.center.y
        spot = DropSpot.Group(if (before) best.index else best.index + 1)
        lineY = if (before) best.rect.top else best.rect.bottom
        lineStart = best.rect.left
    }

    private fun distance(y: Float, r: Rect): Float = if (y in r.top..r.bottom) 0f else min(abs(y - r.top), abs(y - r.bottom))

    private companion object {
        const val ROW = 0
        const val HEADING = 1
        const val LOOSE = 2
    }
}

/** A channel's place a drop asked for: in project [groupId] (null: none), at [position]. */
@Immutable
data class Placed(val groupId: String?, val position: Long)

/**
 * Drops on their way to the dashboard, laid over what it last said, so a dropped row stays where it
 * was dropped instead of jumping back until the dashboard's answer comes. Each place goes once the
 * dashboard agrees with it, or a moment after the dashboard took it (someone else may have moved it
 * since), or at once when the dashboard refused it.
 */
@Stable
class PendingArrangement {
    private val channelPlaces = mutableStateMapOf<String, Placed>()
    private val groupPlaces = mutableStateMapOf<String, Long>()
    private val landed = HashSet<String>()

    /** Counts the drops the dashboard has taken, to clear them a moment later. */
    var landings by mutableIntStateOf(0)
        private set

    fun channels(server: List<Project>): List<Project> {
        if (channelPlaces.isEmpty()) return server
        val placed = server.map { p -> channelPlaces[p.id]?.let { p.copy(groupId = it.groupId, position = it.position) } ?: p }
        // The dashboard's own order: not arranged first, as it sent them, then each in its place.
        return placed.sortedWith(compareBy({ it.position != null }, { it.position ?: 0L }))
    }

    fun groups(server: List<ProjectGroup>): List<ProjectGroup> {
        if (groupPlaces.isEmpty()) return server
        val placed = server.map { g -> groupPlaces[g.id]?.let { g.copy(position = it) } ?: g }
        return placed.sortedWith(compareBy({ it.position != null }, { it.position ?: 0L }))
    }

    internal fun placeChannels(ids: List<String>, groupId: String?) = ids.forEachIndexed { i, id -> channelPlaces[id] = Placed(groupId, i.toLong()) }

    internal fun placeGroups(ids: List<String>) = ids.forEachIndexed { i, id -> groupPlaces[id] = i.toLong() }

    internal fun forget(ids: List<String>) = ids.forEach {
        channelPlaces.remove(it)
        groupPlaces.remove(it)
        landed.remove(it)
    }

    internal fun land(ids: List<String>) {
        landed += ids
        landings += 1
    }

    /** Drops the places the dashboard now agrees with. */
    internal fun settle(channels: List<Project>, groups: List<ProjectGroup>) {
        channels.forEach { p -> if (channelPlaces[p.id] == Placed(p.groupId, p.position ?: -1)) forget(listOf(p.id)) }
        groups.forEach { g -> if (groupPlaces[g.id] == g.position) forget(listOf(g.id)) }
    }

    /** Drops every place the dashboard took a while ago, whatever it says now. */
    internal fun dropLanded() = forget(landed.toList())
}

/** Puts [channels] in this order in project [groupId] (null: none), here at once and on the dashboard. */
internal fun FeedUi.arrangeChannels(channels: List<Project>, groupId: String?) {
    val ids = channels.map { it.id }
    arranging.placeChannels(ids, groupId)
    emit(FeedEvent.ArrangeChannels(channels.mapNotNull { it.slug }, groupId) { problem ->
        if (problem == null) arranging.land(ids) else {
            arranging.forget(ids)
            toast = "Couldn't move it: $problem"
        }
    })
}

/** Puts the projects [groups] in this order, here at once and on the dashboard. */
internal fun FeedUi.arrangeGroups(groups: List<ProjectGroup>) {
    val ids = groups.map { it.id }
    arranging.placeGroups(ids)
    emit(FeedEvent.ArrangeGroups(groups.map { it.slug }) { problem ->
        if (problem == null) arranging.land(ids) else {
            arranging.forget(ids)
            toast = "Couldn't move it: $problem"
        }
    })
}

/** Channel [p] into project [groupId] (null: none) at [index] among that project's other channels. */
internal fun FeedUi.placeChannel(p: Project, groupId: String?, index: Int) {
    val sections = channelSections
    val now = if (groupId == null) sections.loose else sections.groups.firstOrNull { it.first.id == groupId }?.second.orEmpty()
    val others = now.filter { it.id != p.id }
    val next = others.toMutableList().apply { add(index.coerceIn(0, size), p) }
    val inPlace = now.map { it.id } == next.map { it.id } && sections.loose.any { it.id == p.id } == (groupId == null)
    if (!inPlace) arrangeChannels(next, groupId)
}

/** Project [g] to [index] among the other projects. */
internal fun FeedUi.placeGroup(g: ProjectGroup, index: Int) {
    val now = channelSections.groups.map { it.first }
    val next = now.filter { it.id != g.id }.toMutableList().apply { add(index.coerceIn(0, size), g) }
    if (now.map { it.id } != next.map { it.id }) arrangeGroups(next)
}

/** Channel [p] one place up ([by] -1) or down (+1) within its project, from a key or a screen reader. */
internal fun FeedUi.nudgeChannel(p: Project, by: Int) {
    val sections = channelSections
    val (groupId, channels) = sections.groups.firstOrNull { (_, cs) -> cs.any { it.id == p.id } }?.let { it.first.id to it.second } ?: (null to sections.loose)
    val at = channels.indexOfFirst { it.id == p.id }
    if (at < 0 || at + by !in channels.indices) return
    placeChannel(p, groupId, at + by)
}

internal fun FeedUi.nudgeGroup(g: ProjectGroup, by: Int) {
    val groups = channelSections.groups.map { it.first }
    val at = groups.indexOfFirst { it.id == g.id }
    if (at < 0 || at + by !in groups.indices) return
    placeGroup(g, at + by)
}

/** Reports where a row is, for the drag to measure. */
internal fun Modifier.dragRow(drag: SidebarDrag, key: RowKey): Modifier = onGloballyPositioned { drag.rows[key] = it }

/** Marks the scrolling area the list is in: near its edges a drag scrolls it, and off its sides a drop is called off. */
fun Modifier.dragViewport(drag: SidebarDrag): Modifier = onGloballyPositioned { drag.viewport = it }

/**
 * The pointer and keys of one row in the channel list:
 * - right-click (mouse) or a hold that lets go without moving (touch) opens its menu,
 * - a mouse drag, or a hold then a drag (touch), picks it up when [canDrag],
 * - Shift+F10 or the Menu key opens the menu from the keyboard, Ctrl+Shift+Up / Down move it,
 * - and screen readers get each of those as an action.
 */
internal fun Modifier.sidebarGestures(
    label: String,
    canDrag: Boolean,
    openMenu: (Offset) -> Unit,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (dropped: Boolean) -> Unit,
    nudge: ((Int) -> Unit)?,
    focus: FocusRequester,
    extraActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val where = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val menu by rememberUpdatedState(openMenu)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    this
        .semantics {
            customActions = buildList {
                add(CustomAccessibilityAction("Actions for $label") { menu(Offset.Zero); true })
                if (nudge != null) {
                    add(CustomAccessibilityAction("Move $label up") { nudge(-1); true })
                    add(CustomAccessibilityAction("Move $label down") { nudge(1); true })
                }
                addAll(extraActions)
            }
        }
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when {
                e.key == Key.Menu || (e.key == Key.F10 && e.isShiftPressed) -> { menu(Offset.Zero); true }
                // Alt and an arrow already step through the channels, so moving one takes Ctrl (⌘) and Shift.
                nudge != null && (e.isCtrlPressed || e.isMetaPressed) && e.isShiftPressed && e.key == Key.DirectionUp -> { nudge(-1); true }
                nudge != null && (e.isCtrlPressed || e.isMetaPressed) && e.isShiftPressed && e.key == Key.DirectionDown -> { nudge(1); true }
                else -> false
            }
        }
        .onGloballyPositioned { where[0] = it }
        // Right-click, as the cards take it: a press with the secondary button nothing inside took.
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                        event.changes.forEach { it.consume() }
                        menu(event.changes.first().position)
                    }
                }
            }
        }
        .pointerInput(canDrag) {
            fun window(p: Offset) = where[0]?.takeIf { it.isAttached }?.localToWindow(p) ?: p
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                val touch = down.type != PointerType.Mouse
                if (touch) {
                    // A tap, or a scroll, is left to the row and the list: only a hold is ours. Whether
                    // it was a hold is read from the events' own times, never from a timer, so a tap
                    // always taps, however late this code runs; the timer only buzzes on time.
                    val heldAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                    var buzzed = false
                    while (true) {
                        val event = if (buzzed) awaitPointerEvent(PointerEventPass.Initial)
                        else withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent(PointerEventPass.Initial) }
                        if (event == null) {
                            buzzed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            continue
                        }
                        val c = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (c.isConsumed) return@awaitEachGesture
                        val held = c.uptimeMillis >= heldAt
                        val moved = (c.position - down.position).getDistance() > slop
                        if (!c.pressed) {
                            // Let go: a tap is the row's; after a hold, the menu, and the row isn't also tapped.
                            if (held && !moved) {
                                c.consume()
                                menu(down.position)
                            }
                            return@awaitEachGesture
                        }
                        if (!held) {
                            if (moved) return@awaitEachGesture
                            continue
                        }
                        // Held: what follows is ours.
                        c.consume()
                        if (moved && canDrag) break
                    }
                } else if (!canDrag || !currentEvent.buttons.isPrimaryPressed) {
                    return@awaitEachGesture
                }
                var dragging = false
                var dropped = false
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        // Held: the lift is ours, so the row doesn't also open as if tapped.
                        if (dragging || touch) c.consume()
                        dropped = dragging
                        break
                    }
                    if (!dragging && canDrag && (c.position - down.position).getDistance() > slop) {
                        dragging = true
                        runCatching { focus.requestFocus() }
                        start(window(down.position))
                    }
                    if (dragging) {
                        c.consume()
                        move(window(c.position))
                    } else if (touch) {
                        c.consume()
                    }
                }
                when {
                    dragging -> end(dropped)
                    touch -> menu(down.position)
                }
            }
        }
        .focusRequester(focus)
}

/** How the held row looks: lifted and following the pointer, above the rows it passes. */
@Composable
internal fun Modifier.heldRow(drag: SidebarDrag, held: Boolean): Modifier {
    if (!held) return this
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    return this
        .zIndex(1f)
        .graphicsLayer {
            translationY = drag.offsetY
            shadowElevation = 10.dp.toPx()
            this.shape = shape
            clip = true
        }
        .background(colors.surfaceRaised)
}

/** A project's heading while a channel is held over it: lit, as letting go there puts it in. */
@Composable
internal fun Modifier.dropTarget(lit: Boolean): Modifier {
    if (!lit) return this
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    return this.background(colors.accent.copy(alpha = 0.14f), shape).border(1.5.dp, colors.accent, shape)
}

/**
 * The projects and their channels, then the channels in none, for the sidebar and the drawer, with
 * the drag that arranges them. [channelRow] draws one channel given the modifier that makes it
 * draggable; [scroll] is the scrolling list this sits in, to scroll near its edges.
 */
@Composable
internal fun ArrangeableChannels(
    ui: FeedUi,
    /** The list as it is now, and whether this person may arrange it. Handed in, not read here, so a
     *  new state from the engine that leaves the channels as they were (a reply somewhere) skips it. */
    sections: ChannelSections,
    may: Boolean,
    drag: SidebarDrag,
    scroll: ScrollState,
    /** The drawer's rows, on a phone, rather than the sidebar's. */
    drawer: Boolean,
    /** Said beside "Channels" to those who may arrange the list, such as how to start. */
    hint: String?,
    /** After a channel is opened from the list: the drawer closes. */
    onOpened: () -> Unit,
) {
    val folds = LocalCardFolds.current
    val density = LocalDensity.current
    val colors = AgentTheme.colors
    LaunchedEffect(ui) {
        snapshotFlow { ui.state.projects to ui.state.groups }.collect { (channels, groups) -> ui.arranging.settle(channels, groups) }
    }
    val landings = ui.arranging.landings
    LaunchedEffect(landings) {
        if (landings == 0) return@LaunchedEffect
        delay(SettleMillis)
        ui.arranging.dropLanded()
    }
    val held = drag.thing
    val edge = with(density) { 48.dp.toPx() }
    // Only while the pointer is near an edge: the rest of a drag draws only when the pointer moves.
    if (held != null && drag.nearEdge) {
        LaunchedEffect(held) {
            val fastest = with(density) { 14.dp.toPx() }
            while (isActive && drag.thing != null && drag.nearEdge) {
                withFrameNanos { }
                val box = drag.viewport?.takeIf { it.isAttached }?.boundsInWindow() ?: continue
                val y = drag.pointer.y
                val speed = when {
                    y < box.top + edge -> -fastest * ((box.top + edge - y) / edge).coerceIn(0f, 1f)
                    y > box.bottom - edge -> fastest * ((y - (box.bottom - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f) scroll.scrollBy(speed)
                drag.update()
            }
        }
    }
    val slack = with(density) { 24.dp.toPx() }
    val start = { thing: DragThing, at: Offset -> drag.start(thing, at, ui.channelSections, { id -> folds.isFolded(groupFoldKey(id)) }, slack, edge) }
    val end = { dropped: Boolean ->
        val result = drag.end()
        if (dropped && result != null) {
            val (thing, spot) = result
            when {
                thing is DragThing.Channel && spot is DropSpot.Channel ->
                    ui.projects.firstOrNull { it.id == thing.id }?.let { ui.placeChannel(it, spot.groupId, spot.index) }
                thing is DragThing.Group && spot is DropSpot.Group ->
                    ui.groups.firstOrNull { it.id == thing.id }?.let { ui.placeGroup(it, spot.index) }
            }
        }
    }
    val heldChannel = (held as? DragThing.Channel)?.id
    val heldGroup = (held as? DragThing.Group)?.id

    Column(
        Modifier
            .onGloballyPositioned {
                drag.content = it
                // Scrolled under a still pointer, by the wheel: where it would land has moved.
                if (drag.thing != null) drag.update()
            }
            .onPreviewKeyEvent { e ->
                if (drag.thing != null && e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyDown) drag.cancel()
                    true
                } else false
            }
            .drawWithContent {
                drawContent()
                val y = drag.lineY
                if (!y.isNaN()) {
                    val thick = 2.dp.toPx()
                    drawRoundRect(
                        colors.accent,
                        topLeft = Offset(drag.lineStart, y - thick / 2),
                        size = Size(size.width - drag.lineStart, thick),
                        cornerRadius = CornerRadius(thick),
                    )
                }
            },
    ) {
        if (sections.groups.isNotEmpty()) {
            SectionLabel("Projects", Modifier.padding(horizontal = 10.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.zIndex(if (heldGroup != null || sections.groups.any { (_, cs) -> cs.any { it.id == heldChannel } }) 1f else 0f)) {
                for ((group, channels) in sections.groups) {
                    val folded = folds.isFolded(groupFoldKey(group.id))
                    val holdsHeld = heldGroup == group.id || channels.any { it.id == heldChannel }
                    val focus = remember(group.id) { FocusRequester() }
                    Column(
                        Modifier
                            .zIndex(if (holdsHeld) 1f else 0f)
                            .then(if (heldGroup == group.id) Modifier.heldRow(drag, true) else Modifier),
                    ) {
                        GroupHeader(
                            ui, group, channels, folded,
                            Modifier
                                .dragRow(drag, RowKey.Header(group.id))
                                .sidebarGestures(
                                    label = group.name,
                                    canDrag = may,
                                    openMenu = { at -> ui.sideMenu = SideMenu(SideTarget.Group(group.id), at) },
                                    onStart = { at -> start(DragThing.Group(group.id), at) },
                                    onMove = drag::move,
                                    onEnd = end,
                                    nudge = if (may) { by -> ui.nudgeGroup(group, by) } else null,
                                    focus = focus,
                                )
                                .dropTarget(drag.intoGroup == group.id),
                        )
                        if (!folded) {
                            Column(Modifier.padding(start = 14.dp)) {
                                channels.forEach { key(it.id) { ArrangeChannelRow(ui, drag, it, may, heldChannel == it.id, start, end, drawer, onOpened) } }
                                if (channels.isEmpty()) {
                                    MetaText(
                                        "No channels yet",
                                        Modifier.dragRow(drag, RowKey.Empty(group.id)).padding(start = 10.dp, top = 2.dp, bottom = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        androidx.compose.foundation.layout.Row(
            Modifier.dragRow(drag, RowKey.Loose).padding(horizontal = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SectionLabel("Channels", Modifier.weight(1f))
            if (hint != null && may && sections.loose.size + sections.groups.size > 1) MetaText(hint)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.zIndex(if (sections.loose.any { it.id == heldChannel }) 1f else 0f)) {
            sections.loose.forEach { key(it.id) { ArrangeChannelRow(ui, drag, it, may, heldChannel == it.id, start, end, drawer, onOpened) } }
            if (sections.loose.isEmpty() && held is DragThing.Channel) {
                MetaText("Drop here for no project", Modifier.padding(start = 10.dp, bottom = 6.dp), color = colors.textMuted)
            }
        }
    }
}

/**
 * One channel in the list, draggable and with its menu. Its own composable, so a new state that
 * leaves this channel as it was skips it: the list redraws only the rows that changed.
 */
@Composable
private fun ArrangeChannelRow(
    ui: FeedUi,
    drag: SidebarDrag,
    p: Project,
    may: Boolean,
    held: Boolean,
    start: (DragThing, Offset) -> Unit,
    end: (Boolean) -> Unit,
    drawer: Boolean,
    onOpened: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // The menu and the drag sit around the row, so the row redrawing (it is opened, say) leaves them be.
    SideMenuAnchor(
        ui, SideTarget.Channel(p.id),
        Modifier
            .dragRow(drag, RowKey.Channel(p.id))
            .sidebarGestures(
                label = p.name,
                canDrag = may && p.slug != null,
                openMenu = { at -> ui.sideMenu = SideMenu(SideTarget.Channel(p.id), at) },
                onStart = { at -> start(DragThing.Channel(p.id), at) },
                onMove = drag::move,
                onEnd = end,
                nudge = if (may) { by -> ui.nudgeChannel(p, by) } else null,
                focus = focus,
                extraActions = if (may) listOf(CustomAccessibilityAction("Move ${p.name} to project") { ui.projectDialog = ProjectDialog.MoveChannel(p.id); true }) else emptyList(),
            )
            .heldRow(drag, held),
    ) {
        if (drawer) {
            DrawerProjectRow(p, selected = p.id == ui.state.current.id, dragging = held, grip = false) {
                ui.emit(FeedEvent.SelectProject(p.slug))
                onOpened()
            }
        } else {
            SidebarChannelRow(ui, p)
        }
    }
}

/** How long a drop the dashboard took stays laid over its answer, in case the answer is late. */
private const val SettleMillis = 4_000L

/** A project's heading in the list: folds its channels away, shows what waits in it when folded. */
@Composable
private fun GroupHeader(ui: FeedUi, group: ProjectGroup, channels: List<Project>, folded: Boolean, modifier: Modifier) {
    val folds = LocalCardFolds.current
    val colors = AgentTheme.colors
    val holdsCurrent = channels.any { it.id == ui.state.current.id }
    SideMenuAnchor(ui, SideTarget.Group(group.id), modifier) { NavRow(
        group.name,
        selected = folded && holdsCurrent,
        leading = { GroupGlyph(group, folded) },
        trailing = {
            if (folded) {
                WaitingBadge(channels.sumOf { it.waiting }, Modifier.padding(end = 6.dp))
                UnreadBadge(channels.sumOf { it.unread })
            }
            IconAction(dev.wildware.agents.app.design.Icons.Settings, "${group.name} settings", iconSize = 15.dp, tint = colors.textFaint) {
                ui.projectDialog = ProjectDialog.GroupSettings(group.id)
            }
        },
        spoken = "${group.name}, project, ${channels.size} channel${if (channels.size == 1) "" else "s"}, " + if (folded) "folded" else "open",
        onClick = { folds.set(groupFoldKey(group.id), !folded) },
    ) }
}
