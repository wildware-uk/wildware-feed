package dev.wildware.agents.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.ThemeChoice
import dev.wildware.agents.app.design.resolveDark
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.LocalFilePicker
import dev.wildware.agents.app.feed.PendingFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PolishTest {
    private val events = mutableListOf<FeedEvent>()

    private fun ComposeUiTest.desktop(state: FeedState = FakeFeed.state.copy(openThread = null), startAt: String? = null, picker: FilePicker? = null) =
        setContent {
            CompositionLocalProvider(LocalFilePicker provides picker) {
                AgentTheme(dark = true) { FeedScreen(state, startAt = startAt, onEvent = { events += it }) }
            }
        }

    @Test
    fun withNoPreferenceTheAppIsDark() {
        assertTrue(resolveDark(ThemeChoice.System, system = null))
        assertFalse(resolveDark(ThemeChoice.System, system = false))
        assertFalse(resolveDark(ThemeChoice.Light, system = true))
        assertTrue(resolveDark(ThemeChoice.Dark, system = false))
    }

    @Test
    fun archivedProjectsAreFoldedAwayUnderTheirOwnRow() = runDesktopComposeUiTest(1440, 900) {
        val state = FakeFeed.state
        val archived = state.projects.first().copy(id = "old", archived = true, unread = 0, waiting = 0)
        desktop(state.copy(projects = state.projects + archived, openThread = null))
        onAllNodes(hasContentDescription("agent-dashboard,", substring = true)).assertCountEquals(1)
        onNodeWithContentDescription("Show 2 archived").performClick()
        onNodeWithContentDescription("agent-dashboard, archived").assertExists()
    }

    @Test
    fun nothingWaitingShowsNoBadge() = runDesktopComposeUiTest(1440, 900) {
        val state = FakeFeed.state
        desktop(state.copy(projects = state.projects.map { it.copy(waiting = 0) }, openThread = null))
        onNode(hasContentDescription("Waiting on you, nothing")).assert(hasText("0").not())
    }

    @Test
    fun aCardsPicturesOpenAndSwipeFromOneToTheNext() = runDesktopComposeUiTest(1440, 900) {
        desktop(startAt = "u2")
        onNodeWithContentDescription("Picture 1 of 3", substring = true).performClick()
        waitUntil { onAllNodes(hasText("1 of 3")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Next picture").performClick()
        waitUntil { onAllNodes(hasText("2 of 3")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Green hills under a pale morning sky").performTouchInput {
            swipe(center, center - Offset(width * 0.6f, 0f), 250)
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("3 of 3")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun ctrlKOpensSearchAndCommands() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        onNodeWithContentDescription("Search or run a command").assertExists()
    }

    @Test
    fun searchFindsAReplyAndEnterOpensItsThread() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        onNodeWithContentDescription("Search everything. Shortcut Command K").performClick()
        onNodeWithContentDescription("Search or run a command").performTextInput("watch counter")
        onNode(hasText("Yes. The watch reads the same counter", substring = true)).assertExists()
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        // The thread is under its card: opening it opens the card's reply box there.
        onNodeWithContentDescription("Reply").assertExists()
        assertTrue(FeedEvent.OpenThread("u1") in events, "got $events")
    }

    @Test
    fun theCommandBarRunsActions() = runDesktopComposeUiTest(1440, 900) {
        desktop()
        onNodeWithContentDescription("Search everything. Shortcut Command K").performClick()
        onNodeWithContentDescription("Search or run a command").performTextInput("mark all")
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.MarkAllRead), events)
        onNodeWithContentDescription("Search or run a command").assertDoesNotExist()
    }

    @Test
    fun attachedFilesShowAndCanBeRemovedBeforeSending() = runDesktopComposeUiTest(1440, 900) {
        val png = PendingFile("shot.png", "image/png", byteArrayOf(1, 2, 3))
        val log = PendingFile("build.log", "text/plain", "slow".encodeToByteArray())
        desktop(picker = { onPicked -> onPicked(listOf(png, log)) })
        onNodeWithContentDescription("Attach files").performClick()
        onNodeWithContentDescription("Remove shot.png").performClick()
        onNodeWithContentDescription("Remove shot.png").assertDoesNotExist()
        onNodeWithContentDescription("Attached: build.log").assertExists()
        onNodeWithContentDescription("Send").performClick()
        val post = events.filterIsInstance<FeedEvent.Post>().single()
        assertEquals(listOf("build.log"), post.files.map { it.name })
    }
}
