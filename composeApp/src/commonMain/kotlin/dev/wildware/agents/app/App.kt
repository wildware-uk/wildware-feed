package dev.wildware.agents.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.systemAccessibility
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.FeedEvent

/** The whole app. Until the live feed lands it shows [FakeFeed]. */
@Composable
fun AgentsApp(
    dark: Boolean = isSystemInDarkTheme(),
    accessibility: AccessibilityPrefs = systemAccessibility(),
    state: FeedState = FakeFeed.state,
    safeArea: SafeArea = SafeArea(),
    startAt: String? = null,
    preview: ScreenPreview = ScreenPreview(),
    onEvent: (FeedEvent) -> Unit = {},
) {
    AgentTheme(dark, accessibility) {
        FeedScreen(state, safeArea, startAt, preview, onEvent)
    }
}
