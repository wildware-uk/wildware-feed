package dev.wildware.agents.app.design.gesture

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How far a screen or drawer pulled in from the left edge is, from 0 (closed) to 1 (open).
 * Hoisted so screens can open it from a button too, and screenshots can start it open.
 */
@Stable
class EdgeProgress(initial: Float = 0f) {
    var value by mutableFloatStateOf(initial)
        internal set

    /** Opens or closes with the theme's motion, or at once when motion is reduced. */
    suspend fun settle(open: Boolean, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
        animate(value, if (open) 1f else 0f, animationSpec = spec) { v, _ -> value = v }
    }

    fun snap(open: Boolean) {
        value = if (open) 1f else 0f
    }
}

/**
 * A drag that starts in the left [EdgeZone] and pulls right. Used to go back and to open the
 * projects drawer. It listens before the content does, so a swipe that starts at the edge is
 * always the edge's, and a swipe that starts anywhere else never is. It only takes drags that
 * begin inside the app, so it leaves the system's own edge and home gestures alone.
 *
 * [width] is how far a full swipe travels, in pixels. Letting go past a third of it, or
 * flicking, commits; otherwise it springs back.
 */
fun Modifier.edgeSwipe(
    progress: EdgeProgress,
    width: () -> Float,
    enabled: Boolean = true,
    reverse: Boolean = false,
    onCommit: () -> Unit,
    onCancel: () -> Unit = {},
): Modifier = composed {
    if (!enabled) return@composed this
    val haptics = LocalHapticFeedback.current
    val motion = AgentTheme.motion
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    pointerInput(reverse) {
        val edgePx = EdgeZone.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Opening: only from the edge strip. Closing an open drawer: from anywhere on it.
            if (!down.type.isTouchLike() || (!reverse && down.position.x > edgePx)) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var total = Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                total += delta
                tracker.addPointerInputChange(change)
                if (!dragging) {
                    val wantsDirection = if (reverse) total.x < 0 else total.x > 0
                    if (abs(total.y) > slop && abs(total.y) > abs(total.x)) return@awaitEachGesture
                    if (abs(total.x) > slop && wantsDirection) dragging = true else continue
                }
                change.consume()
                val w = width().coerceAtLeast(1f)
                val step = delta.x / w
                progress.value = (progress.value + step).coerceIn(0f, 1f)
            }
            if (!dragging) return@awaitEachGesture
            val v = tracker.calculateVelocity().x
            val commit = if (reverse) {
                progress.value < 0.66f || v < -1000f
            } else {
                progress.value > 0.33f || v > 1000f
            }
            if (commit) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            scope.launch {
                if (reverse) {
                    progress.settle(open = !commit, spec = motion.normal())
                } else {
                    progress.settle(open = commit, spec = motion.normal())
                }
                if (commit) onCommit() else onCancel()
            }
        }
    }
}

/** How far a sheet or viewer has been pulled down, in pixels. */
@Stable
class DismissDrag {
    var offsetPx by mutableFloatStateOf(0f)
        internal set
}

/**
 * Pull down to dismiss, for sheets and the image viewer. Past 120dp or a quick flick it
 * dismisses; otherwise it springs back. [enabled] lets a zoomed image keep its drags.
 */
fun Modifier.swipeDownToDismiss(
    drag: DismissDrag,
    enabled: () -> Boolean = { true },
    onDismiss: () -> Unit,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val motion = AgentTheme.motion
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    pointerInput(Unit) {
        val limit = 120.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled()) return@awaitEachGesture
            val tracker = VelocityTracker()
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                if (over > 0 || drag.offsetPx > 0) {
                    change.consume()
                    drag.offsetPx = (drag.offsetPx + over).coerceAtLeast(0f)
                }
            } ?: return@awaitEachGesture
            tracker.addPointerInputChange(start)
            verticalDrag(start.id) { change ->
                tracker.addPointerInputChange(change)
                drag.offsetPx = (drag.offsetPx + change.positionChange().y).coerceAtLeast(0f)
                change.consume()
            }
            val v = tracker.calculateVelocity().y
            if (drag.offsetPx > limit || v > 1400f) {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                onDismiss()
                drag.offsetPx = 0f
            } else {
                scope.springBack(drag, motion.normal())
            }
        }
    }
}

private fun CoroutineScope.springBack(drag: DismissDrag, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
    launch { animate(drag.offsetPx, 0f, animationSpec = spec) { v, _ -> drag.offsetPx = v } }
}

/** Zoom and pan for an image. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var pan by androidx.compose.runtime.mutableStateOf(Offset.Zero)
        internal set
    val isZoomed: Boolean get() = scale > 1.01f

    fun zoomTo(target: Float) {
        scale = target.coerceIn(MinZoom, MaxZoom)
        if (!isZoomed) pan = Offset.Zero
    }

    companion object {
        const val MinZoom = 1f
        const val MaxZoom = 5f
    }
}

/**
 * Pinch to zoom, drag to pan once zoomed, double-tap to zoom in or back out. A single finger on
 * an unzoomed image is left alone, so the viewer's swipe-down-to-dismiss still works.
 */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .pointerInput(state) {
        detectTapGestures(onDoubleTap = { state.zoomTo(if (state.isZoomed) 1f else 2.5f) })
    }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || state.isZoomed) {
                    state.scale = (state.scale * event.calculateZoom()).coerceIn(ZoomState.MinZoom, ZoomState.MaxZoom)
                    state.pan = if (state.isZoomed) state.pan + event.calculatePan() else Offset.Zero
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
