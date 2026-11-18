package dev.wildware.agents.app

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Choice
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.PickBounds
import dev.wildware.agents.app.feed.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** A multi-choice question says how many to pick, and Send works only within that. */
@OptIn(ExperimentalTestApi::class)
class MultiChoiceBoundsTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()

    private fun ComposeUiTest.show(picks: PickBounds) {
        val q = base.copy(
            id = "qm", prompt = "Which platforms first?", body = "", kind = QuestionKind.MultiChoice,
            choices = listOf("iPhone", "Android", "Mac", "Windows").map { Choice(it) }, picks = picks,
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) { events += it } } } }
    }

    private fun ComposeUiTest.tap(vararg labels: String) = labels.forEach { onNodeWithText(it).performClick() }

    @Test
    fun betweenTwoAndThreeSendsOnlyWithTwoOrThree() = runComposeUiTest {
        show(PickBounds(2, 3, bounded = true))
        onNodeWithText("Choose between 2 and 3.").assertExists()
        tap("iPhone")
        onNodeWithText("Send 1 pick").assertIsNotEnabled()
        tap("Android")
        onNodeWithText("Send 2 picks").assertIsEnabled()
        tap("Mac", "Windows")
        onNodeWithText("Send 4 picks").assertIsNotEnabled()
        tap("Windows")
        onNodeWithText("Send 3 picks").performClick()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("qm", listOf("iPhone", "Android", "Mac"))), events)
    }

    @Test
    fun pickedOptionsShowAsPickedAndSendComesAfterThem() = runComposeUiTest {
        show(PickBounds(1, 4, bounded = false))
        tap("Mac")
        onNode(hasText("Mac") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        onNode(hasText("iPhone") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertExists()
        val sendTop = onNodeWithText("Send 1 pick").fetchSemanticsNode().positionInRoot.y
        val optionTop = onNodeWithText("Windows").fetchSemanticsNode().positionInRoot.y
        kotlin.test.assertTrue(sendTop > optionTop, "Send ($sendTop) comes after the options ($optionTop)")
    }

    @Test
    fun theHintSaysItPlainly() = runComposeUiTest {
        show(PickBounds(2, 4, bounded = true))
        onNodeWithText("Choose at least 2.").assertExists()
    }

    @Test
    fun upToTwo() = runComposeUiTest {
        show(PickBounds(1, 2, bounded = true))
        onNodeWithText("Choose up to 2.").assertExists()
        tap("Mac")
        onNodeWithText("Send 1 pick").assertIsEnabled()
    }

    @Test
    fun exactlyTwo() = runComposeUiTest {
        show(PickBounds(2, 2, bounded = true))
        onNodeWithText("Choose 2.").assertExists()
    }

    @Test
    fun noBoundsSaysNothingAndNeedsOnePick() = runComposeUiTest {
        show(PickBounds(1, 4, bounded = false))
        onNodeWithText("Choose", substring = true).assertDoesNotExist()
        onNodeWithText("Send").assertIsNotEnabled()
        tap("Mac")
        onNodeWithText("Send 1 pick").assertIsEnabled()
    }
}
