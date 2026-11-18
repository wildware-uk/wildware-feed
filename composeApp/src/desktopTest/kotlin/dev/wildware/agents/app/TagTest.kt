package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tags: chips on cards, the picker that puts them on and takes them off, and the filter. */
@OptIn(ExperimentalTestApi::class)
class TagTest {
    private val events = mutableListOf<FeedEvent>()
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    private fun ComposeUiTest.feed(width: Int, startAt: String? = "u1") = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = startAt, onEvent = { events += it }) }
        }
    }

    private fun ComposeUiTest.openPickerOnDesktop() {
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Tags…").performClick()
    }

    @Test
    fun thePickerShowsTheProjectsTagsWithTheCardsTicked() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        openPickerOnDesktop()
        onNode(hasContentDescription("bug, on")).assertExists()
        onNode(hasContentDescription("feature, off")).assertExists()
    }

    @Test
    fun tickingATagPutsItOnTheCard() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        openPickerOnDesktop()
        onNode(hasContentDescription("feature, off")).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Tag("u1", "t3", on = true)), events)
        // Shown at once, ticked in the picker and as a chip on the card.
        onNode(hasContentDescription("feature, on")).assertExists()
        onNodeWithText("Done").performClick()
        onNode(hasContentDescription("Show only cards tagged feature")).assertExists()
    }

    @Test
    fun untickingTakesItOff() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        openPickerOnDesktop()
        onNode(hasContentDescription("bug, on")).performClick()
        onNodeWithText("Done").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Tag("u1", "t1", on = false)), events)
        onNode(hasContentDescription("Show only cards tagged bug")).assertDoesNotExist()
    }

    @Test
    fun aNewWordIsMadeAndPutOn() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        openPickerOnDesktop()
        onNodeWithContentDescription("New tag").performTextInput("needs review")
        onNode(hasText("Add") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Tag("u1", "needs review", on = true, create = true)), events)
        onNode(hasContentDescription("needs review, on")).assertExists()
    }

    @Test
    fun tappingAChipShowsOnlyCardsWithThatTag() = runComposeUiTest {
        feed(390)
        onNode(hasContentDescription("Show only cards tagged bug")).performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Build box disk is 86% full").assertDoesNotExist()
        onNodeWithText(fixTitle).assertExists()
        onNode(hasContentDescription("Stop showing only bug")).performClick()
        waitForIdle()
        // Everything is back: the list reaches the disk update again.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Build box disk", substring = true))
    }

    @Test
    fun aPhoneOpensThePickerFromTheSheet() = runComposeUiTest {
        feed(390)
        onNodeWithText(fixTitle).performScrollTo().performTouchInput { longClick() }
        onNodeWithText("Tags…").performClick()
        onNode(hasContentDescription("sessions, on")).assertExists()
        assertTrue(events.isEmpty(), "opening the picker changes nothing: $events")
    }
}
