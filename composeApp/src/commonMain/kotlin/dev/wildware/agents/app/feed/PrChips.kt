package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.PrTone
import dev.wildware.agents.PrView
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/**
 * The pull requests linked to a card (server migration 079): one small chip each, such as
 * "#423 · CI failing · 1 change requested", coloured by the worst of it. A tap opens it on GitHub.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrChips(prs: List<PrView>, modifier: Modifier = Modifier) {
    if (prs.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prs.forEach { PrChip(it) }
    }
}

@Composable
private fun PrChip(pr: PrView) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val uris = LocalUriHandler.current
    val tint = prTint(pr.tone)
    Row(
        Modifier
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, if (pr.tone == PrTone.Plain) colors.border else tint.copy(alpha = 0.5f), shape)
            .pressable(pr.spoken, shape) { runCatching { uris.openUri(pr.url) } }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.PullRequest, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(pr.label, Modifier.padding(start = 6.dp), color = if (pr.tone == PrTone.Plain) colors.textMuted else tint, style = AgentTheme.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The chip's colour for [tone]: red needs someone, amber while CI runs, green when good, the accent once merged. */
@Composable
internal fun prTint(tone: PrTone): Color {
    val colors = AgentTheme.colors
    return when (tone) {
        PrTone.Bad -> colors.error
        PrTone.Waiting -> colors.warning
        PrTone.Good -> colors.success
        PrTone.Merged -> colors.accent
        PrTone.Closed, PrTone.Plain -> colors.textMuted
    }
}
