package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Waiting on you": every question, thread ones too, with counts that match what they open. */
@OptIn(ExperimentalTestApi::class)
class WaitingTest {
    private val events = mutableListOf<FeedEvent>()
    private var now = FakeFeed.Now
    private val threadPrompt = "Ship the badge fix to the watch app today too?"

    private fun ComposeUiTest.show(state: FeedState, width: Int, height: Int, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalNow provides { now }) {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, height.dp)) { FeedScreen(state, preview = preview, onEvent = { events += it }) }
            }
        }
    }

    private fun ComposeUiTest.phone(state: FeedState = FakeFeed.allProjects, preview: ScreenPreview = ScreenPreview()) = show(state, 390, 844, preview)

    @Test
    fun aQuestionAskedInAThreadIsInWaitingOnYouAndSaysWhatItAnswers() = runComposeUiTest {
        phone()
        onNodeWithText(threadPrompt).assertExists()
        onNodeWithText("In reply to forge:", substring = true).assertExists()
        onNodeWithText("Open thread").performClick()
        waitForIdle()
        assertTrue(FeedEvent.OpenThread("u1") in events, "got $events")
    }

    @Test
    fun aThreadQuestionIsAlsoUnderItsReplyAndAnswersFromThere() = runComposeUiTest {
        val fix = FakeFeed.allProjects.sections.flatMap { it.items }.first { it.id == "u1" } as dev.wildware.agents.app.feed.Threaded
        phone(FakeFeed.allProjects.copy(openThread = fix), ScreenPreview(threadOpen = true))
        val inThread = hasAnyAncestor(hasContentDescription("Question from forge, waiting on you"))
        // The feed is still drawn under the thread screen; the thread's copy is the last one.
        onAllNodes(hasText("Yes, today") and inThread).onLast().performScrollTo().performClick()
        waitForIdle()
        assertTrue(FeedEvent.Answer("q3", listOf("Yes, today")) in events, "got $events")
    }

    @Test
    fun questionCardsNameTheAgentAsking() = runComposeUiTest {
        phone()
        onNode(hasText("relay") and hasAnyAncestor(hasContentDescription("Question from relay, waiting on you"))).assertExists()
    }

    @Test
    fun timeLeftCountsDownAndGoesRedNearTheEnd() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("4 minutes left, ending soon").assertExists()
        onNodeWithContentDescription("42 minutes left").assertExists()
        now += 60_000
        mainClock.advanceTimeBy(1_100)
        onNodeWithContentDescription("3 minutes left, ending soon").assertExists()
    }

    @Test
    fun thePhoneTopBarShowsHowManyWaitAndOpensThem() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("3 questions waiting on you").performClick()
        waitForIdle()
        onNodeWithText("Build box disk is 86% full").assertDoesNotExist()
        onNodeWithText(threadPrompt).assertExists()
    }

    @Test
    fun thePhoneDrawerHasWaitingOnYouWithItsCount() = runComposeUiTest {
        phone(FakeFeed.state, ScreenPreview(drawerOpen = true))
        // Inside one project, it opens every project's questions: the count is every project's.
        onNodeWithContentDescription("Waiting on you, 2 questions").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SelectProject(null)), events)
    }

    @Test
    fun drawerProjectsShowTheirWaitingCount() = runComposeUiTest {
        phone(FakeFeed.allProjects, ScreenPreview(drawerOpen = true))
        onNodeWithContentDescription("agent-dashboard, 4 unread, 2 questions waiting on you").assertExists()
        onNodeWithContentDescription("website, 2 unread, 1 question waiting on you").assertExists()
    }

    @Test
    fun theDesktopSidebarRowOpensEveryProjectsQuestions() = runDesktopComposeUiTest(1440, 900) {
        show(FakeFeed.state.copy(openThread = null), 1440, 900)
        onNodeWithContentDescription("Waiting on you, 2 questions").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SelectProject(null)), events)
    }

    @Test
    fun onDesktopTheThreadQuestionSitsUnderItsReplyInTheInlineThread() = runDesktopComposeUiTest(1440, 900) {
        show(FakeFeed.allProjects, 1440, 900)
        onNodeWithText("Open thread").performClick()
        waitForIdle()
        // Once at the top in Waiting on you, once under forge's reply in the card's thread.
        onAllNodes(hasText(threadPrompt)).fetchSemanticsNodes().let { assertEquals(2, it.size, "found ${it.size}") }
    }
}
