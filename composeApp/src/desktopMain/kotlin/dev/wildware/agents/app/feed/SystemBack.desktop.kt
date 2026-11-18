package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable

/** No system back here: the app's own edge swipe and Escape do it. */
internal actual val PlatformSystemBack: SystemBack = object : SystemBack {
    override val ownsLeftEdge = false

    @Composable
    override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) = Unit
}
