package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.PersonRow
import dev.wildware.agents.app.feed.ProjectGroup
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.TeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arranging channels and projects by hand in the sidebar and the drawer, and the menus on what the
 * sidebar lists: what reaches the dashboard, what each person is offered, and the keys.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarArrangeTest {
    private val events = mutableListOf<FeedEvent>()

    private val mobile = ProjectGroup("g1", "mobile", "Mobile")
    private val platform = ProjectGroup("g2", "platform", "Platform")

    /** agent-dashboard and ios-companion in Mobile, rust-engine in Platform, website and infra in none. */
    private fun state(role: String = "owner"): FeedState {
        val channels = FakeFeed.state.projects.map {
            when (it.slug) {
                "agent-dashboard", "ios-companion" -> it.copy(groupId = mobile.id)
                "rust-engine" -> it.copy(groupId = platform.id)
                else -> it
            }
        }
        return FakeFeed.state.copy(
            projects = channels,
            current = channels.first { it.id == FakeFeed.state.current.id },
            groups = listOf(mobile, platform),
            team = TeamState(me = PersonRow("me", "Sam", "sam", role = role, you = true), loaded = true),
        )
    }

    private fun ComposeUiTest.desktop(role: String = "owner", startAt: String? = null) = setContent {
        AgentTheme(dark = false) {
            Box(Modifier.size(1280.dp, 900.dp)) { FeedScreen(state(role), startAt = startAt, onEvent = { if (it !is FeedEvent.ReadMembers) events += it }) }
        }
    }

    private fun hasCustomAction(label: String) = SemanticsMatcher("has action '$label'") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    private fun ComposeUiTest.phone(role: String = "owner") = setContent {
        AgentTheme(dark = false) {
            Box(Modifier.size(390.dp, 844.dp)) {
                FeedScreen(state(role), preview = ScreenPreview(drawerOpen = true), onEvent = { if (it !is FeedEvent.ReadMembers) events += it })
            }
        }
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    /** A row of the channel list, by the name it shows. */
    private fun ComposeUiTest.row(name: String): SemanticsNodeInteraction = onNode(hasText(name) and isTab)

    private fun ComposeUiTest.bounds(name: String): Rect = row(name).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.labelBounds(text: String): Rect = onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().boundsInRoot

    /** Drags the row [name] with the mouse to [to] (root coordinates), then lets go. */
    private fun ComposeUiTest.mouseDrag(name: String, to: Offset, letGo: Boolean = true) {
        val from = bounds(name).center
        onRoot().performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(0f, 24f))
            moveTo(to)
        }
        waitForIdle()
        if (letGo) {
            onRoot().performMouseInput { release() }
            waitForIdle()
        }
    }

    private val arranged get() = events.filterIsInstance<FeedEvent.ArrangeChannels>()

    // ---- dragging --------------------------------------------------------------------------------

    @Test
    fun `dragging a channel onto a project moves it in at the end`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("website", bounds("Platform").center)
        val move = arranged.single()
        assertEquals(listOf("rust-engine", "website"), move.slugs)
        assertEquals(platform.id, move.groupId)
        // It shows there at once, before the dashboard answers.
        assertTrue(bounds("website").top < labelBounds("CHANNELS").top, "website still under Channels")
        assertTrue(bounds("website").top > bounds("Platform").top)
    }

    @Test
    fun `dragging between channels reorders them`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("infra", Offset(bounds("website").center.x, bounds("website").top + 2f))
        val move = arranged.single()
        assertEquals(listOf("infra", "website"), move.slugs)
        assertEquals(null, move.groupId)
        assertTrue(bounds("infra").top < bounds("website").top, "infra not above website")
    }

    @Test
    fun `dragging a channel to Channels takes it out of its project`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("rust-engine", labelBounds("CHANNELS").center)
        val move = arranged.single()
        assertEquals(listOf("rust-engine", "website", "infra"), move.slugs)
        assertEquals(null, move.groupId)
    }

    @Test
    fun `dragging a project above another reorders the projects`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("Platform", Offset(bounds("Mobile").center.x, bounds("Mobile").top + 2f))
        val move = events.filterIsInstance<FeedEvent.ArrangeGroups>().single()
        assertEquals(listOf("platform", "mobile"), move.slugs)
        assertTrue(bounds("Platform").top < bounds("Mobile").top, "Platform not above Mobile")
    }

    @Test
    fun `Escape calls a drag off`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("website", bounds("Platform").center, letGo = false)
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onRoot().performMouseInput { release() }
        waitForIdle()
        assertTrue(arranged.isEmpty(), "got $events")
    }

    @Test
    fun `a dropped channel stays where it went once the move is taken`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("website", bounds("Mobile").center)
        arranged.single().done(null)
        waitForIdle()
        assertTrue(bounds("website").top < bounds("Platform").top, "website not in Mobile")
    }

    @Test
    fun `a refused move puts the channel back and says why`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        mouseDrag("website", bounds("Mobile").center)
        arranged.single().done("Only an admin can do that.")
        waitForIdle()
        assertTrue(bounds("website").top > labelBounds("CHANNELS").top, "website not back under Channels")
        onNode(hasText("Couldn't move it: Only an admin can do that.")).assertExists()
    }

    @Test
    fun `a member can't drag`() = runDesktopComposeUiTest(1280, 900) {
        desktop(role = "member")
        mouseDrag("website", bounds("Platform").center)
        assertTrue(arranged.isEmpty(), "got $events")
    }

    @Test
    fun `on a phone a hold then a drag moves a channel`() = runDesktopComposeUiTest(390, 844) {
        phone()
        val from = bounds("website").center
        val to = bounds("Platform").center
        onRoot().performTouchInput {
            down(from)
            advanceEventTime(700)
            moveTo(from + Offset(0f, 30f))
            moveTo(to)
            up()
        }
        waitForIdle()
        val move = arranged.single()
        assertEquals(platform.id, move.groupId)
        assertEquals(listOf("rust-engine", "website"), move.slugs)
    }

    // ---- menus -----------------------------------------------------------------------------------

    private fun ComposeUiTest.menuOn(name: String) {
        row(name).performMouseInput { rightClick(center) }
        waitForIdle()
    }

    private fun ComposeUiTest.shows(vararg items: String) = items.forEach { onNode(hasText(it) and hasClickAction()).assertExists() }

    private fun ComposeUiTest.hides(vararg items: String) =
        items.forEach { assertEquals(0, onAllNodes(hasText(it) and hasClickAction()).fetchSemanticsNodes().size, "$it is offered") }

    @Test
    fun `a channel's menu offers everything to an owner`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        menuOn("website")
        shows("Open", "Mark read", "Mute", "Move to project…", "Rename…", "Channel settings", "Permissions…", "Copy link", "Archive")
    }

    @Test
    fun `a member's channel menu leaves out what only an admin may do`() = runDesktopComposeUiTest(1280, 900) {
        desktop(role = "member")
        menuOn("website")
        shows("Open", "Mark read", "Mute", "Channel settings", "Permissions…", "Copy link")
        hides("Move to project…", "Rename…", "Archive")
    }

    @Test
    fun `a project's menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        menuOn("Mobile")
        shows("Rename…", "New channel here", "Collapse", "Mute all", "Project settings", "Permissions…", "Delete project…")
    }

    @Test
    fun `a member's project menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop(role = "member")
        menuOn("Mobile")
        shows("Collapse", "Mute all", "Project settings")
        hides("Rename…", "New channel here", "Delete project…")
    }

    @Test
    fun `Move to project from the menu moves the channel`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        menuOn("website")
        onNode(hasText("Move to project…") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Platform") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        waitForIdle()
        val move = arranged.single()
        assertEquals(platform.id, move.groupId)
        assertEquals(listOf("rust-engine", "website"), move.slugs)
    }

    @Test
    fun `Delete project asks first and its channels stay`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        menuOn("Mobile")
        onNode(hasText("Delete project…") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Its 2 channels move to Channels", substring = true)).assertExists()
        assertTrue(events.none { it is FeedEvent.DeleteGroup })
        onNode(hasText("Delete project") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DeleteGroup("mobile")), events)
    }

    @Test
    fun `Mute and Mark read from a channel's menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        menuOn("website")
        onNode(hasText("Mark read") and hasClickAction()).performClick()
        waitForIdle()
        menuOn("website")
        onNode(hasText("Mute") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(
            listOf(FeedEvent.MarkChannelRead("p4"), FeedEvent.SetProjectNotify("p4", "website", dev.wildware.agents.NotifyLevel.Muted)),
            events,
        )
    }

    @Test
    fun `Shift F10 opens the focused row's menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        row("website").performSemanticsAction(SemanticsActions.RequestFocus)
        row("website").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        waitForIdle()
        shows("Move to project…")
    }

    @Test
    fun `the Menu key opens the focused row's menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        row("infra").performSemanticsAction(SemanticsActions.RequestFocus)
        row("infra").performKeyInput { pressKey(Key.Menu) }
        waitForIdle()
        shows("Move to project…")
    }

    @Test
    fun `Ctrl Shift and an arrow move the focused channel`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        row("infra").performSemanticsAction(SemanticsActions.RequestFocus)
        row("infra").performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } } }
        waitForIdle()
        assertEquals(listOf("infra", "website"), arranged.single().slugs)
    }

    @Test
    fun `screen readers get the menu and the moves as actions`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        val actions = onNode(hasCustomAction("Actions for website")).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertTrue(actions.containsAll(listOf("Actions for website", "Move website up", "Move website down", "Move website to project")), "got $actions")
    }

    @Test
    fun `an agent's menu`() = runDesktopComposeUiTest(1280, 900) {
        desktop()
        val agent = state().agents.first().name
        onAllNodes(hasContentDescription(agent, substring = true) and hasClickAction()).onFirst().performScrollTo().performMouseInput { rightClick(center) }
        waitForIdle()
        shows("Message", "Settings")
    }

    @Test
    fun `on a phone a hold that lets go opens the menu as a sheet`() = runDesktopComposeUiTest(390, 844) {
        phone()
        row("website").performTouchInput { longClick(center, durationMillis = 700) }
        waitForIdle()
        shows("Open", "Mark read", "Move to project…", "Cancel")
        assertTrue(events.none { it is FeedEvent.SelectProject }, "the hold also opened the channel: $events")
    }

    // ---- cards and threads -----------------------------------------------------------------------

    @Test
    fun `a card's menu opens its thread and marks it unread`() = runDesktopComposeUiTest(1280, 900) {
        desktop(startAt = "u1")
        onNode(hasText("Reading messages in one session no longer hides them from another")).performScrollTo().performMouseInput { rightClick(center) }
        waitForIdle()
        shows("Open thread", "Reply", "Copy link", "Mark unread")
        onNode(hasText("Mark unread") and hasClickAction()).performClick()
        waitForIdle()
        assertTrue(events.any { it is FeedEvent.MarkUnread }, "got $events")
    }
}
