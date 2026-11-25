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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.PrView
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import kotlin.test.Test
import kotlin.test.assertEquals

/** A card's pull requests (server migration 079): a chip each, saying CI and reviews, that opens the PR. */
@OptIn(ExperimentalTestApi::class)
class PrChipsTest {
    private val opened = mutableListOf<String>()
    private val uris = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }

    private val state: FeedState = FakeFeed.state.copy(
        openThread = null,
        sections = FakeFeed.state.sections.map { s ->
            s.copy(items = s.items.map { item ->
                if (item is FeedItem.Update && item.id == "u3") item.copy(prs = listOf(
                    PrView("acme/app", 423, "https://github.com/acme/app/pull/423", state = "open", ci = "failing", review = "changes_requested", changesRequested = 1),
                    PrView("acme/plugin", 7, "https://github.com/acme/plugin/pull/7", state = "merged", ci = "passing"),
                    PrView("acme/web", 9, "https://github.com/acme/web/pull/9", state = "draft", ci = "pending"),
                )) else item
            })
        },
    )

    private fun ComposeUiTest.show(width: Int) = setContent {
        CompositionLocalProvider(LocalUriHandler provides uris) {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(state, startAt = "u3") }
            }
        }
    }

    @Test
    fun eachStateReadsAsItIsAndOpensThePr() = runComposeUiTest {
        show(width = 1440)
        onNodeWithText("#423 · CI failing · 1 change requested").performScrollTo().assertExists()
        onNodeWithText("#7 · Merged").assertExists()
        onNodeWithText("#9 · Draft · CI running").assertExists()
        onNode(hasClickAction() and hasContentDescription("Pull request 423 in acme/app: CI failing, 1 change requested. Opens on GitHub.")).performClick()
        assertEquals(listOf("https://github.com/acme/app/pull/423"), opened)
    }

    @Test
    fun fitsOnAPhone() = runComposeUiTest {
        show(width = 390)
        onNodeWithText("#423 · CI failing · 1 change requested").performScrollTo().assertExists()
    }
}
