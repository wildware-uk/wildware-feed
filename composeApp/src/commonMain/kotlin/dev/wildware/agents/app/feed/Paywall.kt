package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.TeamPlan
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.session.AppStorePurchases
import dev.wildware.agents.app.session.PurchaseState
import dev.wildware.agents.app.session.StoreProduct
import kotlinx.coroutines.flow.StateFlow

/**
 * Buying Team in the iPhone app (App Store in-app purchase), and every word the paywall says.
 *
 * App Store guidelines 3.1.1 and 3.1.3: only on the United States storefront may the app point to
 * paying on the website ([transferLink], with [TRANSFER]). Everywhere else the paywall shows the
 * price, how it breaks down where it can, and nothing that leads outside the app to buy.
 */
object Paywall {
    const val TEAM5 = "dev.wildware.feed.team5.monthly"
    const val TEAM10 = "dev.wildware.feed.team10.monthly"
    val PRODUCTS = listOf(TEAM5, TEAM10)

    const val TITLE = "Upgrade to Team"
    const val LEAD = "Team gives you room for more people, as many agents as you like, more room for files, and all your history."
    const val TRANSFER = "For more flexibility and to avoid Apple service charges, transfer to our website for payment."
    /** The short button under [TRANSFER]: the sentence itself wraps, a button's text would not. */
    const val TRANSFER_BUTTON = "Go to our website"
    const val SERVICE_CHARGE = "Apple service charge"
    const val INCLUDES = "includes Apple's service charge"
    const val MEMBERS = "Only the team's owner or an admin can change its plan."
    const val SUBSCRIBE = "Subscribe"
    const val RESTORE = "Restore purchases"
    const val MANAGE = "Manage subscription"
    const val LOADING = "Getting prices from the App Store…"
    const val FINE_PRINT =
        "Monthly. Renews automatically each month until you cancel. Cancel any time in Settings on your iPhone, at least a day before it renews. " +
            "Payment is taken by your Apple ID."
    const val TERMS_URL = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/"
    const val PRIVACY_URL = "https://github.com/wildware-uk/wildware-feed/blob/master/PRIVACY.md"

    /** What the same logins cost a month on the website, in US cents: $29 for 5, and $6 for each one after. */
    private val WEB_CENTS = mapOf(TEAM5 to 2900L, TEAM10 to 5900L)
    private val LOGINS = mapOf(TEAM5 to 5, TEAM10 to 10)

    data class Line(val label: String, val amount: String)

    /**
     * One thing to buy as the paywall shows it: "Team 5", "5 logins", the breakdown ([lines]: the
     * website's price and Apple's service charge, in US dollars only), the [total] a month, and [note]
     * ("includes Apple's service charge") where the breakdown can't be given. [summary] is all of it
     * in one line, for screen readers.
     */
    data class Choice(val productId: String, val name: String, val logins: String, val lines: List<Line>, val total: String, val note: String?, val summary: String)

    /** The Team products in order (Team 5, then Team 10), as far as the App Store has them. */
    fun choices(products: List<StoreProduct>): List<Choice> = PRODUCTS.mapNotNull { id -> products.firstOrNull { it.productId == id }?.let(::choice) }

    fun choice(p: StoreProduct): Choice {
        val n = LOGINS[p.productId] ?: 0
        val name = if (n > 0) "Team $n" else p.displayName
        val logins = "$n logins"
        val cents = cents(p.price)
        val web = WEB_CENTS[p.productId]
        // The split is real only in dollars, against the website's dollar price. Anywhere else, the total.
        if (p.currencyCode == "USD" && cents != null && web != null && cents > web) {
            val total = "${usd(cents)} a month"
            return Choice(
                p.productId, name, logins,
                listOf(Line("Team", usd(web)), Line(SERVICE_CHARGE, usd(cents - web))),
                total, note = null,
                summary = "$name, $logins: Team ${usd(web)} + $SERVICE_CHARGE ${usd(cents - web)} = $total",
            )
        }
        val total = "${p.displayPrice} a month"
        return Choice(p.productId, name, logins, emptyList(), total, INCLUDES, "$name, $logins: $total, $INCLUDES")
    }

    /** Where [TRANSFER] goes: the billing site, only where the app may link to it (the US App Store, [buyingLinks]). */
    fun transferLink(buyingLinks: Boolean, plan: TeamPlan?): String? = plan?.upgradeUrl?.takeIf { buyingLinks && it.isNotBlank() }

    /** Only an owner or admin is offered anything to buy. */
    fun canBuy(role: String?): Boolean = role == "owner" || role == "admin"

    /** What to say about the purchase under way, or null for nothing. */
    fun words(state: PurchaseState): String? = when (state) {
        PurchaseState.Idle -> null
        PurchaseState.Buying -> "Waiting for the App Store…"
        PurchaseState.Restoring -> "Asking the App Store for your purchases…"
        PurchaseState.Pending -> "Your purchase is waiting for approval. Your team moves to Team once it goes through."
        PurchaseState.Cancelled -> null
        PurchaseState.Bought -> "Bought. Moving your team to Team…"
        is PurchaseState.Failed -> state.message
    }

    /** What the dashboard's answer about a purchase means, for the paywall. Null: done, the team is on Team. */
    fun relayWords(status: Int, error: String?, message: String?): String? = when {
        status == 200 -> null
        error == "paid_on_web" -> "Your team already pays on our website, so nothing changed. We've emailed the owner how to cancel the App Store subscription."
        error == "wrong_team" -> "That purchase was made for another team."
        status == 409 || status == 400 -> message ?: "The App Store purchase couldn't be used for this team."
        else -> "Your purchase went through. Your team will move to Team shortly."
    }

    private fun cents(price: String): Long? {
        val m = Regex("^(\\d+)(?:\\.(\\d{1,2})\\d*)?$").matchEntire(price.trim()) ?: return null
        val frac = (m.groupValues[2]).padEnd(2, '0')
        return m.groupValues[1].toLong() * 100 + frac.toLong()
    }

    private fun usd(cents: Long): String = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

/** The App Store's in-app purchase where the app has it (iPhone), and whether it may link to the website (US). */
class AppStoreUi(val store: AppStorePurchases, val buyingLinks: StateFlow<Boolean>)

val LocalAppStore = staticCompositionLocalOf<AppStoreUi?> { null }

/**
 * The paywall: Team 5 and Team 10 with their prices, "Subscribe", "Restore purchases", and on the US
 * App Store the line about paying on the website ([TRANSFER]). A member sees only who can buy.
 * For a team already paid through the App Store: "Manage subscription".
 */
@Composable
fun PaywallSheet(ui: FeedUi, safeArea: SafeArea) {
    val appStore = LocalAppStore.current ?: return
    val store = appStore.store
    val colors = AgentTheme.colors
    val uris = LocalUriHandler.current
    val plan = ui.state.plan.plan
    val role = ui.state.team.me?.role
    val products by store.products.collectAsState()
    val purchase by store.purchase.collectAsState()
    val links by appStore.buyingLinks.collectAsState()
    var told by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        store.load(Paywall.PRODUCTS)
        ui.emit(FeedEvent.ReadPlan)
    }
    val close = {
        store.clearPurchase()
        ui.paywallOpen = false
    }
    SettingsSheet(Paywall.TITLE, safeArea, onClose = close) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !Paywall.canBuy(role) -> Text(Paywall.MEMBERS, color = colors.text, style = AgentTheme.type.body)
                plan == null -> Text("This dashboard has no plan to buy.", color = colors.textMuted, style = AgentTheme.type.body)
                plan.paidByApple -> {
                    Text("Your team is on Team, paid through the App Store.", color = colors.text, style = AgentTheme.type.body)
                    AppButton(Paywall.MANAGE, ButtonTone.Secondary) { store.manage() }
                }
                !plan.free -> Text("Your team is on Team.", color = colors.text, style = AgentTheme.type.body)
                else -> {
                    Text(Paywall.LEAD, color = colors.text, style = AgentTheme.type.body)
                    val choices = Paywall.choices(products)
                    if (choices.isEmpty()) Text(Paywall.LOADING, color = colors.textMuted, style = AgentTheme.type.body)
                    val token = plan.appAccountToken
                    val busy = purchase == PurchaseState.Buying || purchase == PurchaseState.Restoring || purchase == PurchaseState.Bought
                    for (choice in choices) {
                        ChoiceCard(choice, enabled = token != null && !busy) {
                            told = null
                            ui.emit(FeedEvent.BuyTeam(choice.productId) { told = it })
                        }
                    }
                    AppButton(Paywall.RESTORE, ButtonTone.Quiet, enabled = !busy) {
                        told = null
                        ui.emit(FeedEvent.RestorePurchases { told = it })
                    }
                }
            }
            (told ?: Paywall.words(purchase))?.let { Text(it, color = colors.text, style = AgentTheme.type.body) }
            if (Paywall.canBuy(role) && plan != null && (plan.free || plan.paidByApple)) {
                // The US App Store only (guidelines 3.1.1, 3.1.3): nowhere else may the app point to the website.
                Paywall.transferLink(links, plan)?.let { url ->
                    Text(Paywall.TRANSFER, color = colors.text, style = AgentTheme.type.bodyStrong)
                    AppButton(Paywall.TRANSFER_BUTTON, ButtonTone.Secondary, icon = Icons.External) { runCatching { uris.openUri(url) } }
                }
                Text(Paywall.FINE_PRINT, color = colors.textMuted, style = AgentTheme.type.body)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Terms of Use", ButtonTone.Quiet) { runCatching { uris.openUri(Paywall.TERMS_URL) } }
                    AppButton("Privacy", ButtonTone.Quiet) { runCatching { uris.openUri(Paywall.PRIVACY_URL) } }
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(choice: Paywall.Choice, enabled: Boolean, onBuy: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, shape)
            .semantics(mergeDescendants = true) { contentDescription = choice.summary }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(choice.name, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.bodyStrong)
            Text(choice.logins, color = colors.textMuted, style = AgentTheme.type.body)
        }
        for (line in choice.lines) {
            Row {
                Text(line.label, Modifier.weight(1f), color = colors.textMuted, style = AgentTheme.type.body)
                Text(line.amount, color = colors.textMuted, style = AgentTheme.type.body)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(choice.total, color = colors.text, style = AgentTheme.type.bodyStrong)
                choice.note?.let { Text(it, color = colors.textMuted, style = AgentTheme.type.body) }
            }
            AppButton(Paywall.SUBSCRIBE, ButtonTone.Primary, enabled = enabled, onClick = onBuy)
        }
    }
}
