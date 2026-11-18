package dev.wildware.agents.app.design.gesture

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * A short list you can reorder: long-press a row and drag it. The same moves are offered as
 * "Move up" and "Move down" to screen readers, and as Alt+Up / Alt+Down on a keyboard.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    label: (T) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<Any, Int>() }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val currentItems by rememberUpdatedState(items)
    val move by rememberUpdatedState(onMove)

    Column(modifier) {
        items.forEachIndexed { index, item ->
            val k = keyOf(item)
            key(k) {
                val dragging = draggingKey == k
                Column(
                    Modifier
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            if (dragging) {
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("Move ${label(item)} up") { move(index, index - 1); true })
                                if (index < items.lastIndex) add(CustomAccessibilityAction("Move ${label(item)} down") { move(index, index + 1); true })
                            }
                        }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || !e.isAltPressed) return@onKeyEvent false
                            when {
                                e.key == Key.DirectionUp && index > 0 -> { move(index, index - 1); true }
                                e.key == Key.DirectionDown && index < items.lastIndex -> { move(index, index + 1); true }
                                else -> false
                            }
                        }
                        .pointerInput(k) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingKey = k
                                    dragOffset = 0f
                                },
                                onDragEnd = { draggingKey = null; dragOffset = 0f },
                                onDragCancel = { draggingKey = null; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val from = currentItems.indexOfFirst { keyOf(it) == k }
                                    val h = heights[k] ?: return@detectDragGesturesAfterLongPress
                                    val steps = (dragOffset / h).roundToInt()
                                    val to = (from + steps).coerceIn(0, currentItems.lastIndex)
                                    if (to != from) {
                                        move(from, to)
                                        dragOffset -= (to - from) * h
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                },
                            )
                        },
                ) { row(item, dragging) }
            }
        }
    }
}
