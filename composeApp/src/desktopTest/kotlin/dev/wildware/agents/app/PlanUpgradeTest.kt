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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The free plan in the app: its limits in Settings, and "Upgrade" when an action passes one. Nothing is bought in the app. */
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
        onNode(hasText("Upgrade")).assertDoesNotExist()
        onNode(hasText("Not now") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "The free plan is full"))).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DismissUpgrade), events.filter { it == FeedEvent.DismissUpgrade })
        assertEquals(emptyList(), opened)
    }

    @Test
    fun settingsShowTheFreePlanLimitsAndUpgrade() = runComposeUiTest {
        feed(FakeFeed.state.copy(plan = PlanState(free, loaded = true)), ScreenPreview(alertsOpen = true))
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
