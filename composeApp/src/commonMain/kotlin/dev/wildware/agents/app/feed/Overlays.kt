package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import androidx.compose.foundation.focusable
import dev.wildware.agents.app.design.gesture.DismissDrag
import dev.wildware.agents.app.design.gesture.EdgeProgress
import dev.wildware.agents.app.design.gesture.GestureAction
import dev.wildware.agents.app.design.gesture.ReorderableColumn
import dev.wildware.agents.app.design.gesture.SwipeActions
import dev.wildware.agents.app.design.gesture.SwipeActionsState
import dev.wildware.agents.app.design.gesture.ZoomState
import dev.wildware.agents.app.design.gesture.contextGestures
import dev.wildware.agents.app.design.gesture.edgeSwipe
import dev.wildware.agents.app.design.gesture.swipeDownToDismiss
import dev.wildware.agents.app.design.gesture.zoomable
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.AppMark
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One row of a context menu, with its icon. */
private data class MenuRow(val label: String, val icon: ImageVector, val destructive: Boolean, val event: FeedEvent?)

/**
 * [phone] adds "Select text": on a phone a press anywhere on a card opens this menu, so picking out
 * some of its words starts here. On the desktop a drag selects.
 */
private fun menuRows(target: MenuTarget, phone: Boolean = false): List<MenuRow> = buildList {
    add(MenuRow("Reply", Icons.Reply, false, FeedEvent.Reply(target.id)))
    add(MenuRow("Copy text", Icons.Copy, false, FeedEvent.CopyText(target.id)))
    if (phone) add(MenuRow("Select text", Icons.SelectText, false, FeedEvent.SelectText(target.id)))
    add(MenuRow("Copy link", Icons.Link, false, FeedEvent.CopyLink(target.id)))
    if (target.taggable) add(MenuRow("Tags…", Icons.Hash, false, FeedEvent.EditTags(target.id)))
    if (target.own) add(MenuRow("Delete", Icons.Trash, true, FeedEvent.Delete(target.id)))
}

/** The menu's actions as gesture-free equivalents, for [dev.wildware.agents.app.design.gesture.contextGestures]. */
fun menuActionsFor(target: MenuTarget, ui: FeedUi?): List<dev.wildware.agents.app.design.gesture.MenuAction> =
    menuRows(target).mapNotNull { row ->
        row.event?.let { e -> dev.wildware.agents.app.design.gesture.MenuAction(row.label, row.destructive) { ui?.emit(e) } }
    } + dev.wildware.agents.app.design.gesture.MenuAction("React with $QuickReaction") {
        ui?.emit(FeedEvent.React(target.id, QuickReaction))
    }

/**
 * The gestures a card or comment answers to, wired to the feed: long-press or right-click
 * opens the menu, double-tap adds [QuickReaction], and each action is a screen reader action.
 */
@Composable
fun Modifier.feedItemGestures(target: MenuTarget): Modifier {
    val ui = LocalFeedUi.current ?: return this
    return contextGestures(
        openMenu = { at -> ui.menu = target.copy(at = at) },
        quickReact = { ui.emit(FeedEvent.React(target.id, QuickReaction)) },
        actions = menuActionsFor(target, ui),
        menuLabel = "Actions for ${target.author}'s message",
    )
}

/** Copies for real, then tells the data layer. */
@Suppress("DEPRECATION")
@Composable
private fun rememberRunner(ui: FeedUi, target: MenuTarget): (FeedEvent) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { e ->
        when (e) {
            is FeedEvent.CopyText -> clipboard.setText(AnnotatedString(ui.textOf(target.id) ?: target.text))
            is FeedEvent.CopyLink -> clipboard.setText(AnnotatedString("agents://item/${target.id}"))
            else -> Unit
        }
        ui.emit(e)
    }
}

/** The desktop menu: opens at the pointer, closes on Escape or a click outside. */
@Composable
fun DesktopMenu(target: MenuTarget, anchorHeightPx: Int) {
    val ui = LocalFeedUi.current ?: return
    val colors = AgentTheme.colors
    val run = rememberRunner(ui, target)
    val density = LocalDensity.current
    val offset = with(density) { DpOffset(target.at.x.toDp(), (target.at.y - anchorHeightPx).toDp()) }
    DropdownMenu(
        expanded = true,
        onDismissRequest = { ui.menu = null },
        offset = offset,
        shape = AgentTheme.shapes.medium,
        containerColor = colors.surfaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MenuReactions.forEach { emoji ->
                Box(
                    Modifier.clip(CircleShape).pressable("React with $emoji", CircleShape) { ui.emit(FeedEvent.React(target.id, emoji)) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 18.sp) }
            }
        }
        menuRows(target).forEach { row ->
            DropdownMenuItem(
                text = { Text(row.label, style = AgentTheme.type.label) },
                leadingIcon = { Icon(row.icon, null, modifier = Modifier.size(16.dp)) },
                onClick = { row.event?.let(run) },
                colors = MenuDefaults.itemColors(
                    textColor = if (row.destructive) colors.error else colors.text,
                    leadingIconColor = if (row.destructive) colors.error else colors.textMuted,
                ),
            )
        }
    }
}

/** A dimmed layer behind sheets and drawers. Tapping it closes what is on top. */
/**
 * Gives a feed item its gestures and, on desktop, its menu, which opens at the pointer.
 */
@Composable
fun WithMenu(target: MenuTarget, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val ui = LocalFeedUi.current
    val isPhone = LocalIsPhone.current
    var height by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { height = it.height }) {
        content(Modifier.feedItemGestures(target))
        val open = ui?.menu
        if (open != null && open.id == target.id && !isPhone) DesktopMenu(open, height)
    }
}

@Composable
private fun Scrim(alpha: Float, label: String, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onTap,
            ),
    )
}

@Composable
private fun DragHandle() {
    Box(
        Modifier
            .padding(top = 8.dp, bottom = 6.dp)
            .width(36.dp)
            .height(5.dp)
            .clip(CircleShape)
            .background(AgentTheme.colors.textFaint.copy(alpha = 0.45f))
            .clearAndSetSemantics { },
    )
}

/**
 * The phone menu: a sheet from the bottom with quick reactions and actions. Swipe it down,
 * tap outside, or press Cancel to close it.
 */
@Composable
fun ActionSheet(target: MenuTarget, bottomInset: androidx.compose.ui.unit.Dp) {
    val ui = LocalFeedUi.current ?: return
    val colors = AgentTheme.colors
    val run = rememberRunner(ui, target)
    val drag = remember { DismissDrag() }
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(Modifier.fillMaxSize()) {
        Scrim(0.38f, "Close menu") { ui.menu = null }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, drag.offsetPx.roundToInt()) }
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .swipeDownToDismiss(drag) { ui.menu = null }
                .semantics { paneTitle = "Actions" }
                .padding(bottom = bottomInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DragHandle()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                MetaText(target.author, style = AgentTheme.type.label, color = colors.textMuted)
                Text(
                    target.text,
                    color = colors.text,
                    style = AgentTheme.type.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MenuReactions.forEach { emoji ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceSunken)
                            .pressable("React with $emoji", CircleShape) { ui.emit(FeedEvent.React(target.id, emoji)) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            menuRows(target, phone = true).forEach { row -> SheetRow(row.label, row.icon, row.destructive) { row.event?.let(run) } }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            SheetRow("Cancel", Icons.Close, false) { ui.menu = null }
        }
    }
}

@Composable
private fun SheetRow(label: String, icon: ImageVector, destructive: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val tint = if (destructive) colors.error else colors.text
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(label, AgentTheme.shapes.small, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (destructive) colors.error else colors.textMuted, modifier = Modifier.size(20.dp))
        Text(label, Modifier.padding(start = 16.dp), color = tint, style = AgentTheme.type.bodyStrong)
    }
}

/**
 * Pictures full screen, one at a time. Swipe sideways (or the arrows, or the arrow keys) to go
 * through them; pinch or double-tap to zoom, drag to pan once zoomed; swipe down to close.
 * Escape, + and - work from a keyboard, and a screen reader gets next and previous as actions.
 */
@Composable
fun ImageViewer(gallery: Gallery, safeArea: SafeArea) {
    val ui = LocalFeedUi.current ?: return
    val images = gallery.images
    if (images.isEmpty()) return
    val pager = rememberPagerState(initialPage = gallery.index.coerceIn(0, images.lastIndex)) { images.size }
    val zooms = remember(images) { images.map { ZoomState() } }
    val zoom = zooms[pager.currentPage]
    val drag = remember { DismissDrag() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val motion = AgentTheme.motion
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val close = { ui.viewer = null }
    fun go(page: Int) {
        if (page !in images.indices) return
        scope.launch { if (motion.reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }
    val current = images[pager.currentPage]
    val fade = (1f - drag.offsetPx / 900f).coerceIn(0.3f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = fade))
            .semantics {
                paneTitle = "Pictures"
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Next picture") { go(pager.currentPage + 1); true }.takeIf { pager.currentPage < images.lastIndex },
                    CustomAccessibilityAction("Previous picture") { go(pager.currentPage - 1); true }.takeIf { pager.currentPage > 0 },
                )
            }
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Escape -> { close(); true }
                    Key.DirectionRight -> { go(pager.currentPage + 1); true }
                    Key.DirectionLeft -> { go(pager.currentPage - 1); true }
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { zoom.zoomTo(zoom.scale * 1.5f); true }
                    Key.Minus, Key.NumPadSubtract -> { zoom.zoomTo(zoom.scale / 1.5f); true }
                    else -> false
                }
            }
            .focusable()
            .swipeDownToDismiss(drag, enabled = { !zoom.isZoomed }, onDismiss = close),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().offset { IntOffset(0, drag.offsetPx.roundToInt()) },
            // A zoomed picture keeps its drags for panning; swiping on to the next waits until it is back to size.
            userScrollEnabled = !zoom.isZoomed,
        ) { page ->
            val image = images[page]
            val pageZoom = zooms[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(image.aspect)
                        .zoomable(pageZoom)
                        .graphicsLayer {
                            scaleX = pageZoom.scale
                            scaleY = pageZoom.scale
                            translationX = pageZoom.pan.x
                            translationY = pageZoom.pan.y
                        }
                        .semantics { contentDescription = image.altText },
                ) { AttachmentImage(image, Modifier.fillMaxSize(), ContentScale.Fit) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = safeArea.top + 4.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.Close, "Close pictures", tint = Color.White, onClick = close)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(current.title, color = Color.White, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (images.size > 1) {
                    Text(
                        "${pager.currentPage + 1} of ${images.size}",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = Color.White.copy(alpha = 0.6f),
                        style = AgentTheme.type.meta,
                    )
                }
            }
            IconAction(Icons.ZoomOut, "Zoom out", tint = Color.White) { zoom.zoomTo(zoom.scale / 1.5f) }
            IconAction(Icons.ZoomIn, "Zoom in", tint = Color.White) { zoom.zoomTo(zoom.scale * 1.5f) }
        }
        if (images.size > 1) {
            if (pager.currentPage > 0) {
                IconAction(Icons.Back, "Previous picture", Modifier.align(Alignment.CenterStart).padding(start = 8.dp), tint = Color.White) {
                    go(pager.currentPage - 1)
                }
            }
            if (pager.currentPage < images.lastIndex) {
                IconAction(Icons.ChevronRight, "Next picture", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), tint = Color.White) {
                    go(pager.currentPage + 1)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = safeArea.bottom + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(current.altText, color = Color.White.copy(alpha = 0.85f), style = AgentTheme.type.body)
            Spacer(Modifier.height(6.dp))
            Text(
                if (images.size > 1) "Swipe for the next · pinch to zoom · swipe down to close" else "Pinch to zoom · swipe down to close",
                Modifier.clearAndSetSemantics { },
                color = Color.White.copy(alpha = 0.5f),
                style = AgentTheme.type.meta,
            )
        }
    }
}

/**
 * The projects drawer on phones. Opens with a swipe from the left edge or the Projects button;
 * closes with a swipe left, a tap outside, or Escape. Projects reorder by hold-and-drag.
 */
@Composable
fun ProjectsDrawer(ui: FeedUi, progress: EdgeProgress, widthPx: Float, safeArea: SafeArea) {
    if (progress.value <= 0f) return
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { progress.settle(false, motion.normal()) } }
    val density = LocalDensity.current
    val width = with(density) { widthPx.toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .edgeSwipe(progress, width = { widthPx }, reverse = true, onCommit = {})
            .onKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) { close(); true } else false },
    ) {
        Scrim(0.42f * progress.value, "Close projects", close)
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .offset { IntOffset(((progress.value - 1f) * widthPx).roundToInt(), 0) }
                .background(
                    when {
                        colors.sidebar.alpha >= 1f -> colors.sidebar
                        colors.isDark -> colors.surface
                        else -> colors.surfaceRaised
                    },
                )
                .semantics { paneTitle = "Projects" }
                .verticalScroll(rememberScrollState())
                .padding(top = safeArea.top + 8.dp, start = 14.dp, end = 14.dp, bottom = safeArea.bottom + 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark(30.dp)
                Text(
                    dev.wildware.agents.app.ProductName,
                    Modifier.padding(start = 10.dp).weight(1f).semantics { heading() },
                    color = colors.text,
                    style = AgentTheme.type.title.copy(fontSize = 18.sp, lineHeight = 22.sp),
                )
                IconAction(Icons.Close, "Close projects", onClick = close)
            }
            Spacer(Modifier.height(12.dp))
            DrawerWaitingRow(ui, close)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Projects", Modifier.weight(1f))
                MetaText("Hold to reorder")
            }
            Spacer(Modifier.height(6.dp))
            ReorderableColumn(
                items = ui.projects.filter { !it.archived },
                keyOf = { it.id },
                label = { it.name },
                onMove = { from, to -> ui.emit(FeedEvent.MoveProject(from, to)) },
            ) { p, dragging ->
                DrawerProjectRow(p, selected = p.id == ui.state.current.id, dragging = dragging) {
                    ui.emit(FeedEvent.SelectProject(p.slug))
                    close()
                }
            }
            ArchivedProjects(ui)
            Spacer(Modifier.height(20.dp))
            SectionLabel("Agents", Modifier.padding(horizontal = 10.dp))
            Spacer(Modifier.height(6.dp))
            ui.state.agents.forEach { a ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(Author.AgentAuthor(a), 26.dp, showPresence = true)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(a.name, color = colors.text, style = AgentTheme.type.label)
                        MetaText(a.doing ?: a.presence.label)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            SheetRow("Sign out", Icons.SignOut, destructive = false) { ui.emit(FeedEvent.SignOut) }
        }
    }
}

/** "Waiting on you" in the drawer: every question from every project, with how many. */
@Composable
private fun DrawerWaitingRow(ui: FeedUi, close: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val count = ui.state.waitingTotal
    val selected = ui.showingWaiting
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.text.copy(alpha = if (colors.isDark) 0.08f else 0.06f) else Color.Transparent)
            .pressable("Waiting on you, ${if (count == 0) "nothing" else questions(count)}", shape, role = Role.Tab) {
                ui.showWaiting()
                close()
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Clock, null, tint = colors.waiting, modifier = Modifier.size(18.dp))
        Text(
            "Waiting on you",
            Modifier.padding(start = 10.dp).weight(1f),
            color = if (selected) colors.text else colors.textMuted,
            style = AgentTheme.type.label,
        )
        WaitingBadge(count)
    }
}

@Composable
private fun DrawerProjectRow(p: Project, selected: Boolean, dragging: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (dragging) Modifier.lift(AgentTheme.elevation.raised, shape) else Modifier)
            .clip(shape)
            .background(
                when {
                    dragging -> colors.surfaceRaised
                    selected -> colors.text.copy(alpha = if (colors.isDark) 0.08f else 0.06f)
                    else -> Color.Transparent
                },
            )
            .then(if (dragging) Modifier.border(1.dp, colors.borderStrong, shape) else Modifier)
            .pressable(
                "${p.name}, ${p.unread} unread" + if (p.waiting > 0) ", ${questions(p.waiting)} waiting on you" else "",
                shape,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colors.agent(p.hue)))
        Text(
            p.name,
            Modifier.padding(start = 12.dp).weight(1f),
            color = if (selected) colors.text else colors.textMuted,
            style = AgentTheme.type.label,
        )
        WaitingBadge(p.waiting, Modifier.padding(end = 6.dp))
        UnreadBadge(p.unread, emphasised = selected)
        Icon(Icons.Grip, null, tint = colors.textFaint, modifier = Modifier.padding(start = 10.dp).size(18.dp))
    }
}

/** A reply that can be swiped right to answer it, long-pressed for its menu, double-tapped to react. */
@Composable
fun InteractiveReply(reply: Reply, modifier: Modifier = Modifier) {
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    val preview = LocalPreview.current
    val density = LocalDensity.current
    val swipe = remember {
        val start = if (preview.replySwipe == reply.id) with(density) { 72.dp.toPx() } else 0f
        SwipeActionsState(start)
    }
    val target = MenuTarget(
        id = reply.id,
        author = if (reply.author is Author.Owner) "You" else reply.author.name,
        text = reply.body,
        own = reply.author is Author.Owner,
    )
    SwipeActions(
        shape = AgentTheme.shapes.medium,
        modifier = modifier,
        state = swipe,
        startAction = GestureAction("Reply", Icons.Reply, colors.accent, colors.onAccent) {
            ui?.emit(FeedEvent.Reply(reply.id))
        },
    ) {
        val shown = ui?.reactionsFor(reply.id, reply.reactions) ?: reply.reactions
        ReplyView(
            reply.copy(reactions = shown),
            Modifier
                .background(if (swipe.offsetPx != 0f) colors.background else Color.Transparent)
                .feedItemGestures(target)
                .padding(vertical = 10.dp),
        )
    }
}

/** The thread on a phone: full screen, back with the button or a swipe from the left edge. */
@Composable
fun PhoneThreadScreen(ui: FeedUi, item: Threaded, widthPx: Float, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val preview = LocalPreview.current
    val scope = rememberCoroutineScope()
    val back: () -> Unit = {
        scope.launch {
            ui.threadBack.settle(true, motion.normal())
            ui.phoneThreadOpen = false
            ui.threadBack.snap(false)
        }
    }
    LaunchedEffect(item.id) {
        if (!preview.threadOpen) {
            ui.threadBack.snap(true)
            ui.threadBack.settle(false, motion.normal())
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f * (1f - ui.threadBack.value))))
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((ui.threadBack.value * widthPx).roundToInt(), 0) }
                .lift(AgentTheme.elevation.overlay, androidx.compose.ui.graphics.RectangleShape)
                .windowBackground(colors)
                .edgeSwipe(ui.threadBack, width = { widthPx }, enabled = !LocalSystemBack.current.ownsLeftEdge, onCommit = {
                    ui.phoneThreadOpen = false
                    ui.threadBack.snap(false)
                })
                .semantics { paneTitle = "Thread" }
                .onKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) { back(); true } else false },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = safeArea.top + 4.dp, start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shape = AgentTheme.shapes.pill
                Row(
                    Modifier.clip(shape).pressable("Back to ${ui.state.current.name}", shape, onClick = back).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Back, null, tint = colors.accent, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    Text("Feed", Modifier.padding(start = 4.dp), color = colors.accent, style = AgentTheme.type.label)
                }
                Text(
                    "Thread",
                    Modifier.weight(1f).semantics { heading() },
                    color = colors.text,
                    style = AgentTheme.type.bodyStrong,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconAction(Icons.More, "More thread actions")
            }
            // A preview showing a swiped reply opens scrolled to the replies.
            val scroll = rememberScrollState(if (preview.replySwipe != null) 100_000 else 0)
            Column(
                Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                FeedItemView(item, selected = false, density = Density.Compact, inThread = true)
                HeadQuestions(item)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(if (item.replies.size == 1) "1 reply" else "${item.replies.size} replies")
                    Spacer(Modifier.weight(1f))
                    if (item.replies.isNotEmpty()) MetaText("Swipe a reply right to answer it")
                }
                Spacer(Modifier.height(6.dp))
                val firstUnread = item.replies.indexOfFirst { it.unread }
                item.replies.forEachIndexed { i, reply ->
                    if (i == firstUnread) NewDivider(item.unreadReplies)
                    InteractiveReply(reply)
                }
                Spacer(Modifier.height(12.dp))
            }
            ThreadComposer(ui, item.id, Modifier.padding(start = 12.dp, end = 12.dp, bottom = safeArea.bottom + 6.dp, top = 6.dp))
        }
    }
}

/** The reply box, with "Replying to …" above it after a swipe or a menu Reply. */
@Composable
fun ThreadComposer(ui: FeedUi?, threadId: String, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val colors = AgentTheme.colors
    Column(modifier) {
        val replying = ui?.replyingTo
        if (replying != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(AgentTheme.shapes.medium)
                    .background(colors.accentSoft)
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Reply, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text("Replying to ${replying.author.name}", color = colors.text, style = AgentTheme.type.label)
                    MetaText(replying.body.replace("**", ""), color = colors.textMuted)
                }
                IconAction(Icons.Close, "Stop replying to ${replying.author.name}") { ui.replyingTo = null }
            }
        }
        Composer(
            placeholder = replying?.let { "Answer ${it.author.name}…" } ?: "Reply to the thread…",
            label = "Reply",
            compact = true,
            onSend = { text, files -> ui?.emit(FeedEvent.SendReply(threadId, text, replying?.id, files)) },
            focus = focus,
        )
    }
}

/** A pill that appears once the feed is scrolled, to jump back to the top. */
@Composable
fun BackToTop(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Row(
        modifier
            .lift(AgentTheme.elevation.raised, shape)
            .clip(shape)
            .background(if (colors.isDark) colors.accent else colors.text)
            .pressable("Back to top", shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (colors.isDark) colors.onAccent else colors.background
        Icon(Icons.ArrowUp, null, tint = fg, modifier = Modifier.size(16.dp))
        Text("Back to top", Modifier.padding(start = 6.dp), color = fg, style = AgentTheme.type.label)
    }
}
