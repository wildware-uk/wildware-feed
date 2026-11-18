package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reaction chips toggle the owner's own reaction, and "+" picks a new one. */
@OptIn(ExperimentalTestApi::class)
class ReactionTest {
    private val events = mutableListOf<FeedEvent>()

    private fun ComposeUiTest.feed(
        width: Int,
        height: Int,
        preview: ScreenPreview = ScreenPreview(),
        startAt: String? = "u1",
        state: dev.wildware.agents.app.feed.FeedState = FakeFeed.state.copy(openThread = null),
    ) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, height.dp)) {
                FeedScreen(state, startAt = startAt, preview = preview, onEvent = { events += it })
            }
        }
    }

    private fun ComposeUiTest.phone(preview: ScreenPreview = ScreenPreview(), startAt: String? = "u1") = feed(390, 844, preview, startAt)

    @Test
    fun tappingYourOwnReactionTakesItBack() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("check mark, 1, including you. Double tap to remove yours.").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.React("u1", "✅")), events)
        onNodeWithContentDescription("check mark", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingSomeoneElsesReactionAddsYours() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("eyes, 2. Double tap to add yours.").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.React("u1", "👀")), events)
        onNodeWithContentDescription("eyes, 3, including you. Double tap to remove yours.").assertExists()
    }

    @Test
    fun tappingTwiceLeavesItAsItWas() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("eyes, 2. Double tap to add yours.").performScrollTo().performClick()
        onNodeWithContentDescription("eyes, 3, including you. Double tap to remove yours.").performClick()
        waitForIdle()
        onNodeWithContentDescription("eyes, 2. Double tap to add yours.").assertExists()
        assertEquals(2, events.size)
    }

    @Test
    fun plusOpensAPickerAndPickingReacts() = runComposeUiTest {
        phone()
        onAllNodesWithContentDescription("Add a reaction").onFirst().performScrollTo().performClick()
        onNodeWithContentDescription("React with 🎉").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.React("u1", "🎉")), events)
        onNodeWithContentDescription("React with 🎉").assertDoesNotExist()
    }

    @Test
    fun thePickerClosesWithEscapeOnDesktop() = runDesktopComposeUiTest(1440, 900) {
        feed(1440, 900)
        onAllNodesWithContentDescription("Add a reaction").onFirst().performScrollTo().performClick()
        waitForIdle()
        // The first emoji has focus as soon as the picker opens.
        onNode(isFocused() and hasContentDescription("React with 👍")).assertExists().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithContentDescription("React with 🎉").assertDoesNotExist()
        assertEquals(emptyList(), events)
    }

    @Test
    fun aReactionOnAReplyTogglesToo() = runComposeUiTest {
        feed(390, 844, ScreenPreview(threadOpen = true), startAt = null, state = FakeFeed.state)
        onNodeWithText("The watch reads the same counter", substring = true).performScrollTo()
        onNodeWithContentDescription("raised hands, 1, including you. Double tap to remove yours.").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.React("r2", "🙌")), events)
    }
}
