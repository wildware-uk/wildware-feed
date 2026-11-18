package dev.wildware.agents.app.design.gesture

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** One line in a context menu, also offered to screen readers as a custom action. */
@Immutable
data class MenuAction(
    val label: String,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/**
 * The gestures every card and comment answers to:
 * - long-press (touch) or right-click (mouse) opens its menu,
 * - double-tap reacts with the quick emoji,
 * - Shift+F10 or the Menu key opens the menu from the keyboard,
 * - and every action is listed for screen readers, so none of it needs a gesture.
 */
fun Modifier.contextGestures(
    /** Opens the menu; given where the pointer was, or zero from a key or screen reader. */
    openMenu: (Offset) -> Unit,
    quickReact: (() -> Unit)?,
    actions: List<MenuAction>,
    menuLabel: String = "Show actions",
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    this
        .semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(menuLabel) { openMenu(Offset.Zero); true })
                actions.forEach { a -> add(CustomAccessibilityAction(a.label) { a.run(); true }) }
            }
        }
        .pointerInput(openMenu) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    // A press something inside already took (selectable text) is left to it.
                    val free = event.changes.none { it.isConsumed }
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && free) {
                        event.changes.forEach { it.consume() }
                        openMenu(event.changes.first().position)
                    }
                }
            }
        }
        .pointerInput(openMenu, quickReact) {
            detectTapGestures(
                onLongPress = { at ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    openMenu(at)
                },
                onDoubleTap = quickReact?.let { react ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        react()
                    }
                },
            )
        }
        .onKeyEvent { e ->
            val menuKey = e.key == Key.Menu || (e.key == Key.F10 && e.isShiftPressed)
            if (e.type == KeyEventType.KeyUp && menuKey) {
                openMenu(Offset.Zero)
                true
            } else false
        }
        .focusable()
}
