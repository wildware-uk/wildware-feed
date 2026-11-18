package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

/** A card's fields ("Details"): out of the way until opened, then read, changed, removed and added. */
@OptIn(ExperimentalTestApi::class)
class CardFieldTest {
    private val events = mutableListOf<FeedEvent>()
    private val opened = mutableListOf<String>()

    private fun ComposeUiTest.feed(width: Int, startAt: String = "u1") = setContent {
        val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        CompositionLocalProvider(LocalUriHandler provides links) {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = startAt, onEvent = { events += it }) }
            }
        }
    }

    private fun ComposeUiTest.openDetails() {
        onNodeWithText("Details (2)").performScrollTo().performClick()
    }

    @Test
    fun detailsAreFoldedAwayUntilOpened() = runComposeUiTest {
        feed(390)
        onNodeWithText("Details (2)").performScrollTo()
        onNodeWithText("fix/unread-per-connection").assertDoesNotExist()
        openDetails()
        onNodeWithText("fix/unread-per-connection").assertExists()
    }

    @Test
    fun aLinkOpensInTheBrowser() = runComposeUiTest {
        feed(390)
        openDetails()
        onNodeWithText("https://example.com/pull/42").performScrollTo().performClick()
        assertEquals(listOf("https://example.com/pull/42"), opened)
    }

    @Test
    fun aValueCanBeChanged() = runComposeUiTest {
        feed(390)
        openDetails()
        onNodeWithContentDescription("Change branch").performScrollTo().performClick()
        onNodeWithContentDescription("Value for branch").performTextClearance()
        onNodeWithContentDescription("Value for branch").performTextInput("fix/unread-v2")
        onNode(hasText("Save") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SetField("u1", "branch", "fix/unread-v2")), events)
        onNodeWithText("fix/unread-v2").assertExists()
    }

    @Test
    fun aFieldCanBeRemoved() = runComposeUiTest {
        feed(390)
        openDetails()
        onNodeWithContentDescription("Remove branch").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SetField("u1", "branch", null)), events)
        onNodeWithText("Details (1)").assertExists()
    }

    @Test
    fun aCardWithNoneGetsOneFromItsMenu() = runDesktopComposeUiTest(1440, 900) {
        feed(1440, startAt = "u3")
        onNodeWithText("Build box disk is 86% full").performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Details…").performClick()
        onNodeWithText("Nothing noted on this card yet.").assertExists()
        onNodeWithContentDescription("Field name").performTextInput("disk")
        onNodeWithContentDescription("Field value").performTextInput("86%")
        onNode(hasText("Add") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SetField("u3", "disk", "86%")), events)
        onNodeWithText("Details (1)").assertExists()
    }
}
