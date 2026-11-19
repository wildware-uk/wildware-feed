package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.FormField
import dev.wildware.agents.app.feed.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Writing in a question's box never settles the question. Typing, selecting, dragging inside the
 * box, scrolling past it and tapping Discuss leave it waiting; only an explicit swipe or button
 * answers or dismisses it. On a phone, for each kind of question with a box.
 */
@OptIn(ExperimentalTestApi::class)
class QuestionInputTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first { it.id == "q1" }
    private val card = "Question from ${base.agent.name}, waiting on you"

    private val draft = "AI coding agents are fast until they need a decision. Then they wait for you to get back to your desk."

    private val form = base.copy(
        prompt = "Post this?", body = "", kind = QuestionKind.Form, choices = emptyList(), allowsText = false,
        form = FormField(label = "Post", initial = draft, multiline = true, actions = listOf("Approve", "Reject")),
    )
    private val text = base.copy(
        prompt = "What should the release be called?", body = "", kind = QuestionKind.Text, choices = emptyList(), allowsText = true,
        text = FormField(placeholder = "A name", min = 1),
    )
    private val choice = base.copy(kind = QuestionKind.Choice)

    private fun ComposeUiTest.phone(q: FeedItem.Question) {
        val state: FeedState = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state) { events += it } } } }
    }

    private val notInThread = SemanticsMatcher("not in the thread") { node ->
        generateSequence(node.parent) { it.parent }.none { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Thread" }
    }

    private fun ComposeUiTest.box(q: FeedItem.Question): SemanticsNodeInteraction =
        onNode(hasSetTextAction() and hasContentDescription(if (q.kind == QuestionKind.Form) "Post" else "Your answer") and notInThread)

    /** Whatever settles a question: an answer of any kind, or a dismissal. */
    private fun settled() = events.filter {
        it is FeedEvent.DismissQuestion || it is FeedEvent.Answer || it is FeedEvent.AnswerText || it is FeedEvent.AnswerForm
    }

    private fun assertStillWaiting() {
        assertTrue(settled().isEmpty(), "the question was settled: $events")
    }

    private val questions get() = listOf(form, text, choice)

    @Test
    fun typingInTheBoxNeverSettlesTheQuestion() {
        for (q in questions) {
            events.clear()
            runComposeUiTest {
                phone(q)
                box(q).performScrollTo().performClick()
                box(q).performTextInput(" now then")
                // The card's own shortcuts (N for not now, Y for yes, numbers to pick) are keys a
                // sentence is made of: in the box they are just letters.
                box(q).performKeyInput { for (k in listOf(Key.N, Key.Y, Key.One, Key.Two)) pressKey(k) }
                waitForIdle()
                assertStillWaiting()
            }
        }
    }

    @Test
    fun selectingAndDraggingInsideTheBoxNeverSettlesTheQuestion() {
        for (q in questions) {
            events.clear()
            runComposeUiTest {
                phone(q)
                box(q).performScrollTo().performClick()
                box(q).performTextInput("Juniper Lake")
                box(q).performTextInputSelection(TextRange(0, 7))
                // A long drag left from inside the box (moving the cursor, picking words), well past where a swipe commits.
                box(q).performTouchInput { swipe(center, center - Offset(260f, 0f), 200) }
                // A short, fast flick left: enough to fling a swipe through.
                box(q).performTouchInput { swipe(center, center - Offset(80f, 0f), 30) }
                // And the other way, where a swipe answers.
                box(q).performTouchInput { swipe(center, center + Offset(260f, 0f), 200) }
                waitForIdle()
                assertStillWaiting()
            }
        }
    }

    @Test
    fun whileTheKeyboardIsUpSwipingTheCardDoesNothing() {
        for (q in questions) {
            events.clear()
            runComposeUiTest {
                phone(q)
                box(q).performScrollTo().performClick()
                box(q).performTextInput("half written")
                // The keyboard is up; a swipe that starts on the question's words, outside the box, still does nothing.
                onNodeWithContentDescription(card).performTouchInput { swipe(Offset(centerX, 60f), Offset(centerX - 260f, 60f), 200) }
                waitForIdle()
                assertStillWaiting()
            }
        }
    }

    @Test
    fun scrollingPastTheCardWithASlantNeverSettlesIt() {
        for (q in questions) {
            events.clear()
            runComposeUiTest {
                phone(q)
                // A thumb scrolls in an arc: mostly up, a little sideways, and fast.
                onNodeWithContentDescription(card).performTouchInput { swipe(Offset(centerX + 60f, 300f), Offset(centerX - 20f, 40f), 120) }
                waitForIdle()
                assertStillWaiting()
            }
        }
    }

    @Test
    fun tappingDiscussOpensTheThreadAndLeavesTheQuestionWaiting() {
        for (q in questions) {
            events.clear()
            runComposeUiTest {
                phone(q)
                box(q).performScrollTo().performClick()
                box(q).performTextInput("draft")
                onNodeWithContentDescription("Discuss").performScrollTo().performClick()
                waitForIdle()
                val inThread = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread"))
                val reply = hasSetTextAction() and hasContentDescription("Reply") and inThread
                onNode(reply).performClick()
                onNode(reply).performTextInput("Can you make it shorter?")
                onNode(reply).performKeyInput { for (k in listOf(Key.N, Key.Y, Key.One)) pressKey(k) }
                waitForIdle()
                assertStillWaiting()
                assertTrue(events.any { it is FeedEvent.OpenThread && it.cardId == q.id }, "got $events")
            }
        }
    }

    @Test
    fun aDeliberateSwipeLeftStillDismissesAFormQuestion() = runComposeUiTest {
        phone(form)
        onNodeWithContentDescription(card).performTouchInput { swipe(Offset(centerX, 60f), Offset(centerX - 260f, 60f), 200) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DismissQuestion(form.id)), settled())
    }

    @Test
    fun theCardsShortcutsStillWorkWhenTheCardItselfHasFocus() = runComposeUiTest {
        phone(form)
        onNodeWithContentDescription(card).requestFocus()
        onNodeWithContentDescription(card).performKeyInput { pressKey(Key.N) }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DismissQuestion(form.id)), settled())
    }
}
