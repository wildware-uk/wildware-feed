package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** An iPad's keyboard has ⌘. */
internal actual val shortcutsUseCommand: Boolean = true

/** No browser here: the feed's own history does Back and Forward. */
@Composable
internal actual fun FollowBrowserHistory(ui: FeedUi) = Unit

/** Not seen here: no side buttons to speak of. */
internal actual fun Modifier.mouseBackForward(ui: FeedUi): Modifier = this
