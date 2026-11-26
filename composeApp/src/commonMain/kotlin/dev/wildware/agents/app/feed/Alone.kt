package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * The card open on its own, over the feed on a wide screen: a card the feed hadn't paged to, read
 * by itself with its whole thread, so opening it never pages or scrolls the feed under it. "Back to
 * feed" (or board, or media: the tab it was opened over) puts it away, and that tab is just as it was; "Show in feed" appears once the feed has the
 * card too. (On a phone the thread screen shows it instead.)
 */
@Composable
internal fun AloneView(ui: FeedUi, maxWidth: Dp, modifier: Modifier = Modifier) {
    val item = ui.alone ?: return
    val colors = AgentTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .semantics { paneTitle = "Card on its own" }
            .onKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) { ui.closeAlone(); true } else false },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 36.dp, end = 36.dp, top = 20.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                // Back to the tab it was opened from: the feed, the board, Media.
                AppButton("Back to ${ui.tabName.lowercase()}", ButtonTone.Quiet, icon = Icons.Back) { ui.closeAlone() }
                Spacer(Modifier.weight(1f))
                if (ui.state.item(item.id) != null) AppButton("Show in feed", ButtonTone.Quiet) { ui.showAloneInFeed() }
            }
            key(item.id) {
                androidx.compose.runtime.CompositionLocalProvider(LocalDocChannel provides item.channelSlug) {
                    FeedItemView(item, selected = false, density = Density.Comfortable, modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth())
                }
            }
        }
    }
}
