package dev.wildware.agents.app.feed

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.mapNotNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.wildware.agents.app.design.gesture.edgeSwipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.AppMark

/** Below this width the app uses the phone layout. */
val PhoneBreakpoint = 700.dp

/** Insets the phone layout keeps clear for the status bar and home indicator. */
data class SafeArea(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

@Composable
fun FeedScreen(
    state: FeedState,
    safeArea: SafeArea = SafeArea(),
    startAt: String? = null,
    preview: ScreenPreview = ScreenPreview(),
    /** Where notifications, widgets and search ask the feed to go. */
    navigator: AppNavigator? = null,
    onEvent: (FeedEvent) -> Unit = {},
) = FeedScreen(rememberUpdatedState(state), safeArea, startAt, preview, navigator, onEvent)

/**
 * The feed over a live [state], as the engine's flow gives it. Read where it is used, so a new
 * state redraws only what reads it, in the frame it arrives, and nothing is written while drawing.
 */
@Composable
fun FeedScreen(
    state: State<FeedState>,
    safeArea: SafeArea = SafeArea(),
    startAt: String? = null,
    preview: ScreenPreview = ScreenPreview(),
    /** Where notifications, widgets and search ask the feed to go. */
    navigator: AppNavigator? = null,
    onEvent: (FeedEvent) -> Unit = {},
) {
    val colors = AgentTheme.colors
    val ui = remember(state) { FeedUi(preview, state, onEvent) }
    ui.onEvent = onEvent
    BoxWithConstraints(Modifier.fillMaxSize().windowBackground(colors)) {
        val phone = maxWidth < PhoneBreakpoint
        ui.isPhone = phone
        val widthPx = constraints.maxWidth.toFloat()
        CompositionLocalProvider(LocalFeedUi provides ui, LocalIsPhone provides phone, LocalPreview provides preview) {
            if (phone) PhoneFeed(ui, safeArea, startAt, widthPx) else DesktopFeed(ui, showRail = maxWidth >= RailBreakpoint, startAt)
            ui.viewer?.let { ImageViewer(it, if (phone) safeArea else SafeArea()) }
            if (ui.commandOpen) CommandBar(ui, if (phone) safeArea else SafeArea())
        }
        FollowNavigation(ui, navigator)
        FollowSystemBack(ui)
    }
}

/** Screen reader and keyboard equivalents for the list's own gestures. */
private fun Modifier.listActions(refresh: () -> Unit, toTop: () -> Unit, openProjects: (() -> Unit)?) = semantics {
    customActions = listOfNotNull(
        CustomAccessibilityAction("Refresh") { refresh(); true },
        CustomAccessibilityAction("Scroll to top") { toTop(); true },
        openProjects?.let { CustomAccessibilityAction("Open projects") { it(); true } },
    )
}

@Composable
private fun DesktopFeed(ui: FeedUi, showRail: Boolean, startAt: String?) = CompositionLocalProvider(LocalInlineThreads provides true) {
    DesktopColumns(ui, showRail, startAt)
}

/**
 * The wide layout, laid out as the website lays it out: projects on the left, the feed in a column
 * that grows with the window (to [FeedMaxWidth]), and who is working on the right once there is
 * room. Threads sit under their cards, so there is no thread pane.
 */
@Composable
private fun DesktopColumns(ui: FeedUi, showRail: Boolean, startAt: String?) {
    val state = ui.state
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(startIndex(state, startAt, itemsBefore = 1))
    val toTop: () -> Unit = { scope.launch { if (motion.reduced) list.scrollToItem(0) else list.animateScrollToItem(0) } }
    val refresh: () -> Unit = { ui.emit(FeedEvent.Refresh) }
    // The window listens for shortcuts from the start, before anything is clicked.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    Row(
        Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onPreviewKeyEvent { e ->
            // Cmd/Ctrl+K opens search and commands; F5 or Cmd/Ctrl+R refreshes; Home jumps to the newest item.
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.K && (e.isMetaPressed || e.isCtrlPressed) -> { ui.commandOpen = true; true }
                e.key == Key.F5 || (e.key == Key.R && (e.isMetaPressed || e.isCtrlPressed)) -> { refresh(); true }
                else -> false
            }
        }.onKeyEvent { e ->
            // Home only when nothing else took it, so it still moves the cursor in a text box.
            if (e.type == KeyEventType.KeyDown && e.key == Key.MoveHome) { toTop(); true } else false
        },
    ) {
        Sidebar(ui, Modifier.width(SidebarWidth), showAgents = !showRail)
        VerticalRule()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(
                Modifier.fillMaxSize().listActions(refresh, toTop, null),
                state = list,
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 110.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "header") {
                    FeedHeader(ui, compact = false, Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth().padding(bottom = 20.dp))
                }
                feedItems(ui, Density.Comfortable)
            }
            LoadOlderWhenNearEnd(ui, list)
            ScrollToRevealed(ui, list, itemsBefore = 1)
            val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 3 } }
            if (scrolled) BackToTop(Modifier.align(Alignment.TopCenter).padding(top = 14.dp), toTop)
            // "All projects" has no one place to post to, so the box shows once a project is open.
            if (state.current.slug != null) Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(bottomFade(colors.background))
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Composer(
                    focus = ui.composerFocus,
                    placeholder = "Write to everyone in ${state.current.name}…",
                    onSend = { text, files -> ui.emit(FeedEvent.Post(text, files)) },
                    modifier = Modifier.widthIn(max = FeedMaxWidth),
                )
            }
        }
        if (showRail) {
            VerticalRule()
            AgentsRail(ui, Modifier.width(RailWidth))
        }
    }
}

/** As wide as the website's feed column (80rem): room for threads, tables and pictures beside the words. */
private val FeedMaxWidth = 1280.dp
private val SidebarWidth = 240.dp
private val RailWidth = 272.dp

/** From this width the agents rail shows on the right, as the website's does. */
val RailBreakpoint = 1280.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneFeed(ui: FeedUi, safeArea: SafeArea, startAt: String?, widthPx: Float) {
    val state = ui.state
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(startIndex(state, startAt, itemsBefore = 2))
    val toTop: () -> Unit = { scope.launch { if (motion.reduced) list.scrollToItem(0) else list.animateScrollToItem(0) } }
    val drawerWidthPx = widthPx * 0.84f
    val openDrawer: () -> Unit = { scope.launch { ui.drawer.settle(true, motion.normal()) } }
    val refresh: () -> Unit = {
        ui.refreshing = true
        ui.emit(FeedEvent.Refresh)
    }
    // Until the live store reports back, a refresh shows for a moment and then settles.
    // The spinner stays for a moment at least, then until the re-read has landed.
    LaunchedEffect(ui.refreshing) {
        if (ui.refreshing) {
            delay(600)
            snapshotFlow { ui.state.loading }.first { !it }
            ui.refreshing = false
        }
    }
    val pull = rememberPullToRefreshState()
    Box(
        Modifier
            .fillMaxSize()
            .edgeSwipe(
                ui.drawer,
                width = { drawerWidthPx },
                // Only on the feed itself: not under a thread, a menu, the drawer or the picture viewer.
                enabled = ui.phoneThread == null && ui.drawer.value == 0f && ui.viewer == null && ui.menu == null,
                onCommit = {},
            ),
    ) {
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = refresh,
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = ui.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = safeArea.top),
                    containerColor = colors.surfaceRaised,
                    color = colors.accent,
                )
            },
        ) {
            LazyColumn(
                Modifier.fillMaxSize().listActions(refresh, toTop, openDrawer),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = safeArea.top + 8.dp, bottom = safeArea.bottom + 96.dp),
            ) {
                item(key = "bar") { PhoneTopBar(state, onProjects = openDrawer, onTapBar = toTop, onSearch = { ui.commandOpen = true }) }
                item(key = "header") { FeedHeader(ui, compact = true, Modifier.padding(top = 12.dp, bottom = 16.dp)) }
                feedItems(ui, Density.Compact)
            }
            LoadOlderWhenNearEnd(ui, list)
            ScrollToRevealed(ui, list, itemsBefore = 2)
        }
        // Tapping the status bar area scrolls to the top, as it does across iOS.
        Box(
            Modifier
                .fillMaxWidth()
                .height(safeArea.top)
                .pointerInput(Unit) { detectTapGestures { toTop() } }
                .clearAndSetSemantics { },
        )
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 4 } }
        if (scrolled) BackToTop(Modifier.align(Alignment.TopCenter).padding(top = safeArea.top + 8.dp), toTop)
        if (state.current.slug != null) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(bottomFade(colors.background))
                .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = safeArea.bottom + 6.dp),
        ) {
            Composer(
                focus = ui.composerFocus,
                placeholder = "Message ${state.current.name}…",
                onSend = { text, files -> ui.emit(FeedEvent.Post(text, files)) },
                compact = true,
            )
        }
        ui.phoneThread?.let { PhoneThreadScreen(ui, it, widthPx, safeArea) }
        ProjectsDrawer(ui, ui.drawer, drawerWidthPx, safeArea)
        ui.menu?.let { ActionSheet(it, safeArea.bottom) }
    }
}

@Composable
private fun PhoneTopBar(state: FeedState, onProjects: () -> Unit, onTapBar: () -> Unit, onSearch: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Row(
        Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { onTapBar() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(shape).pressable("Projects, ${state.totalUnread} unread elsewhere", shape, onClick = onProjects).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Back, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text("Projects", Modifier.padding(start = 4.dp), color = colors.accent, style = AgentTheme.type.label)
            Spacer(Modifier.width(6.dp))
            UnreadBadge(state.totalUnread - state.current.unread, emphasised = true)
        }
        Spacer(Modifier.weight(1f))
        IconAction(Icons.Search, "Search", onClick = onSearch)
        IconAction(Icons.Bell, "Notifications")
        Box(Modifier.pressable("Your account", CircleShape) {}, contentAlignment = Alignment.Center) {
            Avatar(state.owner, 30.dp)
        }
    }
}

/** What an empty feed says: still loading, or really nothing here. */
@Composable
private fun EmptyFeed(state: FeedState, filter: FeedFilter, modifier: Modifier) {
    val colors = AgentTheme.colors
    Column(modifier.padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(44.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                !state.loaded -> "Loading the feed…"
                filter != FeedFilter.All -> "Nothing matches “${filter.label}”"
                else -> "Nothing here yet"
            },
            color = colors.text,
            style = AgentTheme.type.title,
        )
        Spacer(Modifier.height(4.dp))
        MetaText(
            if (state.loaded) "Updates and questions from your agents will show up here." else "This takes a moment the first time.",
            color = colors.textMuted,
        )
    }
}

/** Fades the feed out above the composer, then hides it fully so no text shows behind the box. */
private fun bottomFade(background: Color) = Brush.verticalGradient(
    0f to background.copy(alpha = 0f),
    0.35f to background,
    1f to background,
)

/** Where in the list an item sits, so a screen can open scrolled to it. */
private fun startIndex(state: FeedState, key: String?, itemsBefore: Int): Int =
    key?.let { indexIn(state.sections, it, itemsBefore) } ?: 0

/** Where card [id] sits in the list built from [sections], or null when it isn't there. */
private fun indexIn(sections: List<FeedSection>, id: String, itemsBefore: Int): Int? {
    val keys = sections.flatMapIndexed { s, section -> listOf("section-$s") + section.items.map { it.id } }
    return keys.indexOf(id).takeIf { it >= 0 }?.plus(itemsBefore)
}

/** How long to wait for a card to load (a project switching, a page arriving) before giving up. */
private const val RevealWaitMillis = 10_000L

/**
 * Scrolls to the card [FeedUi.reveal] asked for: a search result, a tapped notification, a deep
 * link. It waits for the card to load, shows every card if the chips hid it, and leaves a little
 * room above it so the "Back to top" pill doesn't cover its header.
 */
@Composable
private fun ScrollToRevealed(ui: FeedUi, list: LazyListState, itemsBefore: Int) {
    val target = ui.revealing ?: return
    val motion = AgentTheme.motion
    val room = with(LocalDensity.current) { 64.dp.roundToPx() }
    LaunchedEffect(target) {
        val index = withTimeoutOrNull(RevealWaitMillis) {
            snapshotFlow { indexIn(ui.sections, target.id, itemsBefore) to (ui.state.item(target.id) != null) }
                // Loaded but not listed means the chips hid it: show everything again.
                .onEach { (index, loaded) -> if (index == null && loaded && ui.filter != FeedFilter.All) ui.filter = FeedFilter.All }
                .mapNotNull { it.first }
                .first()
        }
        if (index != null) {
            if (motion.reduced) list.scrollToItem(index, -room) else list.animateScrollToItem(index, -room)
        }
        if (ui.revealing == target) ui.revealing = null
    }
}

/** The sections and their cards, hung off a timeline rail. */
/** Reads further into the past once the end of the list comes into view. */
@Composable
private fun LoadOlderWhenNearEnd(ui: FeedUi, list: LazyListState) {
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, ui.state.hasMore, ui.state.loading) {
        if (nearEnd && ui.state.hasMore && !ui.state.loading) ui.emit(FeedEvent.LoadOlder)
    }
}

private fun LazyListScope.feedItems(ui: FeedUi, density: Density) {
    val state = ui.state
    val sections = ui.sections
    if (sections.isEmpty()) {
        item(key = "empty") { EmptyFeed(state, ui.filter, Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth()) }
    }
    sections.forEachIndexed { s, section ->
        item(key = "section-$s", contentType = "section") {
            SectionHeading(section, first = s == 0, Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth())
        }
        section.items.forEachIndexed { i, item ->
            // By kind, so a card scrolling in takes over the layout of one of its kind that scrolled out.
            item(key = item.id, contentType = item::class) {
                Box(Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth()) {
                    FeedRow(
                        item,
                        selected = density == Density.Comfortable && item.id == ui.threadId,
                        density = density,
                        first = i == 0,
                        last = i == section.items.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(section: FeedSection, first: Boolean, modifier: Modifier) {
    val colors = AgentTheme.colors
    val waiting = section.items.any { it is FeedItem.Question }
    Row(
        modifier.padding(top = if (first) 0.dp else 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (waiting) {
            Box(Modifier.padding(end = 8.dp).size(7.dp).clip(CircleShape).background(colors.waiting))
        }
        SectionLabel(section.label, color = if (waiting) colors.waiting else colors.textFaint)
        Box(Modifier.padding(start = 12.dp).weight(1f).height(1.dp).background(colors.border))
    }
}

@Composable
private fun FeedRow(item: FeedItem, selected: Boolean, density: Density, first: Boolean, last: Boolean) {
    // Questions sit apart from the timeline: they are about now, not the history.
    val row = if (item is FeedItem.Question) Modifier else Modifier.rail(item, first, last)
    Box(row.padding(bottom = 10.dp)) { FeedItemView(item, selected, density) }
}

/** The left-hand timeline: a dot in the item's colour on a thin line that joins the items. */
@Composable
private fun Modifier.rail(item: FeedItem, first: Boolean, last: Boolean): Modifier {
    val colors = AgentTheme.colors
    val dot = when (item) {
        is FeedItem.Update -> colors.level(item.level)
        is FeedItem.Post -> colors.text
        is FeedItem.Question -> colors.waiting
    }
    val line = colors.border
    val ring = colors.background
    return drawBehind {
        val x = 11.dp.toPx()
        val dotY = 29.dp.toPx()
        val top = if (first) dotY else 0f
        if (last) {
            drawLine(
                Brush.verticalGradient(listOf(line, Color.Transparent), startY = top, endY = size.height),
                Offset(x, top), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
            )
        } else {
            drawLine(line, Offset(x, top), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        drawCircle(ring, radius = 6.dp.toPx(), center = Offset(x, dotY))
        drawCircle(dot, radius = 3.5.dp.toPx(), center = Offset(x, dotY))
    }.padding(start = 26.dp)
}
