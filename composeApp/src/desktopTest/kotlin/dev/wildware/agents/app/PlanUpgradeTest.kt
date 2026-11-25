package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.PlanLimitHit
import dev.wildware.agents.PlanLimits
import dev.wildware.agents.PlanUse
import dev.wildware.agents.TeamPlan
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.PlanState
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.UpsellState
import dev.wildware.agents.app.feed.TeamState
import dev.wildware.agents.app.feed.PersonRow
import androidx.compose.ui.test.hasContentDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The free plan in the app: its limits in Settings, and "Upgrade" when an action passes one. Here "Upgrade" opens the billing site; on iPhone, the App Store paywall (PaywallTest). */
@OptIn(ExperimentalTestApi::class)
class PlanUpgradeTest {
    private val events = mutableListOf<FeedEvent>()
    private val opened = mutableListOf<String>()
    private val uris = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private val free = TeamPlan(
        plan = TeamPlan.Free,
        limits = PlanLimits(people = 2, agents = 3, storageBytes = 524_288_000, historyDays = 30),
        used = PlanUse(people = 1, agents = 2, storageBytes = 125_829_120),
        upgradeUrl = "https://billing.test/teams/acme",
    )

    private fun ComposeUiTest.feed(state: FeedState, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalUriHandler provides uris) {
            AgentTheme(dark = true) {
                Box(Modifier.size(1200.dp, 900.dp)) { FeedScreen(state, preview = preview, onEvent = { events += it }) }
            }
        }
    }

    @Test
    fun aRefusalShowsTheWordsAndUpgradeOpensTheBillingSite() = runComposeUiTest {
        val hit = PlanLimitHit("The free plan has room for 2 people. Upgrade to Team to add more.", "people", "https://billing.test/teams/acme")
        feed(FakeFeed.state.copy(upgrade = hit))
        onNode(hasText("The free plan is full")).assertExists()
        onNode(hasText(hit.message)).assertExists()
        onNode(hasText("Upgrade")).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
        assertTrue(FeedEvent.DismissUpgrade in events)
    }

    @Test
    fun aRefusalWithNowhereToUpgradeHasNoUpgradeButton() = runComposeUiTest {
        feed(FakeFeed.state.copy(upgrade = PlanLimitHit("The free plan has room for 3 agents.", "agents", null)))
        onNode(hasText("The free plan has room for 3 agents.")).assertExists()
        onNode(hasText("No room for that")).assertExists()
        onNode(hasText("Upgrade")).assertDoesNotExist()
        onNode(hasText("Not now") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "No room for that"))).assertDoesNotExist()
        onNode(hasText("OK") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "No room for that"))).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DismissUpgrade), events.filter { it == FeedEvent.DismissUpgrade })
        assertEquals(emptyList(), opened)
    }

    @Test
    fun settingsShowTheFreePlanLimitsAndUpgrade() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true), upsell = UpsellState(offer = free)), ScreenPreview(alertsOpen = true))
        onNode(hasText("Free plan")).assertExists()
        onNode(hasText("People 1 of 2")).assertExists()
        onNode(hasText("Agents 2 of 3")).assertExists()
        onNode(hasText("Files 120 MB of 500 MB")).assertExists()
        onNode(hasText("History: last 30 days")).assertExists()
        assertTrue(FeedEvent.ReadPlan in events)
        onNode(hasText("Upgrade")).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
    }

    @Test
    fun settingsShowTheLimitsButNoUpgradeWhereTeamIsNotOffered() = runComposeUiTest {
        // A member, or an iPhone outside the US App Store: the facts, and nothing to buy.
        feed(FakeFeed.state.copy(plan = PlanState(free.copy(used = PlanUse(storageBytes = 450L * 1024 * 1024)), loaded = true)), ScreenPreview(alertsOpen = true))
        onNode(hasText("Free plan")).assertExists()
        onNode(hasText("Upgrade")).assertDoesNotExist()
        onNode(hasText("Team gives you more room", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aFullFilesNoteSitsAboveTheFeedWithUpgradeAndHides() = runComposeUiTest {
        val nearlyFull = free.copy(used = PlanUse(people = 1, agents = 1, storageBytes = 412L * 1024 * 1024))
        feed(FakeFeed.state.copy(plan = PlanState(nearlyFull, loaded = true), upsell = UpsellState(offer = nearlyFull)))
        onNode(hasText("412 MB of 500 MB used. Team gives you more room.")).assertExists()
        onNode(hasText("Upgrade")).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
        onNode(hasContentDescription("Hide")).performClick()
        waitForIdle()
        assertTrue(FeedEvent.HideFilesNote in events)
    }

    @Test
    fun noFilesNoteBelowEightyPercentOrOnceHidden() = runComposeUiTest {
        val nearlyFull = free.copy(used = PlanUse(storageBytes = 412L * 1024 * 1024))
        feed(FakeFeed.state.copy(upsell = UpsellState(offer = nearlyFull, storageHidden = true)))
        onNode(hasText("Team gives you more room", substring = true)).assertDoesNotExist()
    }

    @Test
    fun thePopupSaysWhatTeamGivesAndNotNowPutsItAway() = runComposeUiTest {
        feed(FakeFeed.state.copy(upsell = UpsellState(offer = free, popup = true)))
        onNode(hasText("Get more with Team")).assertExists()
        onNode(hasText("All your history, not just the last 30 days", substring = true)).assertExists()
        onNode(hasText("Not now") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Get more with Team"))).performClick()
        waitForIdle()
        assertTrue(FeedEvent.DismissUpsell in events)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun thePopupsUpgradeOpensTheBillingSite() = runComposeUiTest {
        feed(FakeFeed.state.copy(upsell = UpsellState(offer = free, popup = true)))
        onNode(hasText("Upgrade") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Get more with Team"))).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
        assertTrue(FeedEvent.DismissUpsell in events)
    }

    @Test
    fun aRefusalHidesThePopupUnderIt() = runComposeUiTest {
        feed(FakeFeed.state.copy(upsell = UpsellState(offer = free, popup = true), upgrade = PlanLimitHit("Full.", "agents", null)))
        onNode(hasText("Get more with Team")).assertDoesNotExist()
        onNode(hasText("No room for that")).assertExists()
    }

    private val owner = TeamState(me = PersonRow("p1", "Ann", "ann", "owner", you = true), people = listOf(PersonRow("p1", "Ann", "ann", "owner", you = true)), loaded = true)
    private val full = free.copy(used = PlanUse(people = 2, agents = 3, storageBytes = 0))

    @Test
    fun inviteSaysThePlanIsFullBeforeAnEmailIsTyped() = runComposeUiTest {
        feed(FakeFeed.state.copy(team = owner, plan = PlanState(full, loaded = true), upsell = UpsellState(offer = full)), ScreenPreview(peopleOpen = true))
        onNode(hasText("2 of 2 people on the free plan, counting invites still waiting. Team gives you room for more.")).assertExists()
        onNode(hasText("Upgrade")).performClick()
        waitForIdle()
        assertEquals(listOf("https://billing.test/teams/acme"), opened)
    }

    @Test
    fun inviteSaysWhatToDoWhereTeamIsNotOffered() = runComposeUiTest {
        feed(FakeFeed.state.copy(team = owner, plan = PlanState(full, loaded = true)), ScreenPreview(peopleOpen = true))
        onNode(hasText("2 of 2 people, counting invites still waiting. Remove someone or a waiting invite to invite another.")).assertExists()
        onNode(hasText("Upgrade")).assertDoesNotExist()
    }

    @Test
    fun connectingAnAgentSaysThePlanIsFull() = runComposeUiTest {
        feed(FakeFeed.state.copy(team = owner, plan = PlanState(full, loaded = true), upsell = UpsellState(offer = full)), ScreenPreview(connectOpen = true))
        onNode(hasText("3 of 3 agents on the free plan. Team lets you connect as many as you like.")).assertExists()
        onNode(hasText("Upgrade")).assertExists()
    }

    @Test
    fun connectingAnAgentWithRoomSaysNothing() = runComposeUiTest {
        feed(FakeFeed.state.copy(team = owner, plan = PlanState(free, loaded = true), upsell = UpsellState(offer = free)), ScreenPreview(connectOpen = true))
        onNode(hasText("agents on the free plan", substring = true)).assertDoesNotExist()
        onNode(hasText("Upgrade")).assertDoesNotExist()
    }

    @Test
    fun settingsShowJustTheTeamPlanOnTeam() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(TeamPlan(plan = TeamPlan.Team), loaded = true)), ScreenPreview(alertsOpen = true))
        onNode(hasText("Team plan")).assertExists()
        onNode(hasText("Upgrade")).assertDoesNotExist()
        onNode(hasText("People 3", substring = true)).assertDoesNotExist()
    }

    @Test
    fun settingsShowNothingAboutPlansWithoutOne() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(null, loaded = true)), ScreenPreview(alertsOpen = true))
        onNode(hasText("Free plan")).assertDoesNotExist()
        onNode(hasText("Team plan")).assertDoesNotExist()
    }
}
