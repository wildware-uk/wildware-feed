package dev.wildware.agents.app.feed

import kotlinx.coroutines.flow.distinctUntilChanged
import dev.wildware.agents.app.design.ProjectColours
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.AppMark
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Stable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset

/** Where the keyboard is: on the feed itself, or anywhere in it. */
@Stable
private class RootFocus {
    var itself by mutableStateOf(false)
    var within by mutableStateOf(false)
}

/** Below this width the app uses the phone layout. */
val PhoneBreakpoint = 700.dp

/**
 * Insets the phone layout keeps clear for the status bar and home indicator (or the keyboard).
 *
 * A live one ([SafeArea.live]) reads the system's insets each time it is asked. The keyboard moves
 * the bottom inset every frame while it slides, so screens read it while laying out
 * ([safePadding], [safeContentPadding]), not while composing: the composer then glides with the
 * keyboard and nothing else redraws.
 */
@Stable
class SafeArea private constructor(private val topOf: () -> Dp, private val bottomOf: () -> Dp, private val fixed: Boolean) {
    constructor(top: Dp = 0.dp, bottom: Dp = 0.dp) : this({ top }, { bottom }, fixed = true)

    val top: Dp get() = topOf()
    val bottom: Dp get() = bottomOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is SafeArea && fixed && other.fixed && top == other.top && bottom == other.bottom)

    override fun hashCode(): Int = if (fixed) 31 * top.hashCode() + bottom.hashCode() else super.hashCode()
    override fun toString() = "SafeArea(top=$top, bottom=$bottom)"

    companion object {
        /** Insets that change while the screen is up: [top] and [bottom] are read afresh each time. */
        fun live(top: () -> Dp, bottom: () -> Dp) = SafeArea(top, bottom, fixed = false)
    }
}

/**
 * The system's safe area (status bar, notch, home indicator, keyboard), as one [SafeArea] that
 * keeps its identity while the insets move, so the app above it never redraws for them.
 */
@Composable
fun rememberSystemSafeArea(): SafeArea {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    return remember(density, insets) {
        val top = derivedStateOf { with(density) { insets.getTop(density).toDp() } }
        val bottom = derivedStateOf { with(density) { insets.getBottom(density).toDp() } }
        SafeArea.live(top = { top.value }, bottom = { bottom.value })
    }
}

/**
 * Padding that keeps clear of [area] on the sides given, plus that much more, worked out while
 * laying out. A side left null is not padded. A moving keyboard then shifts what this wraps
 * without redrawing it.
 */
fun Modifier.safePadding(area: SafeArea, top: Dp? = null, bottom: Dp? = null): Modifier = layout { measurable, constraints ->
    val t = top?.let { (area.top + it).roundToPx().coerceAtLeast(0) } ?: 0
    val b = bottom?.let { (area.bottom + it).roundToPx().coerceAtLeast(0) } ?: 0
    val placeable = measurable.measure(constraints.offset(vertical = -(t + b)))
    layout(placeable.width, constraints.constrainHeight(placeable.height + t + b)) { placeable.place(0, t) }
}

/** A list's padding that keeps clear of [area], read while the list lays out. */
@Stable
fun safeContentPadding(area: SafeArea, horizontal: Dp, top: Dp, bottom: Dp): PaddingValues = object : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = horizontal
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = horizontal
    override fun calculateTopPadding() = area.top + top
    override fun calculateBottomPadding() = area.bottom + bottom
}

@Composable
fun FeedScreen(
    state: FeedState,
    safeArea: SafeArea = SafeArea(),
    startAt: String? = null,
    preview: ScreenPreview = ScreenPreview(),
    /** Where notifications, widgets and search ask the feed to go. */
    navigator: AppNavigator? = null,
    /** Whether the owner is away from the top, so new updates can wait for them. */
    onAwayFromTop: (Boolean) -> Unit = {},
    onEvent: (FeedEvent) -> Unit = {},
) = FeedScreen(rememberUpdatedState(state), safeArea, startAt, preview, navigator, onAwayFromTop, onEvent)

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
    /** Whether the owner is away from the top, so new updates can wait for them. */
    onAwayFromTop: (Boolean) -> Unit = {},
    onEvent: (FeedEvent) -> Unit = {},
) {
    val ui = remember(state) { FeedUi(preview, state, onEvent) }
    ui.onEvent = onEvent
    ui.onAwayFromTop = onAwayFromTop
    // One watcher for the whole feed, rather than one per card: marks cards that just moved on.
    LaunchedEffect(ui) { snapshotFlow { ui.state.sections }.collect { ui.noteActivity(it) } }
    val scope = rememberCoroutineScope()
    // The open project's own colours, if it has them. Read through a derived state, so only a change
    // of colours redraws the screen, not every new state from the engine.
    val theme by remember(ui) { derivedStateOf { ui.state.current.let { it.background to it.accent } } }
    ProjectColours(theme.first, theme.second) { FeedScreenBody(ui, safeArea, startAt, preview, navigator, scope) }
}

@Composable
private fun FeedScreenBody(
    ui: FeedUi,
    safeArea: SafeArea,
    startAt: String?,
    preview: ScreenPreview,
    navigator: AppNavigator?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = AgentTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().windowBackground(colors).mouseBackForward(ui)) {
        val phone = maxWidth < PhoneBreakpoint
        ui.isPhone = phone
        val widthPx = constraints.maxWidth.toFloat()
        // A [[Doc name]] link, in a doc or a message, opens in the Docs tab; other links as before.
        val uris = androidx.compose.ui.platform.LocalUriHandler.current
        val docLinks = remember(ui, uris) { DocLinkUriHandler(uris) { channel, title -> ui.openDocLink(channel, title) } }
        CompositionLocalProvider(LocalFeedUi provides ui, LocalIsPhone provides phone, LocalPreview provides preview, androidx.compose.ui.platform.LocalUriHandler provides docLinks) {
            if (phone) PhoneFeed(ui, safeArea, startAt, widthPx) else DesktopFeed(ui, showRail = maxWidth >= RailBreakpoint, startAt)
            val motion = AgentTheme.motion
            // Pictures open by fading and growing into place; search drops in from above.
            Presence(ui.viewer, fadeIn(motion.normal()) + scaleIn(motion.settle(), initialScale = 0.92f), fadeOut(motion.fast())) {
                ImageViewer(it, if (phone) safeArea else SafeArea())
            }
            ui.selecting?.let { SelectTextSheet(ui, it, safeArea) }
            Presence(Unit.takeIf { ui.commandOpen }, fadeIn(motion.fast()), fadeOut(motion.fast())) {
                CommandBar(ui, if (phone) safeArea else SafeArea(), Modifier.animateEnterExit(
                    enter = slideInVertically(motion.settle()) { -it / 10 } + scaleIn(motion.settle(), initialScale = 0.97f),
                    exit = slideOutVertically(motion.fast()) { -it / 14 },
                ))
            }
            ui.tagging?.let { TagPicker(ui, it) }
            ui.playing?.let { VideoOverlay(ui, it, if (phone) safeArea else SafeArea()) }
            Toast(ui, if (phone) safeArea else SafeArea())
            if (ui.bellOpen) NotificationPanel(ui, if (phone) safeArea else SafeArea()) { path -> scope.launch { ui.goTo(OpenCard.fromPath(path)) } }
            ui.statusPick?.let { StatusPicker(ui, it, if (phone) safeArea else SafeArea()) }
            AssignPickerHost(ui, if (phone) safeArea else SafeArea())
            if (ui.shortcutsOpen) ShortcutsSheet(ui, if (phone) safeArea else SafeArea())
            ui.deleting?.let { DeleteConfirm(ui, it) }
            ui.editing?.let { EditMessageDialog(ui, it) }
            if (ui.alertsOpen) AlertSettingsSheet(ui, if (phone) safeArea else SafeArea())
            if (ui.retentionOpen) RetentionSettingsSheet(ui, if (phone) safeArea else SafeArea())
            ui.projectDialog?.let { ProjectDialogView(ui, it) }
            ui.agentOpen?.let { AgentDialog(ui, it) }
            ui.personOpen?.let { PersonProfileDialog(ui, it) }
            if (ui.devicesOpen) DevicesSheet(ui, if (phone) safeArea else SafeArea())
            if (ui.secretsOpen) SecretsSheet(ui, if (phone) safeArea else SafeArea())
            if (ui.feedbackOpen) FeedbackSheet(ui, if (phone) safeArea else SafeArea())
            if (ui.peopleOpen) PeopleSheet(ui, if (phone) safeArea else SafeArea())
            if (ui.connectOpen) ConnectAgentSheet(ui, if (phone) safeArea else SafeArea())
            // Over everything else: an agent asking to join waits on the owner, and only for ten minutes.
            JoinPrompt(ui, if (phone) safeArea else SafeArea())
            // A free-plan refusal, with "Upgrade" to the billing site.
            UpgradePrompt(ui, if (phone) safeArea else SafeArea())
            // The guided tour, over everything: nothing at all while it is off.
            TourHost(ui, if (phone) safeArea else SafeArea())
        }
        FollowNavigation(ui, navigator)
        FollowHistory(ui)
        FollowBrowserHistory(ui)
        FollowSystemBack(ui, widthPx)
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
    val focus = remember { RootFocus() }
    // The keyboard comes back here when a message box is put away or a sheet closes and leaves it
    // nowhere, so the shortcuts keep working. Never taken from anything that has it.
    LaunchedEffect(ui) {
        snapshotFlow { ui.composerOpen || ui.overlayOpen }.collect { busy ->
            if (!busy && !focus.within) runCatching { rootFocus.requestFocus() }
        }
    }
    val keys = remember(ui) { FeedKeys(ui) }
    keys.refresh = refresh
    keys.toTop = toTop
    keys.teams = LocalTeams.current
    keys.feedFocused = { focus.itself }
    keys.firstVisible = { list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { (it.key as? String)?.takeIf { key -> ui.state.item(key) != null } } }
    Row(
        Modifier.fillMaxSize()
            .onFocusChanged { focus.itself = it.isFocused; focus.within = it.hasFocus }
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(keys::preview)
            // Single keys only when nothing else took them, so they never reach past a text box.
            .onKeyEvent(keys::bubble),
    ) {
        Sidebar(ui, Modifier.width(SidebarWidth), showAgents = !showRail)
        VerticalRule()
        Column(Modifier.weight(1f).fillMaxHeight()) {
        // Red, above the feed, until it is answered, read or put away: a page made visible.
        CriticalBanner(ui)
        // The board takes the feed's place; the feed keeps where it was scrolled to for coming back.
        if (ui.docsOpen && !ui.boardOpen) DocsPane(ui, Modifier.weight(1f).fillMaxWidth()) else
        if (ui.boardOpen) BoardPane(ui, Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().closesComposer(ui).listActions(refresh, toTop, null),
                state = list,
                // The composer floats over the end of the feed; on "All projects" it has its project picker too.
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 110.dp + if (state.current.slug == null) PickerRoom else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "header") {
                    FeedHeader(ui, compact = false, Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth().padding(bottom = 20.dp))
                }
                feedItems(ui, Density.Comfortable, list)
            }
            LoadOlderWhenNearEnd(ui, list)
            ScrollToRevealed(ui, list, itemsBefore = 1)
            FollowRepliedCard(ui, list, itemsBefore = 1)
            ScrollToTopWhenAsked(ui, list)
            HoldArrivalsWhileAway(ui, list)
            NewOrBackToTop(ui, list, scrolledPast = 3, Modifier.align(Alignment.TopCenter).padding(top = 14.dp), toTop)
            // On "All projects" the box says which project a post goes to, and can change it.
            val target = if (state.current.slug == null) ui.postProject else state.current
            // Only the slug is captured, so a new engine state doesn't redraw the composer.
            val postTo = if (state.current.slug == null) target?.slug else null
            if (target != null) DesktopComposer(ui, target, postTo, picking = state.current.slug == null, Modifier.align(Alignment.BottomCenter))
            // A card the feed hadn't paged to, open on its own over the feed, which stays where it was.
            AloneView(ui, FeedMaxWidth)
        }
        }
        if (showRail) {
            VerticalRule()
            AgentsRail(ui, Modifier.width(RailWidth))
        }
    }
}

/**
 * The desktop's message box at the foot of the feed, with the project picker over it on "All
 * projects". Its own composable, so a new engine state that leaves [target] alone skips it.
 */
@Composable
private fun DesktopComposer(ui: FeedUi, target: Project, postTo: String?, picking: Boolean, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(bottomFade(AgentTheme.colors.background))
            .padding(horizontal = 36.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = FeedMaxWidth)) {
            if (picking) PostToPicker(ui, target, Modifier.padding(bottom = 8.dp))
            Composer(
                focus = ui.composerFocus,
                placeholder = "Write to everyone in ${target.name}…",
                onSend = { text, files -> ui.emit(FeedEvent.Post(text, files, project = postTo, tags = ui.takePostTags(target.id))) },
                onSendVoice = { text, files, voice -> ui.emit(FeedEvent.Post(text, files, project = postTo, tags = ui.takePostTags(target.id), voice = voice)) },
                draftKey = "project:${target.slug}",
                tagProject = target.id,
                lastOwn = { LastOwn.inChannel(ui.state.sections, target.id) },
            )
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
    val drawerWidthPx = widthPx * DrawerWidthFraction
    val openDrawer: () -> Unit = { scope.launch { ui.drawer.settle(true, motion.settle()) } }
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
                // Where the platform owns the left edge (the iPhone), its swipe opens the drawer instead.
                enabled = !LocalSystemBack.current.ownsLeftEdge,
                // Only on the feed itself: not under a thread, a menu, the drawer or the picture viewer.
                canStart = { ui.phoneThread == null && ui.drawer.value == 0f && ui.viewer == null && ui.menu == null },
                onCommit = {},
            ),
    ) {
        // The thread screen shows a card open on its own; closing it puts the card away.
        LaunchedEffect(ui) {
            snapshotFlow { ui.phoneThreadOpen }.collect { open -> if (!open && ui.aloneId != null) ui.closeAlone() }
        }
        if (ui.docsOpen && !ui.boardOpen) {
            DocsPane(ui, Modifier.fillMaxSize().safePadding(safeArea, top = 0.dp, bottom = 0.dp), phone = true) {
                PhoneTopBar(state, onProjects = openDrawer, onTapBar = {}, onSearch = { ui.commandOpen = true }, onBell = { ui.bellOpen = !ui.bellOpen }, onAccount = { ui.alertsOpen = true })
            }
        } else if (ui.boardOpen) {
            BoardPane(ui, Modifier.fillMaxSize().safePadding(safeArea, top = 0.dp, bottom = 0.dp), phone = true) {
                PhoneTopBar(state, onProjects = openDrawer, onTapBar = {}, onSearch = { ui.commandOpen = true }, onBell = { ui.bellOpen = !ui.bellOpen }, onAccount = { ui.alertsOpen = true })
            }
        } else {
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
                    Modifier.fillMaxSize().closesComposer(ui).listActions(refresh, toTop, openDrawer),
                    state = list,
                    contentPadding = remember(safeArea, state.current.slug == null) {
                        safeContentPadding(safeArea, horizontal = 16.dp, top = 8.dp, bottom = 96.dp + if (state.current.slug == null) PickerRoom else 0.dp)
                    },
                ) {
                    item(key = "bar") { PhoneTopBar(state, onProjects = openDrawer, onTapBar = toTop, onSearch = { ui.commandOpen = true }, onBell = { ui.bellOpen = !ui.bellOpen }, onAccount = { ui.alertsOpen = true }) }
                    item(key = "header") { FeedHeader(ui, compact = true, Modifier.padding(top = 12.dp, bottom = 16.dp)) }
                    feedItems(ui, Density.Compact, list)
                }
                LoadOlderWhenNearEnd(ui, list)
                ScrollToRevealed(ui, list, itemsBefore = 2)
                FollowRepliedCard(ui, list, itemsBefore = 2)
                ScrollToTopWhenAsked(ui, list)
            }
            // Tapping the status bar area scrolls to the top, as it does across iOS.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(safeArea.top)
                    .pointerInput(Unit) { detectTapGestures { toTop() } }
                    .clearAndSetSemantics { },
            )
            HoldArrivalsWhileAway(ui, list)
            NewOrBackToTop(ui, list, scrolledPast = 4, Modifier.align(Alignment.TopCenter).padding(top = safeArea.top + 8.dp), toTop)
            val target = if (state.current.slug == null) ui.postProject else state.current
            // Only the slug is captured, so a new engine state doesn't redraw the composer.
            val postTo = if (state.current.slug == null) target?.slug else null
            if (target != null) Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(bottomFade(colors.background))
                    .padding(start = 12.dp, end = 12.dp, top = 18.dp)
                    .safePadding(safeArea, bottom = 6.dp),
            ) {
                if (state.current.slug == null) PostToPicker(ui, target, Modifier.padding(bottom = 6.dp, start = 4.dp))
                Composer(
                    focus = ui.composerFocus,
                    placeholder = "Message ${target.name}…",
                    onSend = { text, files -> ui.emit(FeedEvent.Post(text, files, project = postTo, tags = ui.takePostTags(target.id))) },
                    onSendVoice = { text, files, voice -> ui.emit(FeedEvent.Post(text, files, project = postTo, tags = ui.takePostTags(target.id), voice = voice)) },
                    draftKey = "project:${target.slug}",
                    tagProject = target.id,
                    compact = true,
                    lastOwn = { LastOwn.inChannel(ui.state.sections, target.id) },
                )
            }
        }
        ui.phoneThread?.let { CompositionLocalProvider(LocalDocChannel provides it.channelSlug) { PhoneThreadScreen(ui, it, widthPx, safeArea) } }
        ProjectsDrawer(ui, ui.drawer, drawerWidthPx, safeArea)
        // The menu's sheet rises from the bottom over a fading scrim, and sinks back when closed.
        val motion = AgentTheme.motion
        Presence(ui.menu, fadeIn(motion.fast()), fadeOut(motion.normal())) { target ->
            ActionSheet(target, safeArea, Modifier.animateEnterExit(
                enter = slideInVertically(motion.settle()) { it },
                exit = slideOutVertically(motion.normal()) { it },
            ))
        }
        // A channel's, project's or agent's menu, over the drawer it was opened from.
        Presence(ui.sideMenu, fadeIn(motion.fast()), fadeOut(motion.normal())) { menu ->
            SideSheet(ui, menu, safeArea, Modifier.animateEnterExit(
                enter = slideInVertically(motion.settle()) { it },
                exit = slideOutVertically(motion.normal()) { it },
            ))
        }
    }
}

@Composable
private fun PhoneTopBar(state: FeedState, onProjects: () -> Unit, onTapBar: () -> Unit, onSearch: () -> Unit, onBell: () -> Unit, onAccount: () -> Unit) {
    val colors = AgentTheme.colors
    val tour = LocalFeedUi.current?.tour
    val shape = AgentTheme.shapes.pill
    Row(
        Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { onTapBar() } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.tourTarget(tour, TourTarget.Channels).clip(shape).pressable("Projects, ${state.totalUnread} unread elsewhere", shape, onClick = onProjects).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Back, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text("Projects", Modifier.padding(start = 4.dp), color = colors.accent, style = AgentTheme.type.label)
            Spacer(Modifier.width(6.dp))
            UnreadBadge(state.totalUnread - state.current.unread, emphasised = true)
        }
        Spacer(Modifier.weight(1f))
        PhoneWaitingButton()
        IconAction(Icons.Search, "Search", onClick = onSearch)
        BellButton(state.bell.unseen) { onBell() }
        Box(Modifier.tourTarget(tour, TourTarget.Settings).pressable("Alerts on this device", CircleShape, onClick = onAccount), contentAlignment = Alignment.Center) {
            Avatar(state.owner, 30.dp)
        }
    }
}

/** The amber "questions waiting" count on the phone's top bar. Opens every waiting question. */
@Composable
private fun PhoneWaitingButton() {
    val ui = LocalFeedUi.current ?: return
    val count = ui.state.waitingTotal
    if (count == 0) return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Box(
        Modifier.tourTarget(ui.tour, TourTarget.Waiting).clip(shape).pressable("${questions(count)} waiting on you", shape) { ui.showWaiting() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(shape).background(colors.waiting).padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Clock, null, tint = colors.onWaiting, modifier = Modifier.size(14.dp))
            Text(
                "$count",
                Modifier.padding(start = 4.dp),
                color = colors.onWaiting,
                style = AgentTheme.type.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            )
        }
    }
}

/** What an empty feed says: still loading, or really nothing here. */
@Composable
private fun EmptyFeed(state: FeedState, filter: FeedFilter, modifier: Modifier, onConnect: () -> Unit) {
    val colors = AgentTheme.colors
    // The first load shows the shape of the feed at once; the cards then fade in over it.
    if (!state.loaded) return SkeletonFeed(modifier.padding(top = 8.dp, start = 4.dp))
    Column(modifier.padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(44.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                filter != FeedFilter.All -> "Nothing matches “${filter.label}”"
                else -> "Nothing here yet"
            },
            color = colors.text,
            style = AgentTheme.type.title,
        )
        Spacer(Modifier.height(4.dp))
        MetaText(
            "Updates and questions from your agents will show up here.",
            color = colors.textMuted,
        )
        // A dashboard with no agent yet: the way to get the first one.
        if (filter == FeedFilter.All && state.agents.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            AppButton("Connect an agent", ButtonTone.Primary, icon = Icons.Plus, onClick = onConnect)
        }
    }
}

/** Room the "To" project picker takes above the composer on "All projects". */
private val PickerRoom = 34.dp

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
/** Takes the feed back to its top when [FeedUi.toTopAsked] goes up, such as when a tag filter is set. */
@Composable
private fun ScrollToTopWhenAsked(ui: FeedUi, list: LazyListState) {
    val asked = ui.toTopAsked
    LaunchedEffect(asked) { if (asked > 0) list.scrollToItem(0) }
}

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

/**
 * After the owner replies on a card, follows it to where the reply puts it (the top, usually), so
 * it doesn't vanish from under them. It waits for the card to move up, which it does once the reply
 * lands (another card jumping above it doesn't count); other people's replies never set this, so their activity leaves the view alone. A card
 * that ends up first takes the feed right back to its top, which also lets held updates in.
 */
@Composable
private fun FollowRepliedCard(ui: FeedUi, list: LazyListState, itemsBefore: Int) {
    val target = ui.following ?: return
    val motion = AgentTheme.motion
    val room = with(LocalDensity.current) { 64.dp.roundToPx() }
    LaunchedEffect(target) {
        val moved = withTimeoutOrNull(RevealWaitMillis) {
            snapshotFlow { cardIndex(ui.sections, target.id) }.first { it != null && it < target.from }
        }
        val index = moved?.let { indexIn(ui.sections, target.id, itemsBefore) }
        when {
            index == null -> Unit
            moved == 0 -> if (motion.reduced) list.scrollToItem(0) else list.animateScrollToItem(0)
            motion.reduced -> list.scrollToItem(index, -room)
            else -> list.animateScrollToItem(index, -room)
        }
        if (ui.following == target) ui.following = null
    }
}

/** The sections and their cards, hung off a timeline rail. */
/**
 * "Back to top" once the owner is [scrolledPast] a few cards, or "3 new updates" as soon as updates
 * wait above them. Its reads are its own, so an engine publish redraws the pill only when its words change.
 */
@Composable
private fun NewOrBackToTop(ui: FeedUi, list: LazyListState, scrolledPast: Int, modifier: Modifier, toTop: () -> Unit) {
    val newCount by remember(ui) { derivedStateOf { ui.state.newCount } }
    val shown by remember(list) { derivedStateOf { list.firstVisibleItemIndex > scrolledPast || newCount > 0 && list.awayFromTop() } }
    BackToTopPill({ shown }, modifier, { newCount }, toTop)
}

/** How far down the owner may be and still count as at the top, as the website allows. */
private val AtTopSlack = 48.dp

/** Whether the owner has scrolled away from the top of the feed. */
private fun LazyListState.awayFromTop(slackPx: Int = 0): Boolean =
    firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > slackPx

/**
 * While the owner is away from the top, new updates wait rather than land above what they are
 * reading; the pill counts them, and going back to the top lets them in.
 */
@Composable
private fun HoldArrivalsWhileAway(ui: FeedUi, list: LazyListState) {
    val slack = with(LocalDensity.current) { AtTopSlack.roundToPx() }
    LaunchedEffect(list, ui) {
        snapshotFlow { list.awayFromTop(slack) }
            .distinctUntilChanged()
            .collect { ui.onAwayFromTop(it) }
    }
}

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun LazyListScope.feedItems(ui: FeedUi, density: Density, list: LazyListState) {
    val state = ui.state
    val sections = ui.sections
    if (sections.isEmpty()) {
        item(key = "empty") { EmptyFeed(state, ui.filter, Modifier.widthIn(max = FeedMaxWidth).fillMaxWidth()) { ui.connectOpen = true } }
    }
    // Only while the tour is on: the card and the question it points at.
    val tourCard = if (ui.tour.on) tourCardIn(sections)?.id else null
    val tourQuestion = if (ui.tour.on) tourQuestionIn(sections)?.id else null
    sections.forEachIndexed { s, section ->
        val key = "section-$s"
        // Every heading holds the top while its cards scroll under it, as the website's do, so the
        // owner always knows which day they are reading; the next one pushes it away.
        stickyHeader(key = key, contentType = if (section.items.any { it is FeedItem.Question && it.waiting }) "waiting" else "section") {
            StickyHeading(section, first = s == 0, list, key)
        }
        section.items.forEachIndexed { i, item ->
            // By kind, so a card scrolling in takes over the layout of one of its kind that scrolled out.
            item(key = item.id, contentType = item::class) {
                // Only the card a link opened changes when the ring comes and goes.
                // The keyboard's card (J and K) wears the same ring while it is on it, as does one open to edit in the box.
                val lit by remember(item.id) { derivedStateOf { ui.highlighted == item.id || ui.cursor == item.id || ui.boxEditing == item.id } }
                val spot = when (item.id) {
                    tourCard -> Modifier.tourTarget(ui.tour, TourTarget.Card)
                    tourQuestion -> Modifier.tourTarget(ui.tour, TourTarget.Question)
                    else -> Modifier
                }
                Box(Modifier.feedItemMotion(this).widthIn(max = FeedMaxWidth).fillMaxWidth().then(spot)) {
                    // A [[Doc name]] in the card links to its own channel's doc; one provider for both, as each costs a redraw.
                    CompositionLocalProvider(LocalLit provides lit, LocalDocChannel provides item.channelSlug) { FeedRow(
                        item,
                        selected = density == Density.Comfortable && item.id == ui.threadId,
                        density = density,
                        first = i == 0,
                        last = i == section.items.lastIndex,
                    ) }
                }
            }
        }
    }
}

/**
 * A section's heading ("Waiting on you", "Today"), held at the top of the list. The list holds it at its very top,
 * under its top padding (the status bar on a phone), so it moves itself down past that and paints
 * the background behind, so what scrolls under it doesn't show through. It moves by drawing, not
 * by growing, so the cards around it never shift.
 */
@Composable
private fun StickyHeading(section: FeedSection, first: Boolean, list: LazyListState, key: String) {
    val colors = AgentTheme.colors
    val gap = with(LocalDensity.current) { 6.dp.toPx() }
    // How far above its natural place the list holds it: 0 until it is held.
    val held by remember(list, key) {
        derivedStateOf { -(list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0).coerceAtMost(0) }
    }
    SectionHeading(
        section,
        first = first,
        Modifier
            .widthIn(max = FeedMaxWidth)
            .fillMaxWidth()
            .graphicsLayer { translationY = if (held > 0) held + gap else 0f }
            .drawBehind {
                if (held > 0) drawRect(colors.background, topLeft = Offset(0f, -held - gap), size = Size(size.width, size.height + held + gap))
            },
    )
}

/**
 * A card arriving fades in while the cards below slide down to make room; one leaving fades out as
 * the rest close up; a card that moves glides to its new place. Springs, as iOS lists move.
 * Nothing moves with reduced motion.
 */
@Composable
private fun Modifier.feedItemMotion(scope: LazyItemScope): Modifier {
    val motion = AgentTheme.motion
    if (motion.reduced) return this
    return with(scope) {
        animateItem(
            fadeInSpec = motion.normal(),
            placementSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
            fadeOutSpec = motion.fast(),
        )
    }
}

@Composable
private fun SectionHeading(section: FeedSection, first: Boolean, modifier: Modifier) {
    val colors = AgentTheme.colors
    val waiting = section.items.any { it is FeedItem.Question && it.waiting }
    Row(
        modifier.padding(top = if (first) 0.dp else 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (waiting) {
            Box(Modifier.padding(end = 8.dp).size(7.dp).clip(CircleShape).background(colors.waiting))
        }
        val count = section.items.size
        SectionLabel(
            if (waiting && count > 1) "${section.label} ($count)" else section.label,
            color = if (waiting) colors.waiting else colors.textFaint,
        )
        Box(Modifier.padding(start = 12.dp).weight(1f).height(1.dp).background(colors.border))
    }
}

@Composable
private fun FeedRow(item: FeedItem, selected: Boolean, density: Density, first: Boolean, last: Boolean) {
    // Waiting questions sit apart from the timeline: they are about now, not the history. An
    // answered one (on a team) is history, so it sits on the line.
    val row = if (item is FeedItem.Question && item.waiting) Modifier else Modifier.rail(item, first, last)
    Box(row.padding(bottom = 10.dp)) { FeedItemView(item, selected, density) }
}

/** The left-hand timeline: a dot in the item's colour on a thin line that joins the items. */
@Composable
private fun Modifier.rail(item: FeedItem, first: Boolean, last: Boolean): Modifier {
    val colors = AgentTheme.colors
    val dot = when (item) {
        is FeedItem.Update -> colors.level(item.level)
        is FeedItem.Post -> colors.text
        is FeedItem.Question -> if (item.waiting) colors.waiting else colors.success
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
