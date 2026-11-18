package dev.wildware.agents.app.feed

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android's predictive back: the gesture's progress as it happens, then back or cancel. The left
 * edge stays the system's (back leaves the app at the top), so the app's own edge drag opens the drawer.
 */
internal actual val PlatformSystemBack: SystemBack = object : SystemBack {
    override val ownsLeftEdge = false

    @Composable
    override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
        val progress by rememberUpdatedState(onProgress)
        val cancel by rememberUpdatedState(onCancel)
        val back by rememberUpdatedState(onBack)
        PredictiveBackHandler(enabled) { events ->
            try {
                events.collect { progress(BackSwipe(it.progress, it.touchX)) }
                back()
            } catch (e: CancellationException) {
                cancel()
                throw e
            }
        }
    }
}
