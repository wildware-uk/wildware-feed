package dev.wildware.agents.app.design.gesture

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Something a gesture does. Every gesture action is also offered to screen readers under
 * [label], so nothing is reachable by swiping alone.
 */
@Immutable
data class GestureAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onColor: Color,
    val run: () -> Unit,
)

/**
 * The strip along the left edge that belongs to edge swipes (back, open the drawer). Other
 * horizontal swipes ignore touches that start inside it, so the two never fight.
 */
val EdgeZone: Dp = 24.dp

/** How much more sideways than up or down a drag must be to swipe a row rather than scroll the list. */
private const val SidewaysRatio = 1.5f

/** How far a row must travel before letting go commits its action. */
val SwipeCommitDistance: Dp = 96.dp

/** Swipes are for fingers. Mouse users get right-click menus and visible buttons instead. */
internal fun PointerType.isTouchLike() = this == PointerType.Touch || this == PointerType.Stylus

/**
 * Keeps row swipes away from text boxes. A swipe that starts inside a box is the owner moving the
 * cursor or picking words, and while a box has focus the keyboard is up and they are writing: in
 * both cases a swipe must not answer or dismiss anything. One per window, from the theme.
 */
@Stable
class SwipeGuard {
    /** The pointer whose touch went down inside a text box, if the latest one did. */
    internal var downInInput: PointerId? = null

    private val focused = mutableStateSetOf<Any>()

    /** A text box has focus, so the keyboard is up. */
    val typing: Boolean get() = focused.isNotEmpty()

    internal fun focus(input: Any, on: Boolean) {
        if (on) focused.add(input) else focused.remove(input)
    }
}

val LocalSwipeGuard = staticCompositionLocalOf<SwipeGuard?> { null }

/**
 * Marks a text box for [SwipeGuard]: a touch that starts here, or any swipe while this box has
 * focus, never swipes the row around it. [input] names the box; drop it with [SwipeGuard.focus]
 * off when the box leaves.
 */
fun Modifier.guardsSwipes(guard: SwipeGuard, input: Any): Modifier = this
    .onFocusChanged { guard.focus(input, it.hasFocus) }
    // The first pass runs before any row's own, so the row knows where this touch began.
    .pointerInput(guard) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            guard.downInInput = down.id
        }
    }

/** Where a row is in its swipe. Hoisted so a screen (or a screenshot) can start it part-way. */
@Stable
class SwipeActionsState(initialOffsetPx: Float = 0f) {
    var offsetPx by mutableFloatStateOf(initialOffsetPx)
        internal set
}

/**
 * A row you can swipe sideways. Dragging right reveals [startAction], dragging left reveals
 * [endAction]; letting go past [SwipeCommitDistance] runs it. Crossing the line gives a haptic
 * tick, and the revealed colour deepens as you go, so you know before you let go.
 */
@Composable
fun SwipeActions(
    shape: Shape,
    modifier: Modifier = Modifier,
    startAction: GestureAction? = null,
    endAction: GestureAction? = null,
    state: SwipeActionsState = remember { SwipeActionsState() },
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val guard = LocalSwipeGuard.current
    val scope = rememberCoroutineScope()
    val motion = AgentTheme.motion
    var rootX by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }

    Box(
        modifier
            .onGloballyPositioned { rootX = it.positionInRoot().x }
            .semantics {
                customActions = listOfNotNull(startAction, endAction).map { action ->
                    CustomAccessibilityAction(action.label) { action.run(); true }
                }
            },
    ) {
        val offset = state.offsetPx
        val shown = when {
            offset > 0f -> startAction
            offset < 0f -> endAction
            else -> null
        }
        if (shown != null) {
            val commitPx = with(androidx.compose.ui.platform.LocalDensity.current) { SwipeCommitDistance.toPx() }
            val progress = (abs(offset) / commitPx).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(shown.color.copy(alpha = 0.25f + 0.75f * progress)),
                contentAlignment = if (offset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .graphicsLayer {
                            val s = 0.7f + 0.3f * progress
                            scaleX = s
                            scaleY = s
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(shown.icon, null, tint = shown.onColor, modifier = Modifier.size(24.dp))
                    Text(shown.label, color = shown.onColor, style = AgentTheme.type.label)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(state.offsetPx.roundToInt(), 0) }
                .pointerInput(startAction, endAction, guard) {
                    val commitPx = SwipeCommitDistance.toPx()
                    val edgePx = EdgeZone.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!down.type.isTouchLike() || rootX + down.position.x < edgePx) return@awaitEachGesture
                        // Not from inside a text box, and not while the keyboard is up.
                        if (guard != null && (guard.downInInput == down.id || guard.typing)) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            val allowed = (over > 0 && startAction != null) || (over < 0 && endAction != null)
                            // Only a mostly sideways drag: a thumb scrolling in an arc is a scroll, and the list keeps it.
                            val moved = change.position - down.position
                            val sideways = abs(moved.x) > abs(moved.y) * SidewaysRatio
                            if (allowed && sideways) {
                                change.consume()
                                state.offsetPx += over
                            }
                        } ?: return@awaitEachGesture
                        tracker.addPointerInputChange(start)
                        horizontalDrag(start.id) { change ->
                            tracker.addPointerInputChange(change)
                            val raw = state.offsetPx + change.positionChange().x
                            val clamped = when {
                                raw > 0f && startAction == null -> 0f
                                raw < 0f && endAction == null -> 0f
                                else -> raw
                            }
                            // Past the line the row drags with resistance, so it feels like a detent.
                            val damped = if (abs(clamped) > commitPx) {
                                val sign = if (clamped > 0) 1f else -1f
                                sign * (commitPx + (abs(clamped) - commitPx) * 0.6f)
                            } else clamped
                            state.offsetPx = damped
                            val nowArmed = abs(damped) >= commitPx
                            if (nowArmed != armed) {
                                armed = nowArmed
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            }
                            change.consume()
                        }
                        val velocity = tracker.calculateVelocity().x
                        val flung = abs(velocity) > 1600f && abs(state.offsetPx) > commitPx / 2
                        val action = when {
                            state.offsetPx > 0 && (armed || flung) -> startAction
                            state.offsetPx < 0 && (armed || flung) -> endAction
                            else -> null
                        }
                        armed = false
                        if (action != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            action.run()
                        }
                        scope.launch {
                            animate(state.offsetPx, 0f, animationSpec = motion.settle()) { v, _ -> state.offsetPx = v }
                        }
                    }
                },
        ) { content() }
    }
}
