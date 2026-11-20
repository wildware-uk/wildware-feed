package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val shortcutsUseCommand: Boolean = false

/** No browser here: system back goes through the feed's own history. */
@Composable
internal actual fun FollowBrowserHistory(ui: FeedUi) = Unit

/** Not seen here: Android sends the mouse's Back as system back. */
internal actual fun Modifier.mouseBackForward(ui: FeedUi): Modifier = this
