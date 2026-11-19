package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AlertSettings
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalCriticalAlertSettings
import dev.wildware.agents.app.feed.LocalPushAlerts
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A "Critical" chip on what an agent marks critical, and the setting that lets it page this phone. */
@OptIn(ExperimentalTestApi::class)
class CriticalAlertsTest {
    private val events = mutableListOf<FeedEvent>()
    private var openedSystemSettings = 0

    private fun ComposeUiTest.sheet(prefs: AlertPrefs?, android: Boolean = false) = setContent {
        val state = FakeFeed.state.copy(alerts = AlertSettings(prefs, loaded = true))
        CompositionLocalProvider(
            LocalPushAlerts provides !android,
            LocalCriticalAlertSettings provides (if (android) ({ openedSystemSettings++ }) else null),
        ) {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, preview = ScreenPreview(alertsOpen = true), onEvent = { events += it }) }
            }
        }
    }

    private fun saved() = events.filterIsInstance<FeedEvent.SetAlertPrefs>().map { it.prefs }

    @Test
    fun criticalAlertsAreTurnedOnFromTheAlertSettings() = runComposeUiTest {
        sheet(null)
        onNode(hasText("Page me for critical alerts")).performScrollTo().performClick()
        assertEquals(listOf(AlertPrefs(critical = true)), saved())
    }

    @Test
    fun theyCanBeLimitedToSomeAgentsAndProjects() = runComposeUiTest {
        val agent = FakeFeed.state.agents.first()
        val project = FakeFeed.state.projects.first { it.slug != null }
        sheet(AlertPrefs(critical = true))
        onNode(hasContentDescription("Critical alerts from ${agent.name}", substring = true)).performScrollTo().performClick()
        onNode(hasContentDescription("Critical alerts from ${project.name}", substring = true)).performScrollTo().performClick()
        assertEquals(
            listOf(AlertPrefs(critical = true, criticalAgents = listOf(agent.id)), AlertPrefs(critical = true, criticalProjects = listOf(project.slug!!))),
            saved(),
        )
    }

    @Test
    fun onAndroidTheSettingLeadsToTheSystemsDoNotDisturbSwitch() = runComposeUiTest {
        sheet(AlertPrefs(critical = true), android = true)
        onNode(hasText("Let them through Do Not Disturb")).performScrollTo().performClick()
        assertEquals(1, openedSystemSettings)
    }

    @Test
    fun aCriticalCardWearsTheChipAndSaysSo() = runComposeUiTest {
        val state: FeedState = FakeFeed.state.copy(
            sections = FakeFeed.state.sections.map { s ->
                s.copy(items = s.items.map { if (it is FeedItem.Update) it.copy(critical = true, high = false) else it })
            },
        )
        setContent { AgentTheme(dark = true) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) } } }
        // The header says it, and the chip shows it.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription(", critical,", substring = true))
        assertTrue(onAllNodes(hasText("CRITICAL"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun onDesktopACriticalItemShowsARedBannerThatOpensItOrGoesAway() = runComposeUiTest {
        val state = FakeFeed.state.copy(critical = listOf(dev.wildware.agents.app.feed.CriticalNotice("u2", "forge", "Production is down", question = false)))
        setContent { AgentTheme(dark = true) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) } } }
        onNode(hasContentDescription("Critical from forge: Production is down", substring = true)).assertExists()
        onNode(hasText("Dismiss")).performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription("Critical from forge", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun onAPhoneThereIsNoBanner() = runComposeUiTest {
        val state = FakeFeed.state.copy(critical = listOf(dev.wildware.agents.app.feed.CriticalNotice("u2", "forge", "Production is down", question = false)))
        setContent { AgentTheme(dark = true) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state) } } }
        waitForIdle()
        assertTrue(onAllNodes(hasContentDescription("Critical from forge", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
