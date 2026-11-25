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
 * and, for an owner or admin where Team may be offered ([Upsell]), a note once files are nearly
 * full and "Upgrade": the billing site in the browser, or on iPhone the App Store paywall
 * ([PaywallSheet]); or just "Team plan", with "Manage subscription" when the App Store bills it. Nothing at all on a self-hosted dashboard, which has no plan.
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
        val upgrade = rememberUpgrade(url)
        if (url != null && upgrade != null) {
            Upsell.storageNote(offer)?.let { Text(it, color = colors.text, style = AgentTheme.type.body) }
            AppButton("Upgrade", ButtonTone.Primary, Modifier.padding(top = 6.dp), icon = upgradeIcon()) { upgrade() }
        }
        // Paid through the App Store: changed or cancelled there, by whoever may buy.
        val store = LocalAppStore.current
        if (plan.paidByApple && Paywall.canBuy(ui.state.team.me?.role)) {
            Text("Paid through the App Store.", color = colors.textMuted, style = AgentTheme.type.body)
            if (store != null) AppButton(Paywall.MANAGE, ButtonTone.Secondary, Modifier.padding(top = 6.dp)) { store.store.manage() }
        }
    }
}

/**
 * Opens the paywall where the app sells Team itself (the iPhone, through the App Store): set where
 * the feed is drawn, null elsewhere.
 */
val LocalOpenPaywall = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * What "Upgrade" does: the paywall where the app sells Team itself (iPhone, [LocalOpenPaywall]),
 * else the billing site at [url] in the browser. Null when there is nothing to do.
 */
@Composable
internal fun rememberUpgrade(url: String?): (() -> Unit)? {
    val paywall = LocalOpenPaywall.current
    val uris = LocalUriHandler.current
    if (paywall != null) return paywall
    if (url == null) return null
    return { runCatching { uris.openUri(url) } }
}

/** The "opens elsewhere" mark on "Upgrade", only when it opens the browser. */
@Composable
private fun upgradeIcon() = if (LocalOpenPaywall.current == null) Icons.External else null

/**
 * A slim note about the plan: [text], "Upgrade" to [url] when there is one, and a close button
 * when [onDismiss] is given. Used above the feed, in the invite form and on the agent screens.
 */
@Composable
internal fun PlanNote(text: String, url: String?, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val upgrade = rememberUpgrade(url)
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
        if (url != null && upgrade != null) {
            AppButton("Upgrade", ButtonTone.Secondary, Modifier.padding(start = 8.dp), icon = upgradeIcon()) { upgrade() }
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
    val upgrade = rememberUpgrade(url) ?: return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
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
                AppButton("Upgrade", ButtonTone.Primary, icon = upgradeIcon()) {
                    upgrade()
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
    val url = upgradeLink(hit)
    val upgrade = rememberUpgrade(url)
    // With nowhere to upgrade (a member, an iPhone outside the US App Store) it names no plan.
    val title = if (url != null) "The free plan is full" else "No room for that"
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
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = colors.text, style = AgentTheme.type.bodyStrong)
            Text(hit.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textMuted, style = AgentTheme.type.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (url != null && upgrade != null) {
                    AppButton("Upgrade", ButtonTone.Primary, icon = upgradeIcon()) {
                        upgrade()
                        ui.emit(FeedEvent.DismissUpgrade)
                    }
                }
                AppButton(if (url != null) "Not now" else "OK", ButtonTone.Quiet) { ui.emit(FeedEvent.DismissUpgrade) }
            }
        }
    }
}
