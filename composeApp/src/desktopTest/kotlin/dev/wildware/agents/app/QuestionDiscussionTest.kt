package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Acks
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Reply
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A question can be talked over before it is answered, as on the website: replies under it, with
 * their reactions and "is thinking…", and a way to add one, on a desktop and on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class QuestionDiscussionTest {
    private val events = mutableListOf<FeedEvent>()
    private val asked = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
    private val agent = asked.agent

    private fun withTalk(replies: List<Reply>): FeedState {
        val q = asked.copy(replies = replies, unreadReplies = 0)
        return FakeFeed.state.copy(sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == q.id) q else it }) })
    }

    private val talk = listOf(
        Reply("d1", Author.AgentAuthor(agent), "10:01", "Happy to wait if you want numbers first."),
        Reply("d2", FakeFeed.state.owner, "10:03", "What did the test group say about the volume?", acks = Acks(thinking = listOf(agent))),
    )

    private fun ComposeUiTest.desktop(state: FeedState) = setContent {
        AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 1600.dp)) { FeedScreen(state) { events += it } } }
    }

    private fun ComposeUiTest.phone(state: FeedState) = setContent {
        AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state) { events += it } } }
    }

    @Test
    fun onADesktopTheTalkSitsUnderTheQuestionWithItsAcks() = runComposeUiTest {
        desktop(withTalk(talk))
        onNodeWithText("Happy to wait if you want numbers first.").assertExists()
        onNodeWithText("What did the test group say about the volume?").assertExists()
        onNode(hasContentDescription("${agent.name} is thinking…")).assertExists()
        // The answer buttons are still there: talking it over doesn't answer it.
        onNodeWithText(asked.choices.first().label).assertExists()
    }

    @Test
    fun onADesktopDiscussOpensABoxThatSendsIntoTheQuestion() = runComposeUiTest {
        desktop(withTalk(emptyList()))
        onAllNodesWithText("Discuss")[0].performScrollTo().performClick()
        val box = hasSetTextAction() and hasContentDescription("Reply")
        onNode(box).performTextInput("Can you share the numbers?")
        onNode(box).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
        waitForIdle()
        assertTrue(events.any { it is FeedEvent.SendReply && it.threadId == asked.id && it.text == "Can you share the numbers?" }, "got $events")
    }

    private val inThread = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread"))

    @Test
    fun onAPhoneDiscussOpensTheQuestionsOwnThread() = runComposeUiTest {
        phone(withTalk(talk))
        onNodeWithContentDescription("Discuss, 2 replies").performScrollTo().performClick()
        waitForIdle()
        // The thread shows the question, still answerable, then the talk about it.
        onNode(hasText(asked.prompt) and inThread).assertExists()
        onNode(hasText(asked.choices.first().label) and inThread).assertExists()
        onNode(hasText("Happy to wait if you want numbers first.") and inThread).assertExists()
        val box = hasSetTextAction() and hasContentDescription("Reply") and inThread
        onNode(box).performTextInput("Send me the volume numbers")
        onNode(hasContentDescription("Send") and inThread).performClick()
        waitForIdle()
        assertTrue(events.any { it is FeedEvent.SendReply && it.threadId == asked.id }, "got $events")
    }

    @Test
    fun onAPhoneAQuestionWithNoTalkStillOffersDiscuss() = runComposeUiTest {
        phone(withTalk(emptyList()))
        onAllNodesWithContentDescription("Discuss")[0].performScrollTo().assertExists()
    }
}
