package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Making a project, renaming it, archiving it and giving it its own colours, on phones and desktop. */
@OptIn(ExperimentalTestApi::class)
class ProjectManagementTest {
    private val events = mutableListOf<FeedEvent>()

    /** What the dashboard says back to each change: null for done, or why not. */
    private var answer: String? = null

    private fun ComposeUiTest.feed(width: Int, state: FeedState = FakeFeed.state) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) {
                FeedScreen(state, onEvent = { event ->
                    events += event
                    when (event) {
                        is FeedEvent.CreateProject -> event.done(answer)
                        is FeedEvent.EditProject -> event.done(answer)
                        else -> {}
                    }
                })
            }
        }
    }

    private fun ComposeUiTest.field(label: String) = onNode(hasContentDescription(label) and hasSetTextAction())

    @Test
    fun aNewProjectIsMadeFromTheSidebar() = runComposeUiTest {
        feed(1440)
        onNode(hasText("New project") and hasClickAction()).performScrollTo().performClick()
        field("Project name").performTextInput("harbor-app")
        field("What it's for").performTextInput("Ferry times")
        onNode(hasText("Create project")).performClick()
        waitForIdle()
        assertEquals(listOf("harbor-app" to "Ferry times"), events.filterIsInstance<FeedEvent.CreateProject>().map { it.name to it.description })
        assertTrue(onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty(), "closes once made")
    }

    @Test
    fun aRefusedNameSaysWhyAndStaysOpen() = runComposeUiTest {
        answer = "That name is too long."
        feed(1440)
        onNode(hasText("New project") and hasClickAction()).performScrollTo().performClick()
        field("Project name").performTextInput("x".repeat(90))
        onNode(hasText("Create project")).performClick()
        waitForIdle()
        onNode(hasText("That name is too long.")).assertExists()
        onNode(hasText("Create project")).assertExists()
    }

    @Test
    fun aPhoneMakesOneFromTheDrawer() = runComposeUiTest {
        feed(390)
        onNode(hasContentDescription("Projects", substring = true)).performClick()
        waitForIdle()
        onNode(hasText("New project") and hasClickAction()).performScrollTo().performClick()
        field("Project name").performTextInput("garden-game")
        onNode(hasText("Create project")).performClick()
        waitForIdle()
        assertEquals(listOf("garden-game"), events.filterIsInstance<FeedEvent.CreateProject>().map { it.name })
    }

    @Test
    fun settingsRenameAndRedescribeTheOpenProject() = runComposeUiTest {
        feed(1440)
        onNode(hasContentDescription("Project settings")).performClick()
        field("Project name").performTextReplacement("dashboard")
        field("What it's for").performTextReplacement("The status wall")
        onNode(hasText("Save")).performClick()
        waitForIdle()
        val edit = events.filterIsInstance<FeedEvent.EditProject>().single()
        assertEquals(Triple("agent-dashboard", "dashboard", "The status wall"), Triple(edit.slug, edit.name, edit.description))
    }

    @Test
    fun archivingAndUnarchiving() = runComposeUiTest {
        val archived = FakeFeed.state.copy(current = FakeFeed.state.projects.first { it.archived })
        val shown = androidx.compose.runtime.mutableStateOf(FakeFeed.state)
        setContent {
            AgentTheme(dark = true) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(shown, onEvent = { events += it }) } }
        }
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasText("Archive project")).performClick()
        waitForIdle()
        shown.value = archived
        waitForIdle()
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasText("Unarchive project")).performClick()
        waitForIdle()
        assertEquals(
            listOf(FeedEvent.ArchiveProject("agent-dashboard", true), FeedEvent.ArchiveProject("agent-dashboard-v1", false)),
            events.filterIsInstance<FeedEvent.ArchiveProject>(),
        )
    }

    @Test
    fun pickingColoursSetsThem() = runComposeUiTest {
        feed(1440)
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasContentDescription("Page colour Harbour")).performScrollTo().performClick()
        onNode(hasContentDescription("Accent colour Coral")).performScrollTo().performClick()
        onNode(hasContentDescription("Page colour None")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(
            listOf(
                FeedEvent.SetProjectColours("agent-dashboard", "#0f3d3e", null),
                FeedEvent.SetProjectColours("agent-dashboard", "#0f3d3e", "#ff7a59"),
                FeedEvent.SetProjectColours("agent-dashboard", null, "#ff7a59"),
            ),
            events.filterIsInstance<FeedEvent.SetProjectColours>(),
        )
    }

    @Test
    fun aProjectsColoursAreDrawn() = runComposeUiTest {
        val themed = FakeFeed.state.current.copy(background = "#0f3d3e", accent = "#ff7a59")
        feed(1440, FakeFeed.state.copy(current = themed, projects = FakeFeed.state.projects.map { if (it.id == themed.id) themed else it }))
        waitForIdle()
        val pixels = onAllNodes(isRoot()).onFirst().captureToImage().toPixelMap()
        // The page behind the feed, past the sidebar and clear of any card.
        val heading = onNode(hasText("agent-dashboard") and androidx.compose.ui.test.isHeading()).fetchSemanticsNode().boundsInRoot
        assertEquals(Color(0xFF0F3D3E), pixels[(heading.left - 12).toInt(), (heading.top + 4).toInt()])
    }
}
