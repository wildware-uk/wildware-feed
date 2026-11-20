package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

internal actual val shortcutsUseCommand: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }

/** No browser here: the feed's own history does Back and Forward. */
@Composable
internal actual fun FollowBrowserHistory(ui: FeedUi) = Unit

/** Watched, not taken: the press still reaches what it landed on. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
internal actual fun Modifier.mouseBackForward(ui: FeedUi): Modifier = pointerInput(ui) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press || ui.browser != null) continue
            when (event.button) {
                PointerButton.Back -> ui.goBack()
                PointerButton.Forward -> ui.goForward()
                else -> Unit
            }
        }
    }
}
