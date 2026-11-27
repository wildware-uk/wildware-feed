package dev.wildware.agents.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Acks
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.BoardCard
import dev.wildware.agents.app.feed.BoardColumn
import dev.wildware.agents.app.feed.BoardState
import dev.wildware.agents.app.feed.BoardStatuses
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.QuickReplies
import dev.wildware.agents.app.feed.Reaction
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.WorkStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While the owner is writing a reply, a new message (in that thread or anywhere else) never moves
 * the box, scrolls what they are reading, takes the cursor or changes what they wrote. A "New
 * message" pill says one came instead, and takes them to it when they ask.
 */
@OptIn(ExperimentalTestApi::class)
class TypingStaysPutTest {
    private val agent = Agent("a1", "forge", "Builder", Presence.Online, hue = 0)
    private val project = FakeFeed.state.current
    private val replyBox = hasSetTextAction() and hasContentDescription("Reply")
    private val draft = "half a thought about the"

    private fun reply(card: String, n: Int, body: String = "Comment number $n on $card.") =
        Reply("$card-r$n", Author.AgentAuthor(agent), "10:0$n", body)

    private fun card(n: Int, replies: Int = 1) = FeedItem.Update(
        "c$n", "9:4$n", agent, project, Level.Info, "Card number $n", "What card $n says.",
        replies = List(replies) { reply("c$n", it) },
    )

    private fun feed(order: List<FeedItem.Update>, open: dev.wildware.agents.app.feed.Threaded? = null, board: BoardState? = null): FeedState =
        FakeFeed.state.copy(openThread = open, sections = listOf(FeedSection("Today", order)), board = board ?: FakeFeed.state.board)

    /** What the engine sends when an agent replies on [item]: the reply, and the card first in the feed. */
    private fun replied(order: List<FeedItem.Update>, item: FeedItem.Update, body: String? = null): List<FeedItem.Update> {
        val next = item.copy(replies = item.replies + reply(item.id, item.replies.size, body ?: "A new comment on ${item.id}."))
        return listOf(next) + order.filter { it.id != item.id }
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
    }

    /** Where the box is, its words and its cursor. */
    private data class Box(val top: Float, val text: String, val cursor: TextRange?)

    private fun ComposeUiTest.box(): Box {
        val node = onNode(replyBox).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return Box(node.boundsInRoot.top, text, node.config.getOrNull(SemanticsProperties.TextSelectionRange))
    }

    private fun ComposeUiTest.write() {
        onNode(replyBox).performClick()
        onNode(replyBox).performTextInput(draft)
        waitForIdle()
        onNode(replyBox).assertIsFocused()
    }

    private fun ComposeUiTest.assertStill(before: Box) {
        waitForIdle()
        onNode(replyBox).assertIsDisplayed()
        onNode(replyBox).assertIsFocused()
        assertEquals(before, box(), "the box, its words and its cursor are where they were")
    }

    private fun ComposeUiTest.desktopFeed(state: MutableState<FeedState>, startAt: String?) {
        setContent { AgentTheme(dark = false) { FeedScreen(state.value, startAt = startAt) } }
        waitForIdle()
    }

    private val cards = (1..8).map { card(it) }

    @Test
    fun onADesktopAReplyOnTheCardBeingAnsweredLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val state = mutableStateOf(feed(cards, open = cards[4]))
        desktopFeed(state, startAt = "c4")
        write()
        val before = box()
        state.value = feed(replied(cards, cards[4]), open = cards[4])
        assertStill(before)
    }

    @Test
    fun onADesktopAReplyOnTheCardAboveLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val state = mutableStateOf(feed(cards, open = cards[4]))
        desktopFeed(state, startAt = "c4")
        write()
        val before = box()
        state.value = feed(replied(cards, cards[3]), open = cards[4])
        assertStill(before)
    }

    @Test
    fun onADesktopANewCardAtTheTopLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val state = mutableStateOf(feed(cards, open = cards[0]))
        desktopFeed(state, startAt = null)
        write()
        val before = box()
        state.value = feed(listOf(card(9)) + cards, open = cards[0])
        assertStill(before)
        // It waits under a pill instead, which lets it in.
        onNode(hasText("1 new update") and hasClickAction()).assertIsDisplayed()
        onNode(hasText("Card number 9")).assertDoesNotExist()
        onNode(hasText("1 new update") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Card number 9")).assertIsDisplayed()
    }

    @Test
    fun onADesktopALongerMessageAboveLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val state = mutableStateOf(feed(cards, open = cards[4]))
        desktopFeed(state, startAt = "c4")
        write()
        val before = box()
        // Card 4's comment is changed to something much longer, as an edit or new buttons make it.
        val longer = cards[3].copy(replies = listOf(reply("c4", 0, "A much longer comment. ".repeat(40))))
        state.value = feed(cards.map { if (it.id == "c4") longer else it }, open = cards[4])
        assertStill(before)
    }

    @Test
    fun onADesktopSendingStillFollowsTheCardToTheTop() = runDesktopComposeUiTest(1440, 900) {
        val state = mutableStateOf(feed(cards, open = cards[4]))
        desktopFeed(state, startAt = "c4")
        write()
        val box = onNode(replyBox).fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        sends[sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == box.top }].performClick()
        waitForIdle()
        state.value = feed(replied(cards, cards[4]), open = cards[4])
        waitForIdle()
        onNode(hasText("Card number 5")).assertIsDisplayed()
        onNode(hasText("Card number 1")).assertIsDisplayed()
    }

    private val thread = card(1, replies = 3)

    @Test
    fun onAPhoneANewReplyInTheThreadLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val state = mutableStateOf(feed(listOf(thread), open = thread))
        setContent { AgentTheme(dark = false) { FeedScreen(state.value, preview = ScreenPreview(threadOpen = true)) } }
        waitForIdle()
        write()
        val before = box()
        val next = replied(listOf(thread), thread).first()
        state.value = feed(listOf(next), open = next)
        assertStill(before)
    }

    private fun board(id: String) = BoardState(
        columns = BoardStatuses.map { s ->
            val card = BoardCard(kind = "update", id = id, title = "Card number 1", status = WorkStatus.Todo, channel = null, channelSlug = null, who = "forge", age = "1h")
            BoardColumn(s, if (s == WorkStatus.Todo) listOf(card) else emptyList())
        },
        loaded = true,
    )

    private fun ComposeUiTest.boardModal(state: MutableState<FeedState>, phone: Boolean) {
        setContent { AgentTheme(dark = false) { FeedScreen(state.value, preview = ScreenPreview(boardOpen = true)) } }
        if (phone) {
            waitFor(hasText("To do") and hasClickAction())
            onAllNodes(hasText("To do") and hasClickAction()).onFirst().performClick()
        }
        waitFor(hasTestTag("board-card-c1"))
        onAllNodes(hasTestTag("board-card-c1")).onFirst().performClick()
        waitFor(hasTestTag("board-card-modal"))
        waitFor(replyBox)
    }

    private fun ComposeUiTest.scrolled() =
        onNode(hasTestTag("board-card-body")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()

    private fun ComposeUiTest.aNewCommentInTheModal(phone: Boolean, count: Int) {
        val long = card(1, replies = count)
        val state = mutableStateOf(feed(listOf(long), board = board("c1")))
        boardModal(state, phone)
        write()
        val before = box()
        val was = scrolled()
        state.value = feed(replied(listOf(long), long, body = "The very newest comment."), board = board("c1"))
        assertStill(before)
        assertEquals(was, scrolled(), "the comments are scrolled where they were")
        // The pill says so, and takes the owner down to it, without taking the cursor out of the box.
        val pill = hasText("New message") and hasClickAction()
        waitFor(pill)
        onNode(pill).performClick()
        waitForIdle()
        onNode(hasText("The very newest comment.", substring = true)).assertIsDisplayed()
        assertTrue(onAllNodes(pill).fetchSemanticsNodes().isEmpty(), "the pill goes once it is seen")
    }

    @Test
    fun onADesktopANewCommentInTheBoardCardLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) { aNewCommentInTheModal(phone = false, count = 12) }

    @Test
    fun onAPhoneANewCommentInTheBoardCardLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) { aNewCommentInTheModal(phone = true, count = 12) }

    @Test
    fun noDraftMeansTheBoardCardStillScrollsToANewComment() = runDesktopComposeUiTest(1440, 900) {
        val long = card(1, replies = 12)
        val state = mutableStateOf(feed(listOf(long), board = board("c1")))
        boardModal(state, phone = false)
        waitForIdle()
        state.value = feed(replied(listOf(long), long, body = "The very newest comment."), board = board("c1"))
        waitForIdle()
        onNode(hasText("The very newest comment.", substring = true)).assertIsDisplayed()
        assertTrue(onAllNodes(hasText("New message", substring = true)).fetchSemanticsNodes().isEmpty(), "no pill when not typing")
    }

    /** The owner's own post, as the head of a thread: the card agents ack, react to and that gets edited. */
    private fun post(replies: Int = 2) = FeedItem.Post(
        "p1", "9:41", FakeFeed.state.owner, project, "Can someone look at why the badge count is off on the watch?",
        replies = List(replies) { reply("p1", it) },
    )

    private fun ComposeUiTest.phoneThread(state: MutableState<FeedState>) {
        setContent { AgentTheme(dark = false) { FeedScreen(state.value, preview = ScreenPreview(threadOpen = true)) } }
        waitForIdle()
    }

    private fun MutableState<FeedState>.head(next: FeedItem.Post) {
        value = feed(emptyList(), open = next).copy(sections = listOf(FeedSection("Today", listOf(next))))
    }

    /** On a phone, the head card above the box changes: [changes] in turn, each leaving the box where it was. */
    private fun ComposeUiTest.onAPhoneTheHeadCard(start: FeedItem.Post, vararg changes: FeedItem.Post) {
        val state = mutableStateOf(feed(emptyList()))
        state.head(start)
        phoneThread(state)
        write()
        val before = box()
        for (next in changes) {
            state.head(next)
            assertStill(before)
        }
    }

    private val thinking = Acks(thinking = listOf(agent))

    @Test
    fun onAPhoneAnAckLineComingAndGoingOnTheHeadCardLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post()
        onAPhoneTheHeadCard(start, start.copy(acks = thinking), start.copy(acks = Acks(done = listOf(agent))), start)
    }

    @Test
    fun onAPhoneAnEditToTheHeadCardLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post()
        val longer = start.copy(body = start.body + "\n\n" + "It shows one more than it should after a restart. ".repeat(8), edited = "Edited just now")
        onAPhoneTheHeadCard(start, longer, start)
    }

    @Test
    fun onAPhoneAReactionOnTheHeadCardLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post()
        onAPhoneTheHeadCard(start, start.copy(reactions = listOf(Reaction("👀", 1, mine = false, spoken = "eyes"))))
    }

    @Test
    fun onAPhoneTheFirstReplyLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post(replies = 0)
        onAPhoneTheHeadCard(start, start.copy(replies = listOf(reply("p1", 0)), acks = thinking), start.copy(replies = List(2) { reply("p1", it) }))
    }

    @Test
    fun onAPhoneAHeadCardChangeInALongThreadLeavesTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post(replies = 14)
        onAPhoneTheHeadCard(start, start.copy(acks = thinking), start.copy(acks = thinking, reactions = listOf(Reaction("👍", 2, mine = false, spoken = "thumbs up"))), start)
    }

    @Test
    fun onADesktopAnAckLineOnTheCardBeingAnsweredLeavesTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val others = (1..6).map { card(it) }
        val mine = post()
        val state = mutableStateOf(FakeFeed.state.copy(openThread = mine, sections = listOf(FeedSection("Today", others.take(3) + listOf(mine) + others.drop(3)))))
        setContent { AgentTheme(dark = false) { FeedScreen(state.value, startAt = "c3") } }
        waitForIdle()
        write()
        val before = box()
        for (next in listOf(mine.copy(acks = thinking), mine.copy(acks = thinking, reactions = listOf(Reaction("👀", 1, mine = false, spoken = "eyes"))), mine)) {
            state.value = state.value.copy(openThread = next, sections = listOf(FeedSection("Today", others.take(3) + listOf(next) + others.drop(3))))
            assertStill(before)
        }
    }

    @Test
    fun onAPhoneQuickReplyButtonsOnTheHeadCardLeaveTheBoxAlone() = runDesktopComposeUiTest(390, 844) {
        val start = post()
        onAPhoneTheHeadCard(start, start.copy(quickReplies = QuickReplies(messageId = "p1", buttons = listOf("Ship it", "Try another"))), start)
    }

    @Test
    fun onADesktopQuickReplyButtonsOnACommentInTheBoardCardLeaveTheBoxAlone() = runDesktopComposeUiTest(1440, 900) {
        val few = card(1, replies = 2)
        val state = mutableStateOf(feed(listOf(few), board = board("c1")))
        boardModal(state, phone = false)
        write()
        val before = box()
        val buttons = QuickReplies(messageId = few.replies.last().id, buttons = listOf("Ship it", "Try another"))
        state.value = feed(listOf(few.copy(replies = few.replies.dropLast(1) + few.replies.last().copy(quickReplies = buttons))), board = board("c1"))
        assertStill(before)
    }
}
