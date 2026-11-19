package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.MentionText
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reply on a comment: the reply box names who wrote it ("@forge "), says "Replying to forge" with a
 * way to call it off, and what is sent answers that comment.
 */
@OptIn(ExperimentalTestApi::class)
class ReplyToCommentTest {
    private val events = mutableListOf<FeedEvent>()
    private val fix = FakeFeed.state.openThread as FeedItem.Update
    private val state by mutableStateOf(
        FakeFeed.state.copy(openThread = null, sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(fix)))),
    )
    private val replyBox = hasSetTextAction() and hasContentDescription("Reply")

    private fun ComposeUiTest.desktop() {
        setContent { AgentTheme(dark = false) { FeedScreen(state, onEvent = { events += it }) } }
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText(fix.replies.first().body.take(20), substring = true))
    }

    private fun ComposeUiTest.phone() = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(390.dp, 844.dp)) {
                FeedScreen(FakeFeed.state, preview = ScreenPreview(threadOpen = true), onEvent = { events += it })
            }
        }
    }

    /** Points at the comment that starts with [body], as a mouse does, and presses the Reply that shows. */
    private fun ComposeUiTest.hoverReply(body: String, who: String) {
        onAllNodes(hasText(body, substring = true)).onFirst().performMouseInput { moveTo(center) }
        waitForIdle()
        onNode(hasContentDescription("Reply to $who")).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.typed(): String =
        onNode(replyBox).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun ComposeUiTest.send() {
        onNode(replyBox).performTextInput("thanks")
        val field = onNode(replyBox).fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        val thread = sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == field.top }
        sends[thread].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun sent() = events.filterIsInstance<FeedEvent.SendReply>().single()

    @Test
    fun replyOnAnAgentsCommentNamesItAndAnswersIt() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        hoverReply("Confirmed from my side", "scout")
        onNode(hasText("Replying to scout")).assertExists()
        assertEquals("@scout ", typed())
        onNode(replyBox).assertIsFocused()
        send()
        assertEquals(FeedEvent.SendReply("u1", "@scout thanks", answers = "r3"), sent())
    }

    @Test
    fun replyOnTheOwnersOwnCommentNamesNobody() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        hoverReply("Nice. Does this also fix", "yourself")
        onNode(hasText("Replying to yourself")).assertExists()
        assertEquals("", typed())
        send()
        assertEquals(FeedEvent.SendReply("u1", "thanks", answers = "r1"), sent())
    }

    @Test
    fun aSecondReplySwapsTheNameButKeepsWhatWasWritten() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        hoverReply("Yes. The watch reads", "forge")
        assertEquals("@forge ", typed())
        hoverReply("Confirmed from my side", "scout")
        assertEquals("@scout ", typed(), "only the mention was there, so it is swapped")
        onNode(replyBox).performTextInput("and @forge too")
        hoverReply("Deployed to the box", "relay")
        assertEquals("@relay @scout and @forge too", typed(), "words the owner wrote stay")
    }

    @Test
    fun callingItOffSendsToTheWholeThread() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        hoverReply("Confirmed from my side", "scout")
        onNode(hasContentDescription("Stop replying to scout")).performClick()
        waitForIdle()
        onNode(hasText("Replying to scout")).assertDoesNotExist()
        assertEquals("", typed(), "the mention the Reply put there goes with it")
        send()
        assertEquals(FeedEvent.SendReply("u1", "thanks", answers = null), sent())
    }

    @Test
    fun theReplyButtonShowsOnlyUnderThePointer() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        onNode(hasContentDescription("Reply to scout")).assertDoesNotExist()
        onAllNodes(hasText("Confirmed from my side", substring = true)).onFirst().performMouseInput { moveTo(center) }
        waitForIdle()
        onNode(hasContentDescription("Reply to scout")).assertExists()
        onNode(hasContentDescription("Reply to forge")).assertDoesNotExist()
    }

    @Test
    fun anAnswerSaysWhoItAnswers() = runDesktopComposeUiTest(width = 1440, height = 1600) {
        desktop()
        val spoken = SemanticsMatcher("says it answers you") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("forge, answering you") } == true
        }
        // The words are drawn too, but read as part of the line's header, so only that is in the tree.
        onNode(spoken).assertExists()
    }

    @Test
    fun onAPhoneASwipesReplyNamesTheAgentToo() = runDesktopComposeUiTest(width = 390, height = 844) {
        phone()
        // No hover on a phone: the swipe (here its screen reader twin) is the way in.
        onNode(hasContentDescription("Reply to scout")).assertDoesNotExist()
        val action = SemanticsMatcher("has action 'Reply'") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Reply" } == true
        }
        val node = onAllNodes(action and hasAnyDescendant(hasText("Confirmed from my side", substring = true))).fetchSemanticsNodes().last()
        runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Reply" }.action() }
        waitForIdle()
        onNode(hasText("Replying to scout")).assertExists()
        assertEquals("@scout ", typed())
        onNode(replyBox).assertIsFocused()
    }

    // --- The words the box ends up with -----------------------------------------------------

    private val known = listOf("here", "everyone", "forge", "scout", "Big Agent")

    @Test
    fun retargetingFillsSwapsAndKeeps() {
        assertEquals("@forge ", MentionText.retarget("", "forge", known))
        assertEquals("@scout ", MentionText.retarget("@forge ", "scout", known))
        assertEquals("@Big Agent ", MentionText.retarget(" @forge", "Big Agent", known))
        assertEquals("", MentionText.retarget("@Big Agent ", null, known))
        assertEquals("@scout look at this", MentionText.retarget("look at this", "scout", known))
        assertEquals("cc @Scout, look", MentionText.retarget("cc @Scout, look", "scout", known), "already named")
        assertEquals("look at this", MentionText.retarget("look at this", null, known))
        assertEquals("@scout @scouting", MentionText.retarget("@scouting", "scout", known), "a longer name is not this one")
    }

    @Test
    fun aMentionIsReadAsTheDashboardReadsIt() {
        assertTrue(MentionText.names("hi @forge.", "forge"))
        assertTrue(!MentionText.names("mail a@forge", "forge"))
        assertTrue(!MentionText.names("@forge-2 go", "forge"))
        assertTrue(MentionText.names("@x @FORGE", "forge"))
    }
}
