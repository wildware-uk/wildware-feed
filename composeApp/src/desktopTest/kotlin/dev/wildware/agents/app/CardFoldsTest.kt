package dev.wildware.agents.app

import androidx.compose.ui.test.performScrollToNode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardFolds
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.LocalCardFolds
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cards the owner folds away stay folded on this device, as the website remembers them in the browser. */
@OptIn(ExperimentalTestApi::class)
class CardFoldsTest {
    @Test
    fun aFoldIsKeptAndReadBackByTheNextStart() {
        val store = MemoryTextStore()
        CardFolds(store).set("u1", folded = true)
        assertTrue(CardFolds(store).isFolded("u1"))
        CardFolds(store).set("u1", folded = false)
        assertFalse(CardFolds(store).isFolded("u1"))
    }

    @Test
    fun onlyTheNewestFiveHundredAreKept() {
        val store = MemoryTextStore()
        val folds = CardFolds(store)
        (1..501).forEach { folds.set("c$it", folded = true) }
        val again = CardFolds(store)
        assertFalse(again.isFolded("c1"), "the oldest is forgotten")
        assertTrue(again.isFolded("c2"))
        assertTrue(again.isFolded("c501"))
    }

    @Test
    fun unreadableTextIsNoFolds() {
        assertFalse(CardFolds(MemoryTextStore("not json")).isFolded("u1"))
        assertFalse(CardFolds(MemoryTextStore("""{"u1":true}""")).isFolded("u1"))
    }

    private val fix = FakeFeed.state.openThread as FeedItem.Update

    private fun ComposeUiTest.show(folds: CardFolds, width: Int = 1440, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalCardFolds provides folds) {
            AgentTheme(dark = false) { Box(Modifier.size(width.dp, 2400.dp)) { FeedScreen(FakeFeed.state, preview = preview) } }
        }
    }

    /** Scrolls the feed until a node matching [m] is drawn: cards further down aren't composed yet. */
    private fun ComposeUiTest.reveal(m: androidx.compose.ui.test.SemanticsMatcher) =
        onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(m)

    @Test
    fun foldingAnUpdateLeavesItsHeaderAndOneLineAndIsRemembered() = runComposeUiTest {
        val store = MemoryTextStore()
        show(CardFolds(store))
        reveal(androidx.compose.ui.test.hasContentDescription("Collapse update: ${fix.title}"))
        onNodeWithText("Unread state is now kept", substring = true).assertExists()
        onNodeWithContentDescription("Collapse update: ${fix.title}").performClick()
        onNodeWithText("Unread state is now kept", substring = true).assertDoesNotExist()
        onNodeWithText(fix.title).assertExists()
        onNodeWithText("Two sessions on the same token", substring = true).assertExists()
        onNodeWithText("4 replies hidden").assertExists()
        assertTrue(CardFolds(store).isFolded(fix.id), "kept for next time")
        onNodeWithContentDescription("Expand update: ${fix.title}").performClick()
        onNodeWithText("Unread state is now kept", substring = true).assertExists()
        assertFalse(CardFolds(store).isFolded(fix.id))
    }

    @Test
    fun aFoldedCardOpensFoldedNextTime() = runComposeUiTest {
        val store = MemoryTextStore()
        CardFolds(store).set(fix.id, folded = true)
        show(CardFolds(store))
        reveal(androidx.compose.ui.test.hasText("4 replies hidden"))
        onNodeWithText("4 replies hidden").assertExists()
        onNodeWithText("Unread state is now kept", substring = true).assertDoesNotExist()
    }

    @Test
    fun postsFoldToo() = runComposeUiTest {
        val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
        show(CardFolds(MemoryTextStore()))
        reveal(androidx.compose.ui.test.hasContentDescription("Collapse post", substring = true))
        onNodeWithContentDescription("Collapse post", substring = true).performClick()
        onNodeWithContentDescription("Expand post", substring = true).assertExists()
        onNodeWithText(post.body, substring = true).assertExists()
    }

    @Test
    fun questionsFoldToTheirQuestionAndCannotBeAnsweredFolded() = runComposeUiTest {
        val q = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val store = MemoryTextStore()
        show(CardFolds(store))
        onNodeWithText(q.choices.first().label).assertExists()
        onNodeWithContentDescription("Collapse question: ${q.prompt}").performClick()
        onNodeWithText(q.prompt).assertExists()
        onNodeWithText("Open it to answer.").assertExists()
        onNodeWithText(q.choices.first().label).assertDoesNotExist()
        assertTrue(CardFolds(store).isFolded(q.id), "kept for next time")
        onNodeWithContentDescription("Expand question: ${q.prompt}").performClick()
        onNodeWithText(q.choices.first().label).assertExists()
    }

    @Test
    fun aFoldedCardIsWholeInItsOwnThread() = runComposeUiTest {
        val folds = CardFolds(MemoryTextStore()).also { it.set(fix.id, folded = true) }
        show(folds, width = 390, preview = ScreenPreview(threadOpen = true))
        onNode(androidx.compose.ui.test.hasText("Unread state is now kept", substring = true) and androidx.compose.ui.test.hasAnyAncestor(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Thread"),
        )).assertExists()
    }
}
