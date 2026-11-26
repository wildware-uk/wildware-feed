package dev.wildware.agents.app.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * The guided tour: a few steps that light up the real parts of the screen, one at a time, with a
 * short line about each. The screen stays as it is under a dimmed layer with a hole cut round the
 * part being shown. Each part says where it is ([tourTarget]) only while the tour is on, so the
 * tour costs nothing the rest of the time.
 */

/** A part of the screen the tour can point at. */
enum class TourTarget {
    /** The channel list (the sidebar), or the "Projects" button that opens it on a phone. */
    Channels,
    /** The first card in the feed that isn't a question waiting on the owner. */
    Card,
    /** The reply box of the thread the tour opened. */
    Reply,
    /** The first question waiting on the owner, in the feed. */
    Question,
    /** "Waiting on you" in the sidebar, or the amber count on a phone's top bar. */
    Waiting,
    /** "Feed | Board | Docs". */
    Views,
    /** Where the owner's settings open: their name in the sidebar, their picture on a phone. */
    Settings,
    /** Never pointed at: the phone's feed heading, which the bubble keeps clear of so it stays readable. */
    Heading,
}

/** One step: what it lights up (the first of [targets] on screen) and what it says. */
enum class TourStep(val title: String, val targets: List<TourTarget>, private val wide: String, private val narrow: String = wide) {
    Channels(
        "Channels",
        listOf(TourTarget.Channels),
        "Each agent posts into a channel. Your channels are listed here.",
        "Each agent posts into a channel. Tap Projects to see yours.",
    ),
    Card("The feed", listOf(TourTarget.Card), "Agents post updates here. Each one has its own thread."),
    Reply("Replies", listOf(TourTarget.Reply), "Reply here and the agent gets it."),
    Waiting("Waiting on you", listOf(TourTarget.Question, TourTarget.Waiting), "Agents ask you things here. Answer with one tap."),
    Views("Feed, Board and Docs", listOf(TourTarget.Views), "Switch views here. A channel's Docs hold what the team knows."),
    Settings("Settings", listOf(TourTarget.Settings), "Open your settings here to connect your first agent.");

    /** What it says, worded for a phone or a wider screen. */
    fun line(phone: Boolean): String = if (phone) narrow else wide
}

/**
 * Whether the tour should start by itself on this team, and what to call once it is finished or
 * skipped. The app gives one per team it opens; previews and tests give none, so it never starts.
 */
class TourSeen(val due: Boolean, val done: () -> Unit)

val LocalTourSeen = staticCompositionLocalOf<TourSeen?> { null }

/** Where a part of the screen is, in window pixels, and who said so. */
private class TourSpot(val bounds: Rect, val owner: Any)

/**
 * The tour's place: which steps it takes and which one is showing. Off ([on] false) unless started.
 * Plain state, so the parts of the screen that read [on] redraw only when it starts and ends.
 */
@Stable
class Tour {
    /** The steps this run takes, in order. A step with nothing on screen is dropped as it is reached. */
    var steps by mutableStateOf<List<TourStep>>(emptyList())
        private set

    /** The step showing, as an index into [steps]; -1 while the tour is off. */
    var index by mutableIntStateOf(-1)
        private set

    val on: Boolean get() = index >= 0
    val step: TourStep? get() = steps.getOrNull(index)

    /** Which way the owner last moved, so a step with nothing to show is passed the same way. */
    private var forward = true

    /** Called when the tour ends, by Skip or by finishing. */
    var ended: () -> Unit = {}

    /** The thread the tour opened to show its reply box, to close again as it moves on. */
    internal var openedThread: String? = null

    private val places = mutableStateMapOf<TourTarget, TourSpot>()

    /** The tour layer's own bounds, in window pixels. */
    internal var frame by mutableStateOf(Rect.Zero)

    fun start(steps: List<TourStep>) {
        if (steps.isEmpty()) return
        this.steps = steps
        forward = true
        index = 0
    }

    fun next() {
        forward = true
        if (index + 1 >= steps.size) finish() else index++
    }

    fun back() {
        if (index <= 0) return
        forward = false
        index--
    }

    fun skip() = finish()

    /**
     * The step showing has nothing on screen to point at: it is dropped, and the tour goes on the
     * way it was going. Passed going back from the first step, it goes forward instead.
     */
    fun pass() {
        if (!on) return
        val at = index
        steps = steps.filterIndexed { i, _ -> i != at }
        when {
            steps.isEmpty() -> finish()
            forward -> if (at >= steps.size) finish() else index = at
            else -> index = (at - 1).coerceAtLeast(0).also { if (at == 0) forward = true }
        }
    }

    private fun finish() {
        if (!on) return
        index = -1
        steps = emptyList()
        places.clear()
        ended()
    }

    internal fun place(target: TourTarget, bounds: Rect, owner: Any) {
        val was = places[target]
        if (was == null || was.owner !== owner || was.bounds != bounds) places[target] = TourSpot(bounds, owner)
    }

    internal fun forget(target: TourTarget, owner: Any) {
        if (places[target]?.owner === owner) places.remove(target)
    }

    /** Where the bubble mustn't cover, inside the tour layer: the feed's heading, when it is on screen. */
    internal fun keepClear(): Rect? {
        val frame = frame
        if (frame.isEmpty) return null
        val seen = places[TourTarget.Heading]?.bounds?.intersect(frame) ?: return null
        return if (seen.isEmpty) null else seen.translate(-frame.left, -frame.top)
    }

    /**
     * Where [step] points, inside the tour layer: the first of its targets that is on screen, or
     * null when none is (not laid out, scrolled away, or too small to see).
     */
    internal fun spot(step: TourStep): Rect? {
        val frame = frame
        if (frame.isEmpty) return null
        for (target in step.targets) {
            val bounds = places[target]?.bounds ?: continue
            val seen = bounds.intersect(frame)
            if (seen.width >= MinSpotPx && seen.height >= MinSpotPx) return seen.translate(-frame.left, -frame.top)
        }
        return null
    }
}

/** Smaller than this, in pixels, a part doesn't count as on screen. */
private const val MinSpotPx = 8f

/** How long a step waits for its part to come on screen (a thread opening) before it is passed. */
private const val SpotWaitMillis = 1_500L

/**
 * Tells the tour where this is, while the tour is on; otherwise nothing at all. Not composable, and
 * it reads [Tour.on] only, so the screen around it redraws only as the tour starts and ends.
 */
fun Modifier.tourTarget(tour: Tour?, target: TourTarget): Modifier =
    if (tour == null || !tour.on) this else this.then(TourTargetElement(tour, target))

private data class TourTargetElement(val tour: Tour, val target: TourTarget) : ModifierNodeElement<TourTargetNode>() {
    override fun create() = TourTargetNode(tour, target)
    override fun update(node: TourTargetNode) {
        if (node.tour !== tour || node.target != target) {
            node.tour.forget(node.target, node)
            node.tour = tour
            node.target = target
        }
    }
}

private class TourTargetNode(var tour: Tour, var target: TourTarget) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        tour.place(target, coordinates.boundsInWindow(), this)
    }

    override fun onDetach() = tour.forget(target, this)
}

/** The first card the tour shows: one with a thread, that isn't a question waiting on the owner. */
internal fun tourCardIn(sections: List<FeedSection>): Threaded? =
    sections.asSequence().flatMap { it.items }.firstOrNull { it is Threaded && !(it is FeedItem.Question && it.waiting) } as? Threaded

/** The first question waiting on the owner in the feed. */
internal fun tourQuestionIn(sections: List<FeedSection>): FeedItem? =
    sections.asSequence().flatMap { it.items }.firstOrNull { it is FeedItem.Question && it.waiting }

/**
 * Starts the tour from the top of the feed: the board or docs give way to the feed, and anything
 * over it closes. Steps that can't show here (no cards yet; nothing waiting on a phone) are left out.
 */
fun FeedUi.startTour() {
    alertsOpen = false
    menu = null
    boardOpen = false
    docsOpen = false
    mediaOpen = false
    askForTop()
    val card = tourCardIn(sections) != null
    tour.start(
        TourStep.entries.filter { step ->
            when (step) {
                TourStep.Card, TourStep.Reply -> card
                // The phone's amber count shows only while something waits.
                TourStep.Waiting -> !isPhone || state.waitingTotal > 0 || tourQuestionIn(sections) != null
                else -> true
            }
        },
    )
}

/**
 * Gets the screen ready for [step]: scrolls the feed to its card, opens a thread for its reply box,
 * or takes the feed back to its top (where a phone keeps its top bar), closing a thread it opened.
 */
private fun FeedUi.prepare(step: TourStep) {
    when (step) {
        TourStep.Card -> {
            closeTourThread()
            tourCardIn(sections)?.let { reveal(it.id) }
        }
        TourStep.Reply -> {
            val card = tourCardIn(sections) ?: return
            if (threadId != card.id || (isPhone && !phoneThreadOpen)) {
                openThread(card.id, phone = isPhone)
                tour.openedThread = card.id
            }
        }
        else -> {
            closeTourThread()
            askForTop()
        }
    }
}

private fun FeedUi.closeTourThread() {
    val opened = tour.openedThread ?: return
    tour.openedThread = null
    if (threadId == opened) {
        threadId = null
        phoneThreadOpen = false
    }
}

/**
 * The tour, over the feed: started by itself the first time a person opens a team, and from
 * Settings' "Show the tour again". Draws nothing while it is off.
 */
@Composable
internal fun TourHost(ui: FeedUi, safeArea: SafeArea) {
    val tour = ui.tour
    val seen = LocalTourSeen.current
    val done by rememberUpdatedState(seen?.done)
    tour.ended = {
        ui.closeTourThread()
        done?.invoke()
    }
    if (seen?.due == true) {
        LaunchedEffect(ui) {
            // Once the feed has loaded and nothing else is over it.
            snapshotFlow { ui.state.loaded && !ui.overlayOpen }.first { it }
            delay(TourStartDelayMillis)
            if (!tour.on) ui.startTour()
        }
    }
    val step = tour.step ?: return
    TourOverlay(ui, tour, step, safeArea)
}

/** A moment for the feed to settle before the tour first shows. */
private const val TourStartDelayMillis = 400L

@Composable
private fun TourOverlay(ui: FeedUi, tour: Tour, step: TourStep, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = remember { FocusRequester() }
    // Where the hole is, gliding from one part to the next (at once with reduced motion). Null until
    // the first part is found, so nothing shows pointing at nothing.
    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var found by remember { mutableStateOf(false) }
    var shownStep by remember { mutableStateOf<TourStep?>(null) }

    LaunchedEffect(step) {
        ui.prepare(step)
        val spot = withTimeoutOrNull(SpotWaitMillis) { snapshotFlow { tour.spot(step) }.filterNotNull().first() }
        if (spot == null) {
            tour.pass()
            return@LaunchedEffect
        }
        if (!found || motion.reduced) hole.snapTo(spot) else hole.animateTo(spot, motion.settle())
        found = true
        shownStep = step
        runCatching { focus.requestFocus() }
        // Follows the part as it moves: the window resized, the phone turned, a thread sliding in.
        snapshotFlow { tour.spot(step) }.filterNotNull().collectLatest { moved ->
            if (motion.reduced) hole.snapTo(moved) else hole.animateTo(moved, motion.settle())
        }
    }

    val pad = with(density) { 6.dp.toPx() }
    val radius = with(density) { 10.dp.toPx() }
    val ring = with(density) { 2.dp.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { tour.frame = it.boundsInWindow() }) {
        // The dimmed layer, with the hole. It takes every touch, so nothing under it is pressed by mistake.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
                .clearAndSetSemantics { }
                .drawBehind {
                    if (!found) return@drawBehind
                    val r = hole.value.inflate(pad)
                    val path = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addRoundRect(RoundRect(r, CornerRadius(radius)))
                    }
                    drawPath(path, Color.Black.copy(alpha = if (colors.isDark) 0.62f else 0.5f))
                    drawRoundRect(colors.accent, r.topLeft, r.size, CornerRadius(radius), style = Stroke(ring))
                },
        )
        val showing = shownStep
        if (found && showing != null) {
            BubblePlacement(hole, pad, safeArea, phone = ui.isPhone, keepClear = tour::keepClear) {
                TourBubble(ui, tour, showing, focus)
            }
        }
    }
}

/**
 * Puts the bubble beside the hole: under it if there is room, else over it, else to its side, else
 * at the foot of the screen. Under the hole, it also goes below [keepClear] (the feed's heading)
 * where it would cover it. Worked out while laying out, so the hole gliding moves it without
 * redrawing it.
 */
@Composable
private fun BubblePlacement(
    hole: Animatable<Rect, *>,
    pad: Float,
    safeArea: SafeArea,
    phone: Boolean,
    keepClear: () -> Rect?,
    content: @Composable () -> Unit,
) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val top = margin + if (phone) safeArea.top.roundToPx() else 0
        val bottom = constraints.maxHeight - margin - if (phone) safeArea.bottom.roundToPx() else 0
        val width = (constraints.maxWidth - 2 * margin).coerceAtMost(360.dp.roundToPx()).coerceAtLeast(0)
        val bubble = measurables.first().measure(Constraints(maxWidth = width, maxHeight = (bottom - top).coerceAtLeast(0)))
        val r = hole.value.inflate(pad)
        val w = bubble.width
        val h = bubble.height
        val centred = (r.center.x - w / 2f).roundToInt().coerceIn(margin, (constraints.maxWidth - margin - w).coerceAtLeast(margin))
        val beside = (r.top.roundToInt()).coerceIn(top, (bottom - h).coerceAtLeast(top))
        // Under the hole, or under the heading when the bubble there would cover it.
        val clear = keepClear()
        val under = (r.bottom + gap).let { y ->
            if (clear != null && clear.overlaps(Rect(centred.toFloat(), y, centred + w.toFloat(), y + h))) maxOf(y, clear.bottom + gap) else y
        }
        val (x, y) = when {
            under + h <= bottom -> centred to under.roundToInt()
            r.top - gap - h >= top -> centred to (r.top - gap - h).roundToInt()
            r.right + gap + w <= constraints.maxWidth - margin -> (r.right + gap).roundToInt() to beside
            r.left - gap - w >= margin -> (r.left - gap - w).roundToInt() to beside
            else -> centred to (bottom - h).coerceAtLeast(top)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { bubble.place(x, y) }
    }
}

/** The step's few words, how far through it is, and Next, Back and Skip. Escape skips. */
@Composable
private fun TourBubble(ui: FeedUi, tour: Tour, step: TourStep, focus: FocusRequester) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val number = tour.index + 1
    val total = tour.steps.size
    val last = number >= total
    Column(
        Modifier
            .widthIn(max = 360.dp)
            .lift(AgentTheme.elevation.overlay, shape)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.borderStrong, shape)
            .testTag(TourBubbleTag)
            .semantics { paneTitle = "Tour" }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) {
                    tour.skip()
                    true
                } else {
                    false
                }
            }
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
    ) {
        // Read out as one, each time the step changes.
        Column(Modifier.padding(end = 6.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            MetaText("$number of $total", color = colors.textMuted)
            Spacer(Modifier.height(2.dp))
            Text(step.title, color = colors.text, style = AgentTheme.type.bodyStrong)
            Spacer(Modifier.height(2.dp))
            Text(step.line(ui.isPhone), color = colors.text, style = AgentTheme.type.body)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppButton("Skip tour", ButtonTone.Quiet, onClick = tour::skip)
            Spacer(Modifier.weight(1f))
            if (number > 1) AppButton("Back", ButtonTone.Secondary, onClick = tour::back)
            AppButton(if (last) "Done" else "Next", ButtonTone.Primary, onClick = tour::next)
        }
    }
}

/** The tour's bubble, for tests. */
internal const val TourBubbleTag = "tour-bubble"

/** "Show the tour again", in Settings. */
@Composable
internal fun TourEntry(ui: FeedUi) {
    SettingsHeading("Help")
    SettingsLinkRow(Icons.Refresh, "Show the tour again", "") {
        ui.alertsOpen = false
        ui.startTour()
    }
}
