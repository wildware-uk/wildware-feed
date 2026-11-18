package dev.wildware.agents.app.feed

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException

/** Android's predictive back: the gesture's progress as it happens, then back or cancel. */
@Composable
internal actual fun SystemBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val progress by rememberUpdatedState(onProgress)
    val cancel by rememberUpdatedState(onCancel)
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress(it.progress) }
            back()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }
}
