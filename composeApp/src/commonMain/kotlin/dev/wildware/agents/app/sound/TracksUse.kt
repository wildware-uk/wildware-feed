package dev.wildware.agents.app.sound

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Tells [activity] when the window gains or loses focus and whenever the owner touches, clicks,
 * scrolls or types anywhere inside. It only listens: every event still reaches the app.
 */
@Composable
fun Modifier.tracksUse(activity: OwnerActivity): Modifier {
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(activity, focused) { activity.focus(focused) }
    return pointerInput(activity) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                activity.input()
            }
        }
    }.onPreviewKeyEvent {
        activity.input()
        false
    }
}
