package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.Gallery
import dev.wildware.agents.app.feed.QuickReaction
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the phone feed with real touch input and checks each gesture does what it says. */
@OptIn(ExperimentalTestApi::class)
class GestureTest {
    private val events = mutableListOf<FeedEvent>()
    private val question = "Question from relay, waiting on you"
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    private fun ComposeUiTest.phone(preview: ScreenPreview = ScreenPreview(), startAt: String? = null) = setContent {
        AgentTheme(dark = false) {
            Box(Modifier.size(390.dp, 844.dp)) {
                FeedScreen(FakeFeed.state, startAt = startAt, preview = preview, onEvent = { events += it })
            }
        }
    }

    private fun hasCustomAction(label: String) = SemanticsMatcher("has action '$label'") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    private fun ComposeUiTest.runCustomAction(label: String) {
        val node = onNode(hasCustomAction(label)).fetchSemanticsNode()
        runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }

    @Test
    fun swipingAQuestionRightAnswersWithTheMainChoice() = runComposeUiTest {
        phone()
        onNodeWithContentDescription(question).performTouchInput { swipe(center, center + Offset(260f, 0f), 200) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q1", listOf("Ship it"))), events)
    }

    @Test
    fun swipingAQuestionLeftDeclines() = runComposeUiTest {
        phone()
        onNodeWithContentDescription(question).performTouchInput { swipe(center, center - Offset(260f, 0f), 200) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q1", listOf("Not now"))), events)
    }

    @Test
    fun aShortSwipeSpringsBackAndDoesNothing() = runComposeUiTest {
        phone()
        onNodeWithContentDescription(question).performTouchInput { swipe(center, center + Offset(50f, 0f), 200) }
        waitForIdle()
        assertTrue(events.isEmpty(), "got $events")
    }

    @Test
    fun theSwipeIsAlsoAScreenReaderAction() = runComposeUiTest {
        phone()
        runCustomAction("Ship it")
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q1", listOf("Ship it"))), events)
    }

    @Test
    fun longPressOpensTheMenu() = runComposeUiTest {
        phone(startAt = "u1")
        onNodeWithText(fixTitle).performTouchInput { longClick() }
        onNodeWithText("Copy link").assertExists()
        onNodeWithText("Reply").assertExists()
    }

    @Test
    fun doubleTapReactsWithTheQuickEmoji() = runComposeUiTest {
        phone(startAt = "u1")
        onNodeWithText(fixTitle).performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.React("u1", QuickReaction)), events)
    }

    @Test
    fun swipingInFromTheLeftEdgeOpensTheDrawer() = runComposeUiTest {
        phone()
        onNodeWithText("Hold to reorder").assertDoesNotExist()
        onRoot().performTouchInput { swipe(Offset(4f, 400f), Offset(330f, 400f), 250) }
        waitForIdle()
        onNodeWithText("Hold to reorder").assertExists()
    }

    @Test
    fun theDrawerFollowsTheWholeSwipeAndEndsFullyOpen() = runComposeUiTest {
        phone()
        // Slowly, as a finger does: every step has to reach the drawer, not just the first.
        onRoot().performTouchInput { swipe(Offset(4f, 400f), Offset(300f, 400f), 900) }
        waitForIdle()
        val left = onNodeWithText("Hold to reorder").fetchSemanticsNode().boundsInRoot.left
        assertTrue(left >= 0f, "the drawer stopped part way, its label at x=$left")
    }

    private fun ComposeUiTest.pullDown() =
        onRoot().performTouchInput { swipe(Offset(195f, 160f), Offset(195f, 700f), 500) }

    @Test
    fun pullingTheFeedDownRefreshesItAndTheIndicatorSettles() = runComposeUiTest {
        phone()
        pullDown()
        waitForIdle()
        assertEquals(1, events.count { it == FeedEvent.Refresh }, "got $events")

        // The spinner shows for a moment at least, then goes once the read has landed (the state is
        // not loading), so a second pull refreshes again.
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        pullDown()
        waitForIdle()
        assertEquals(2, events.count { it == FeedEvent.Refresh }, "got $events")
    }

    @Test
    fun whileTheReadIsStillRunningTheIndicatorStaysAndAPullDoesNotAskTwice() = runComposeUiTest {
        setContent {
            AgentTheme(dark = false) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state.copy(loading = true), onEvent = { events += it })
                }
            }
        }
        pullDown()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        pullDown()
        waitForIdle()
        assertEquals(1, events.count { it == FeedEvent.Refresh }, "got $events")
    }

    @Test
    fun projectsReorderFromTheScreenReaderToo() = runComposeUiTest {
        phone(ScreenPreview(drawerOpen = true))
        runCustomAction("Move ios-companion up")
        assertEquals(listOf<FeedEvent>(FeedEvent.MoveProject(1, 0)), events)
    }

    @Test
    fun swipingAReplyRightRepliesToIt() = runComposeUiTest {
        phone(ScreenPreview(threadOpen = true))
        onNodeWithText("The watch reads the same counter", substring = true).performScrollTo()
            .performTouchInput { swipe(center, center + Offset(240f, 0f), 200) }
        waitForIdle()
        assertTrue(FeedEvent.Reply("r2") in events, "got $events")
        onNodeWithText("Replying to forge").assertExists()
    }

    @Test
    fun swipingTheImageDownClosesIt() = runComposeUiTest {
        val images = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isNotEmpty() }.attachments
        phone(ScreenPreview(viewer = Gallery(images)))
        onNodeWithContentDescription("Close pictures").assertExists()
        onNodeWithContentDescription(images[0].altText).performTouchInput { swipe(center, center + Offset(0f, 400f), 200) }
        waitForIdle()
        onNodeWithContentDescription("Close pictures").assertDoesNotExist()
    }

    @Test
    fun tappingPicturesPicksThemAndSendSendsThem() = runComposeUiTest {
        phone(startAt = "q2")
        onNodeWithContentDescription("Dusk").performClick()
        onNodeWithContentDescription("Night").performClick()
        onNodeWithText("Send 2 picks").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q2", listOf("Dusk", "Night"))), events)
    }

    @Test
    fun longPressingAPictureOpensItFullSize() = runComposeUiTest {
        phone(startAt = "q2")
        onNodeWithContentDescription("Dawn").performTouchInput { longClick() }
        onNodeWithContentDescription("Close pictures").assertExists()
        assertTrue(events.isEmpty(), "a long press must not pick: $events")
    }

    @Test
    fun eachPictureIsShownOnceNotAlsoInAMediaGrid() = runComposeUiTest {
        phone(startAt = "q2")
        onAllNodes(hasContentDescription("Dawn")).assertCountEquals(1)
        onAllNodes(hasContentDescription("Look closer at Dawn")).assertCountEquals(1)
    }

    @Test
    fun aPictureCardStillSwipesRightToSend() = runComposeUiTest {
        phone(ScreenPreview(picked = mapOf("q2" to setOf("Dawn"))), startAt = "q2")
        onNodeWithText("Which empty states should ship?").performTouchInput { swipe(center, center + Offset(260f, 0f), 200) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q2", listOf("Dawn"))), events)
    }
}
