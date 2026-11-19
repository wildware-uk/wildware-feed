package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
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
import dev.wildware.agents.app.feed.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** A choice question opens on the agent's default, as the website does. */
@OptIn(ExperimentalTestApi::class)
class ChoiceDefaultTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()

    private fun ComposeUiTest.show(preset: String?) {
        val q = base.copy(
            id = "qc", prompt = "Which branch?", body = "", kind = QuestionKind.Choice,
            choices = listOf("main", "release", "hotfix").map { Choice(it) }, preset = preset,
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state) { events += it } } } }
    }

    private fun picked(label: String, value: Boolean) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, value)

    @Test
    fun theDefaultIsPickedAndSendsAsItIs() = runComposeUiTest {
        show("release")
        onNode(picked("release", true)).assertExists()
        onNode(picked("main", false)).assertExists()
        onNodeWithText("Send 1 pick").assertIsEnabled().performClick()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("qc", listOf("release"))), events)
    }

    @Test
    fun pickingAnotherReplacesTheDefault() = runComposeUiTest {
        show("release")
        onNodeWithText("hotfix").performClick()
        onNode(picked("release", false)).assertExists()
        onNodeWithText("Send 1 pick").performClick()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("qc", listOf("hotfix"))), events)
    }

    @Test
    fun withNoDefaultNothingIsPicked() = runComposeUiTest {
        show(null)
        onNode(picked("main", false)).assertExists()
        onNodeWithText("Send").assertIsNotEnabled()
    }
}
