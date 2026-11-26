package dev.wildware.agents.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.BoardCard
import dev.wildware.agents.app.feed.BoardColumn
import dev.wildware.agents.app.feed.BoardState
import dev.wildware.agents.app.feed.BoardStatuses
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.WorkStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A board card opens in a modal over the board, not the thread screen: closing it leaves the board
 * as it was, and a full thread opened from it goes Back to the board. One opened from the feed still
 * goes back to the feed.
 */
@OptIn(ExperimentalTestApi::class)
class BoardCardModalTest {
    private val agent = Agent("a1", "forge", "Builder", Presence.Online, hue = 0)
    private val project = FakeFeed.state.current

    private fun reply(n: Int) = Reply("r$n", Author.AgentAuthor(agent), "10:0$n", "Comment number $n.")

    private fun update(replies: Int = 2) = FeedItem.Update(
        "u-plan", "9:41", agent, project, Level.Info, "Plan the release", "Ship the **board modal** this week.",
        replies = List(replies) { reply(it) },
    )

    private val boardCard = BoardCard(kind = "update", id = "u-plan", title = "Plan the release", status = WorkStatus.Todo, channel = null, channelSlug = null, who = "forge", age = "1h")

    private fun board() = BoardState(columns = BoardStatuses.map { s -> BoardColumn(s, if (s == WorkStatus.Todo) listOf(boardCard) else emptyList()) }, loaded = true)

    private fun state(item: FeedItem.Update = update()): FeedState =
        FakeFeed.state.copy(openThread = null, sections = listOf(FeedSection("Today", listOf(item))), board = board())

    /** The screen on the board, answering a read of a card on its own with [lone] as the engine would. */
    private fun ComposeUiTest.screen(shown: MutableState<FeedState>, board: Boolean = true, navigator: AppNavigator? = null, lone: FeedItem? = null) {
        setContent {
            AgentTheme(dark = true) {
                FeedScreen(shown, preview = ScreenPreview(boardOpen = board), navigator = navigator) { event ->
                    if (event is FeedEvent.OpenAlone) {
                        if (lone != null && shown.value.item(event.id) == null) shown.value = shown.value.copy(lone = lone)
                        event.result(if (lone != null || shown.value.item(event.id) != null) event.id else null)
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
    }

    private fun ComposeUiTest.waitGone(matcher: SemanticsMatcher) {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("still saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
    }

    private fun ComposeUiTest.has(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val modal = hasTestTag("board-card-modal")
    private val todo = hasTestTag("board-column-todo")

    @Test
    fun aTapOnABoardCardOpensItsModalOverTheBoardNotTheThread() = runDesktopComposeUiTest(1440, 900) {
        val shown = mutableStateOf(state())
        screen(shown)
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        // The card and its comments, with a reply box and a way out.
        waitFor(hasText("Comment number 1.", substring = true))
        assertTrue(has(hasContentDescription("Reply")), "a reply box")
        assertTrue(has(hasContentDescription("Close card") and hasClickAction()), "a close button")
        assertTrue(has(hasText("Open full thread")), "a link to the full thread")
        // A pane a screen reader names, over the board, which stays put.
        val pane = onAllNodes(modal).onFirst().fetchSemanticsNode().config.getOrNull(SemanticsProperties.PaneTitle)
        assertTrue(pane == "Card", "pane title was $pane")
        assertTrue(has(todo), "the board is still there under it")
        assertTrue(!has(hasText("Back to feed")), "not the card on its own")
    }

    @Test
    fun escapeClosesTheModalAndTheBoardIsAsItWas() = runDesktopComposeUiTest(1440, 900) {
        val shown = mutableStateOf(state())
        screen(shown)
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        onAllNodes(modal).onFirst().performKeyInput { pressKey(Key.Escape) }
        waitGone(modal)
        assertTrue(has(todo) && has(hasTestTag("board-card-u-plan")), "back on the board")
        // The keyboard is back on the card it came from.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasTestTag("board-card-u-plan")).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        }
    }

    @Test
    fun onAPhoneClosingTheSheetKeepsTheColumnThatWasShowing() = runDesktopComposeUiTest(390, 844) {
        val shown = mutableStateOf(state())
        screen(shown)
        // The phone shows one column at a time; In progress first. Pick To do.
        waitFor(hasText("To do") and hasClickAction())
        onAllNodes(hasText("To do") and hasClickAction()).onFirst().performClick()
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        assertTrue(!has(hasText("Thread")), "not the thread screen")
        onAllNodes(hasContentDescription("Close card") and hasClickAction()).onFirst().performClick()
        waitGone(modal)
        // Still To do: the card is on screen without picking the column again.
        waitFor(hasTestTag("board-card-u-plan"))
    }

    @Test
    fun aNewCommentReachesTheOpenModal() = runDesktopComposeUiTest(1440, 900) {
        val shown = mutableStateOf(state(update(replies = 2)))
        screen(shown)
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        waitFor(hasText("Comment number 1.", substring = true))
        shown.value = state(update(replies = 3))
        waitFor(hasText("Comment number 2.", substring = true))
    }

    @Test
    fun theFullThreadFromTheModalGoesBackToTheBoard() = runDesktopComposeUiTest(1440, 900) {
        val shown = mutableStateOf(state())
        screen(shown)
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        onAllNodes(hasText("Open full thread")).onFirst().performClick()
        waitGone(modal)
        waitFor(hasText("Back to board"))
        onAllNodes(hasText("Back to board")).onFirst().performClick()
        waitGone(hasText("Back to board"))
        waitFor(todo)
        assertTrue(has(hasTestTag("board-card-u-plan")), "on the Board, not the Feed")
    }

    @Test
    fun onAPhoneTheFullThreadFromTheModalGoesBackToTheBoard() = runDesktopComposeUiTest(390, 844) {
        val shown = mutableStateOf(state())
        screen(shown)
        waitFor(hasText("To do") and hasClickAction())
        onAllNodes(hasText("To do") and hasClickAction()).onFirst().performClick()
        waitFor(hasTestTag("board-card-u-plan"))
        onAllNodes(hasTestTag("board-card-u-plan")).onFirst().performClick()
        waitFor(modal)
        onAllNodes(hasText("Open full thread")).onFirst().performClick()
        // The thread screen, its Back naming the board.
        waitFor(hasContentDescription("Back to board"))
        onAllNodes(hasContentDescription("Back to board")).onFirst().performClick()
        waitGone(hasContentDescription("Back to board"))
        // Back on the board, on the column it was on.
        waitFor(hasTestTag("board-card-u-plan"))
    }

    @Test
    fun aCardOpenedOnItsOwnFromTheFeedStillGoesBackToTheFeed() = runDesktopComposeUiTest(1440, 900) {
        val navigator = AppNavigator()
        val old = update().copy(id = "u-old", title = "Something from last month", body = "The old plan.")
        val shown = mutableStateOf(state())
        screen(shown, board = false, navigator = navigator, lone = old)
        waitFor(hasText("Plan the release"))
        navigator.open(project.slug, "u-old")
        waitFor(hasText("Back to feed"))
        waitFor(hasText("The old plan.", substring = true))
        onAllNodes(hasText("Back to feed")).onFirst().performClick()
        waitGone(hasText("Back to feed"))
        assertTrue(!has(todo), "the feed, not the board")
        waitFor(hasText("Plan the release"))
    }
}
