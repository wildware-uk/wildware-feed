package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deleting asks first, and says what goes: an update, a post and its replies, or a reply. */
@OptIn(ExperimentalTestApi::class)
class DeleteTest {
    private val events = mutableListOf<FeedEvent>()
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    private fun ComposeUiTest.feed(width: Int, startAt: String? = "u1", state: FeedState = FakeFeed.state.copy(openThread = null), preview: ScreenPreview = ScreenPreview()) =
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(state, startAt = startAt, preview = preview, onEvent = { events += it }) }
            }
        }

    /** The dialog's button, not the menu row of the same name. */
    private fun ComposeUiTest.confirmButton() = onNode(hasText("Delete") and hasClickAction() and hasAnyAncestorDialog())

    private fun hasAnyAncestorDialog() = androidx.compose.ui.test.hasAnyAncestor(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.IsDialog))

    @Test
    fun anUpdateCanBeDeletedAfterSayingYes() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Delete").performClick()
        onNodeWithText("Delete this update?").assertExists()
        assertTrue(events.isEmpty(), "nothing goes before the owner says yes: $events")
        confirmButton().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Delete("u1")), events.filterIsInstance<FeedEvent.Delete>())
        onNodeWithText("Delete this update?").assertDoesNotExist()
    }

    @Test
    fun cancelKeepsIt() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Delete").performClick()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText("Delete this update?").assertDoesNotExist()
        assertTrue(events.none { it is FeedEvent.Delete }, "got $events")
    }

    @Test
    fun yourOwnPostAsksOnAPhone() = runComposeUiTest {
        feed(390, startAt = "u2")
        onNode(hasContentDescription("You, 8:57", substring = true)).performTouchInput { longClick() }
        onNodeWithText("Delete").performClick()
        onNodeWithText("Delete this post?").assertExists()
        confirmButton().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Delete("o1")), events.filterIsInstance<FeedEvent.Delete>())
    }

    @Test
    fun aPostWithRepliesSaysTheyGoToo() = runComposeUiTest {
        val withReplies = FakeFeed.state.copy(
            openThread = null,
            sections = FakeFeed.state.sections.map { s ->
                s.copy(items = s.items.map { if (it.id == "o1") (it as dev.wildware.agents.app.feed.FeedItem.Post).copy(replies = FakeFeed.state.openThread!!.replies.take(2)) else it })
            },
        )
        feed(390, startAt = "u2", state = withReplies)
        onNode(hasContentDescription("You, 8:57", substring = true)).performTouchInput { longClick() }
        onNodeWithText("Delete").performClick()
        onNodeWithText("Delete this post and its 2 replies?").assertExists()
    }

    @Test
    fun theScreenReaderDeleteAsksToo() = runComposeUiTest {
        feed(390)
        val node = onAllNodes(SemanticsMatcher("has action 'Delete'") { n ->
            n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Delete" } == true
        }).onFirst().fetchSemanticsNode()
        runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Delete" }.action() }
        waitForIdle()
        assertTrue(events.none { it is FeedEvent.Delete }, "got $events")
        onNode(hasText("Delete this", substring = true)).assertExists()
    }
}
