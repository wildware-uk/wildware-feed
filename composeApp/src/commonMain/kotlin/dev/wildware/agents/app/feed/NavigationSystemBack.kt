package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * A [SystemBack] on Compose's navigation events: whatever back input the platform feeds them. On
 * the iPhone that is UIKit's screen-edge pan from the left, which Compose installs on the window
 * while a handler is enabled.
 */
internal class NavigationSystemBack(override val ownsLeftEdge: Boolean) : SystemBack {
    @Composable
    override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
        val progress by rememberUpdatedState(onProgress)
        val state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = state,
            isBackEnabled = enabled,
            onBackCancelled = onCancel,
            onBackCompleted = onBack,
        )
        // The swipe as it happens: where the finger is, for the screen to follow it.
        LaunchedEffect(state) {
            snapshotFlow { state.transitionState }.collect { transition ->
                if (transition is NavigationEventTransitionState.InProgress) {
                    val event = transition.latestEvent
                    progress(BackSwipe(event.progress, event.touchX))
                }
            }
        }
    }
}
