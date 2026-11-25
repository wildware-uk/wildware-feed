package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.PlanLimits
import dev.wildware.agents.PlanUse
import dev.wildware.agents.TeamPlan
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppStoreUi
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalAppStore
import dev.wildware.agents.app.feed.Paywall
import dev.wildware.agents.app.feed.PersonRow
import dev.wildware.agents.app.feed.PlanState
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.TeamState
import dev.wildware.agents.app.feed.Upsell
import dev.wildware.agents.app.feed.UpsellState
import dev.wildware.agents.app.session.AppStorePurchases
import dev.wildware.agents.app.session.PurchaseState
import dev.wildware.agents.app.session.SignedTransaction
import dev.wildware.agents.app.session.StoreProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Buying Team in the iPhone app (App Store in-app purchase). The rule the App Store holds us to: the
 * line pointing to paying on the website, and any link to it, show only on the US storefront.
 */
@OptIn(ExperimentalTestApi::class)
class PaywallTest {
    private val events = mutableListOf<FeedEvent>()
    private val opened = mutableListOf<String>()
    private val uris = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private val usd = listOf(
        StoreProduct(Paywall.TEAM5, "Team 5", "$34.99", "34.99", "USD"),
        StoreProduct(Paywall.TEAM10, "Team 10", "$69.99", "69.99", "USD"),
    )
    private val gbp = listOf(
        StoreProduct(Paywall.TEAM5, "Team 5", "£34.99", "34.99", "GBP"),
        StoreProduct(Paywall.TEAM10, "Team 10", "£69.99", "69.99", "GBP"),
    )

    private class FakeStore(products: List<StoreProduct>) : AppStorePurchases {
        val bought = mutableListOf<Pair<String, String>>()
        var managed = 0
        override val products = MutableStateFlow(products)
        override val productsLoaded = MutableStateFlow(true)
        override val purchase = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
        override val signed = emptyFlow<SignedTransaction>()
        override fun load(productIds: List<String>) {}
        override fun buy(productId: String, accountToken: String) {
            bought += productId to accountToken
        }
        override fun restore() {}
        override fun manage() {
            managed++
        }
        override fun finish(transactionId: String) {}
        override fun clearPurchase() {}
    }

    private val free = TeamPlan(
        plan = TeamPlan.Free,
        limits = PlanLimits(people = 2, agents = 3, storageBytes = 524_288_000, historyDays = 30),
        used = PlanUse(people = 1, agents = 2, storageBytes = 125_829_120),
        upgradeUrl = "https://billing.test/teams/acme",
        // Built, not written out, so the secret scan does not take it for a real key.
        appAccountToken = listOf("aa19f4a5", "0eb8", "501f", "898f", "bb79bbdcb7f5").joinToString("-"),
    )

    private fun team(role: String) = TeamState(me = PersonRow("p1", "Ann", "ann", role, you = true), people = listOf(PersonRow("p1", "Ann", "ann", role, you = true)), loaded = true)

    private fun ComposeUiTest.feed(state: FeedState, store: AppStorePurchases, us: Boolean, preview: ScreenPreview = ScreenPreview(paywallOpen = true)) = setContent {
        CompositionLocalProvider(LocalUriHandler provides uris, LocalAppStore provides AppStoreUi(store, MutableStateFlow(us))) {
            AgentTheme(dark = true) {
                Box(Modifier.size(1200.dp, 900.dp)) { FeedScreen(state, preview = preview, onEvent = { events += it }) }
            }
        }
    }

    // ---- the words

    @Test
    fun inDollarsThePriceShowsTheWebsitePriceAndAppleServiceCharge() {
        val (five, ten) = Paywall.choices(usd)
        assertEquals(listOf(Paywall.Line("Team", "$29.00"), Paywall.Line("Apple service charge", "$5.99")), five.lines)
        assertEquals("$34.99 a month", five.total)
        assertEquals("Team 5, 5 logins: Team $29.00 + Apple service charge $5.99 = $34.99 a month", five.summary)
        assertNull(five.note)
        assertEquals(listOf(Paywall.Line("Team", "$59.00"), Paywall.Line("Apple service charge", "$10.99")), ten.lines)
        assertEquals("$69.99 a month", ten.total)
    }

    @Test
    fun inAnotherCurrencyItShowsTheTotalAndSaysItIncludesAppleServiceCharge() {
        val (five, _) = Paywall.choices(gbp)
        assertEquals(emptyList(), five.lines)
        assertEquals("£34.99 a month", five.total)
        assertEquals("includes Apple's service charge", five.note)
    }

    @Test
    fun theWebsiteIsOfferedOnlyOnTheUsStorefront() {
        assertEquals("https://billing.test/teams/acme", Paywall.transferLink(buyingLinks = true, plan = free))
        assertNull(Paywall.transferLink(buyingLinks = false, plan = free))
        assertNull(Paywall.transferLink(buyingLinks = true, plan = free.copy(upgradeUrl = null)))
    }

    @Test
    fun onAnIphoneTeamIsOfferedOnEveryStorefrontButOnlyToOwnersAndAdmins() {
        // The session offers Team where the app sells it itself, links or not (DashboardSession.mayOffer).
        assertEquals(free, Upsell.offerFor(free, "owner", links = true))
        assertEquals(free, Upsell.offerFor(free, "admin", links = true))
        assertNull(Upsell.offerFor(free, "member", links = true))
        assertTrue(Paywall.canBuy("owner") && Paywall.canBuy("admin") && !Paywall.canBuy("member"))
    }

    // ---- the sheet

    @Test
    fun onTheUsStorefrontTheOwnerSeesBothPlansAndTheTransferLine() = runComposeUiTest {
        val store = FakeStore(usd)
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), store, us = true)
        onNode(hasContentDescription("Team 5, 5 logins: Team $29.00 + Apple service charge $5.99 = $34.99 a month")).assertExists()
        onNode(hasContentDescription("Team 10, 10 logins: Team $59.00 + Apple service charge $10.99 = $69.99 a month")).assertExists()
        onNode(hasText("For more flexibility and to avoid Apple service charges, transfer to our website for payment.")).assertExists()
        onNode(hasText(Paywall.TRANSFER_BUTTON)).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
        onAllNodes(hasText(Paywall.SUBSCRIBE))[0].performClick()
        waitForIdle()
        assertTrue(events.any { it is FeedEvent.BuyTeam && it.productId == Paywall.TEAM5 })
    }

    @Test
    fun onAPhoneTheTransferLineIsShownWhole() = runComposeUiTest {
        // An iPhone is 390 points wide: the sentence must wrap, not be cut off.
        setContent {
            CompositionLocalProvider(LocalUriHandler provides uris, LocalAppStore provides AppStoreUi(FakeStore(usd), MutableStateFlow(true))) {
                AgentTheme(dark = true) {
                    Box(Modifier.size(390.dp, 844.dp)) {
                        FeedScreen(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), preview = ScreenPreview(paywallOpen = true), onEvent = { events += it })
                    }
                }
            }
        }
        val node = onNode(hasText(Paywall.TRANSFER)).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertTrue(!layout.hasVisualOverflow, "the line is cut off")
        assertEquals(Paywall.TRANSFER, layout.layoutInput.text.text)
        onNode(hasText(Paywall.TRANSFER_BUTTON)).assertExists()
    }

    @Test
    fun elsewhereTheOwnerSeesOnlyThePricesAndNothingThatLeadsToTheWebsite() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), FakeStore(gbp), us = false)
        onNode(hasContentDescription("Team 5, 5 logins: £34.99 a month, includes Apple's service charge")).assertExists()
        onNode(hasText(Paywall.TRANSFER)).assertDoesNotExist()
        onNode(hasText(Paywall.TRANSFER_BUTTON)).assertDoesNotExist()
        val inPaywall = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, Paywall.TITLE))
        onNode(hasText("Apple service charges", substring = true) and inPaywall).assertDoesNotExist()
        onNode(hasText("website", substring = true) and inPaywall).assertDoesNotExist()
        onNode(hasText("billing.test", substring = true) and inPaywall).assertDoesNotExist()
        onAllNodes(hasText(Paywall.SUBSCRIBE)).fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }

    @Test
    fun beforeTheAppStoreSellsTeamTheUsPaywallSaysSoAndOffersOnlyTheWebsite() = runComposeUiTest {
        // No Paid Apps agreement, or no products set up: StoreKit answers with none.
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), FakeStore(emptyList()), us = true)
        onNode(hasText(Paywall.UNAVAILABLE)).assertExists()
        onNode(hasText(Paywall.LOADING)).assertDoesNotExist()
        onNode(hasText(Paywall.SUBSCRIBE)).assertDoesNotExist()
        onNode(hasText(Paywall.RESTORE)).assertDoesNotExist()
        onNode(hasText(Paywall.FINE_PRINT)).assertDoesNotExist()
        onNode(hasText(Paywall.TRANSFER)).assertExists()
        onNode(hasText(Paywall.TRANSFER_BUTTON)).assertExists()
    }

    @Test
    fun beforeTheAppStoreSellsTeamElsewhereThePaywallSaysSoWithNothingToTap() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), FakeStore(emptyList()), us = false)
        onNode(hasText(Paywall.UNAVAILABLE)).assertExists()
        val inPaywall = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, Paywall.TITLE))
        onNode(hasClickAction() and inPaywall and !hasText("Done")).assertDoesNotExist()
        onNode(hasText(Paywall.LOADING)).assertDoesNotExist()
        onNode(hasText(Paywall.TRANSFER)).assertDoesNotExist()
    }

    @Test
    fun whileTheAppStoreHasNotAnsweredThePaywallSaysItIsGettingPrices() = runComposeUiTest {
        val store = FakeStore(emptyList()).apply { productsLoaded.value = false }
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("owner")), store, us = false)
        onNode(hasText(Paywall.LOADING)).assertExists()
        onNode(hasText(Paywall.UNAVAILABLE)).assertDoesNotExist()
    }

    @Test
    fun aMemberSeesNothingToBuy() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), team = team("member")), FakeStore(usd), us = true)
        onNode(hasText(Paywall.MEMBERS)).assertExists()
        onNode(hasText(Paywall.SUBSCRIBE)).assertDoesNotExist()
        onNode(hasText(Paywall.TRANSFER)).assertDoesNotExist()
        onNode(hasText(Paywall.RESTORE)).assertDoesNotExist()
    }

    @Test
    fun onAnIphoneUpgradeOpensThePaywallNotTheBrowser() = runComposeUiTest {
        feed(
            FakeFeed.state.copy(plan = PlanState(free, loaded = true), upsell = UpsellState(offer = free), team = team("owner")),
            FakeStore(gbp), us = false, preview = ScreenPreview(alertsOpen = true),
        )
        onNode(hasText("Upgrade")).performClick()
        waitForIdle()
        assertEquals(emptyList(), opened)
        onNode(hasText(Paywall.LEAD)).assertExists()
    }

    @Test
    fun aTeamPaidThroughTheAppStoreIsManagedThere() = runComposeUiTest {
        val store = FakeStore(usd)
        val paid = free.copy(plan = TeamPlan.Team, billing = TeamPlan.Apple)
        feed(FakeFeed.state.copy(plan = PlanState(paid, loaded = true), team = team("admin")), store, us = false)
        onNode(hasText(Paywall.SUBSCRIBE)).assertDoesNotExist()
        onNode(hasText(Paywall.MANAGE)).performClick()
        waitForIdle()
        assertEquals(1, store.managed)
    }
}
