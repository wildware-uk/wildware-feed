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
 * and, for an owner or admin where the app may link to buying ([Upsell]), a note once files are
 * nearly full and "Upgrade", which opens the billing site in the browser (nothing is bought in the
 * app); or just "Team plan". Nothing at all on a self-hosted dashboard, which has no plan.
 */
@Composable
internal fun PlanEntry(ui: FeedUi) {
    // Read afresh each time Settings opens, so the use it shows is today's.
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadPlan) }
    val plan = ui.state.plan.plan ?: return
    val offer = ui.state.upsell.offer
    val colors = AgentTheme.colors
    SettingsHeading("Plan")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(plan.title, color = colors.text, style = AgentTheme.type.bodyStrong)
        for (line in plan.lines) Text(line, color = colors.textMuted, style = AgentTheme.type.body)
        val url = offer?.let(::upgradeLink)
        if (url != null) {
            Upsell.storageNote(offer)?.let { Text(it, color = colors.text, style = AgentTheme.type.body) }
            val uris = LocalUriHandler.current
            AppButton("Upgrade", ButtonTone.Primary, Modifier.padding(top = 6.dp), icon = Icons.External) { runCatching { uris.openUri(url) } }
        }
    }
}

/**
 * A slim note about the plan: [text], "Upgrade" to [url] when there is one, and a close button
 * when [onDismiss] is given. Used above the feed, in the invite form and on the agent screens.
 */
@Composable
internal fun PlanNote(text: String, url: String?, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val uris = LocalUriHandler.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp, end = if (onDismiss == null) 8.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = colors.text, style = AgentTheme.type.body)
        if (url != null) {
            AppButton("Upgrade", ButtonTone.Secondary, Modifier.padding(start = 8.dp), icon = Icons.External) { runCatching { uris.openUri(url) } }
        }
        if (onDismiss != null) IconAction(Icons.Close, "Hide", onClick = onDismiss)
    }
}

/** Above the feed, for an owner or admin of a free team whose files are 80% full: how full, and "Upgrade". */
@Composable
internal fun FilesNote(ui: FeedUi, modifier: Modifier = Modifier) {
    // Read only the offer, so a feed update that leaves it alone doesn't redraw this.
    val upsell by remember(ui) { derivedStateOf { ui.state.upsell } }
    val offer = upsell.offer?.takeIf { !upsell.storageHidden } ?: return
    val text = Upsell.storageNote(offer) ?: return
    PlanNote(text, upgradeLink(offer), modifier) { ui.emit(FeedEvent.HideFilesNote) }
}

/**
 * On the screens that let an agent in, once the free plan has room for no more: how many, and
 * what Team gives (with "Upgrade") where it may be offered, else what to do.
 */
@Composable
internal fun AgentsFullNote(ui: FeedUi, modifier: Modifier = Modifier) {
    val plan = ui.state.plan.plan ?: return
    val offer = ui.state.upsell.offer
    val text = Upsell.agentsNote(plan, offer = offer != null) ?: return
    PlanNote(text, offer?.let(::upgradeLink), modifier)
}

/** In People, for an owner or admin, once the free plan has room for no more: before an email is typed. */
@Composable
internal fun PeopleFullNote(ui: FeedUi, modifier: Modifier = Modifier) {
    if (!ui.state.team.canManage) return
    val plan = ui.state.plan.plan ?: return
    val offer = ui.state.upsell.offer
    val text = Upsell.peopleNote(plan, offer = offer != null) ?: return
    PlanNote(text, offer?.let(::upgradeLink), modifier)
}

/**
 * Now and then (at most once a week, see [Upsell.popupDue]), for an owner or admin of a free team:
 * what Team gives, "Upgrade" and "Not now". Either puts it away for another week.
 */
@Composable
fun UpsellPopup(ui: FeedUi, safeArea: SafeArea) {
    val upsell by remember(ui) { derivedStateOf { ui.state.upsell } }
    val refusal by remember(ui) { derivedStateOf { ui.state.upgrade } }
    val offer = upsell.offer?.takeIf { upsell.popup && refusal == null } ?: return
    val url = upgradeLink(offer) ?: return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val uris = LocalUriHandler.current
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
                .semantics { paneTitle = Upsell.POPUP_TITLE },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(Upsell.POPUP_TITLE, color = colors.text, style = AgentTheme.type.bodyStrong)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in Upsell.popupLines(offer)) Text("•  $line", color = colors.textMuted, style = AgentTheme.type.body)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton("Upgrade", ButtonTone.Primary, icon = Icons.External) {
                    runCatching { uris.openUri(url) }
                    ui.emit(FeedEvent.DismissUpsell)
                }
                AppButton("Not now", ButtonTone.Quiet) { ui.emit(FeedEvent.DismissUpsell) }
            }
        }
    }
}

/** Where "Upgrade" goes for [plan]: only a free team, and only when the dashboard named a place. */
internal fun upgradeLink(plan: TeamPlan): String? = plan.upgradeUrl?.takeIf { plan.free && it.isNotBlank() }

/** Where "Upgrade" goes after a free-plan refusal, or null for no button. */
internal fun upgradeLink(hit: PlanLimitHit): String? = hit.upgradeUrl?.takeIf { it.isNotBlank() }

/**
 * A free-plan refusal (a third person, a fourth agent, an upload past 500 MB): the dashboard's own
 * words, "Upgrade" when it said where, and "Not now". Over the feed until put away. Where Team may
 * not be offered (a member, an iPhone outside the US App Store) the session has already put it in
 * plain words with nowhere to go ([Upsell.refusal]), so it says what is full and just "OK".
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
                AppButton(if (url != null) "Not now" else "OK", ButtonTone.Quiet) { ui.emit(FeedEvent.DismissUpgrade) }
            }
        }
    }
}
