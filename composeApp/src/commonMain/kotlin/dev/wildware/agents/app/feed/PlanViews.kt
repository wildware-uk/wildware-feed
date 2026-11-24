package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.PlanLimitHit
import dev.wildware.agents.TeamPlan
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * The team's plan in Settings, for a hosted team: "Free plan" with its limits and what is used,
 * and "Upgrade", which opens the billing site in the browser (nothing is bought in the app); or
 * just "Team plan". Nothing at all on a self-hosted dashboard, which has no plan.
 */
@Composable
internal fun PlanEntry(ui: FeedUi) {
    // Read afresh each time Settings opens, so the use it shows is today's.
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadPlan) }
    val plan = ui.state.plan.plan ?: return
    val colors = AgentTheme.colors
    SettingsHeading("Plan")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.title, color = colors.text, style = AgentTheme.type.bodyStrong)
        for (line in plan.lines) Text(line, color = colors.textMuted, style = AgentTheme.type.body)
        val url = upgradeLink(plan)
        if (url != null) {
            val uris = LocalUriHandler.current
            AppButton("Upgrade", ButtonTone.Primary, Modifier.padding(top = 6.dp), icon = Icons.External) { runCatching { uris.openUri(url) } }
        }
    }
}

/** Where "Upgrade" goes for [plan]: only a free team, and only when the dashboard named a place. */
internal fun upgradeLink(plan: TeamPlan): String? = plan.upgradeUrl?.takeIf { plan.free && it.isNotBlank() }

/** Where "Upgrade" goes after a free-plan refusal, or null for no button. */
internal fun upgradeLink(hit: PlanLimitHit): String? = hit.upgradeUrl?.takeIf { it.isNotBlank() }

/**
 * A free-plan refusal (a third person, a fourth agent, an upload past 500 MB): the dashboard's own
 * words, "Upgrade" when it said where, and "Not now". Over the feed until put away.
 */
@Composable
fun UpgradePrompt(ui: FeedUi, safeArea: SafeArea) {
    // Read only the refusal, so a feed update that leaves it alone doesn't redraw this.
    val refusal by remember(ui) { derivedStateOf { ui.state.upgrade } }
    val hit = refusal ?: return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val uris = LocalUriHandler.current
    val url = upgradeLink(hit)
    Box(Modifier.fillMaxSize().padding(bottom = safeArea.bottom + 24.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .padding(16.dp)
                .semantics { paneTitle = "The free plan is full" },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("The free plan is full", color = colors.text, style = AgentTheme.type.bodyStrong)
            Text(hit.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textMuted, style = AgentTheme.type.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (url != null) {
                    AppButton("Upgrade", ButtonTone.Primary, icon = Icons.External) {
                        runCatching { uris.openUri(url) }
                        ui.emit(FeedEvent.DismissUpgrade)
                    }
                }
                AppButton("Not now", ButtonTone.Quiet) { ui.emit(FeedEvent.DismissUpgrade) }
            }
        }
    }
}
