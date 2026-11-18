package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable

/** No system back here: the app's own edge swipe and Escape do it. */
@Composable
internal actual fun SystemBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) = Unit
