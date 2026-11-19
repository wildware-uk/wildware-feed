package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * A red bar across the top of the desktop feed while something marked critical wants the owner:
 * a question still waiting, or an update not yet read. Open goes to it; Dismiss puts it away for
 * this run of the app. The notification says it once; this keeps saying it until it is dealt with.
 */
@Composable
fun CriticalBanner(ui: FeedUi) {
    val dismissed = remember { mutableStateListOf<String>() }
    val shown = ui.state.critical.filter { it.id !in dismissed }
    val first = shown.firstOrNull() ?: return
    val more = shown.size - 1
    val spoken = "Critical from ${first.agent}: ${first.text}" + if (more > 0) ", and $more more" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .background(CriticalRed)
            .semantics { contentDescription = spoken; liveRegion = LiveRegionMode.Assertive }
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Bell, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text("CRITICAL", Modifier.padding(start = 8.dp), color = Color.White, style = AgentTheme.type.overline)
        Text(
            "${first.agent}: ${first.text}" + if (more > 0) "  ·  $more more" else "",
            Modifier.padding(start = 12.dp).weight(1f),
            color = Color.White,
            style = AgentTheme.type.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BannerAction(if (first.question) "Answer" else "Open") { ui.reveal(first.id) }
        BannerAction("Dismiss") { dismissed += first.id }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    val shape = AgentTheme.shapes.pill
    Text(
        label,
        Modifier
            .padding(start = 6.dp)
            .clip(shape)
            .pressable(label, shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White,
        style = AgentTheme.type.label,
    )
}
