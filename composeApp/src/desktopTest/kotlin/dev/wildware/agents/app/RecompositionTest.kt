package dev.wildware.agents.app

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.MarkdownCache
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.Project
import dev.wildware.agents.app.feed.Reply
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How much of the screen redraws when the engine hands over a new state. Counted, not timed, so it
 * holds on a busy machine: every composable that runs is one count.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class RecompositionTest {
    private val agent = Agent("a1", "forge", "Builder", Presence.Online, hue = 0)
    private val project = Project("p1", "agent-dashboard", unread = 0, hue = 0, slug = "agent-dashboard")
    private val body = "Each session now keeps its own **last read** marker.\n\n- one cursor per connection\n- `heartbeat` says how many wait"

    private fun reply(card: Int, n: Int) =
        Reply("r$card-$n", if (n % 2 == 0) Author.Owner("You") else Author.AgentAuthor(agent), "10:0$n", "Reply $n with *some* `code`.")

    private fun card(i: Int, replies: Int = 3): FeedItem = if (i % 3 == 0) {
        FeedItem.Post("c$i", "9:41", Author.AgentAuthor(agent), project, "Post $i\n\n$body", replies = List(replies) { reply(i, it) })
    } else {
        FeedItem.Update("c$i", "9:41", agent, project, Level.Success, "Update $i", body, replies = List(replies) { reply(i, it) })
    }

    /** What the engine does on every publish: the same cards, mapped afresh as new objects. */
    private fun fresh(item: FeedItem): FeedItem = when (item) {
        is FeedItem.Update -> item.copy(replies = item.replies.map { it.copy() })
        is FeedItem.Post -> item.copy(replies = item.replies.map { it.copy() })
        is FeedItem.Question -> item.copy()
    }

    private fun state(cards: List<FeedItem>): FeedState = FakeFeed.state.copy(openThread = null, sections = listOf(FeedSection("Today", cards)))

    private val calls = HashMap<String, Int>()

    private fun count() {
        calls.clear()
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (").substringAfterLast('.')
                calls[name] = (calls[name] ?: 0) + 1
            }
            override fun traceEventEnd() {}
        })
    }

    @AfterTest
    fun stopCounting() = Composer.setTracer(null)

    @Test
    fun anEqualStateRedrawsNothingAndOneReplyRedrawsOneCard() = runDesktopComposeUiTest(1440, 900) {
        var cards = List(120) { card(it) }
        val shown = mutableStateOf(state(cards))
        setContent { AgentTheme(dark = true) { FeedScreen(shown) } }
        waitForIdle()

        count()
        shown.value = state(cards.map(::fresh))
        waitForIdle()
        assertEquals(0, calls.values.sum(), "an engine publish that changed nothing redraws nothing: $calls")

        count()
        cards = cards.toMutableList().also { it[1] = card(1, replies = 4) }
        shown.value = state(cards)
        waitForIdle()
        val cardsDrawn = (calls["UpdateCard"] ?: 0) + (calls["PostCard"] ?: 0)
        assertEquals(1, cardsDrawn, "only the card with the new reply: $calls")
        assertEquals(1, calls["DesktopColumns"], "one pass, in the frame the state arrived")
        // That card's replies show under it, so they redraw with it; the rest is the header and
        // sidebar counting cards again. Measured at 412, then 420 once the header gained the
        // work-state filter row and status chip (#18): new things on screen, not extra redraws.
        // 443 once the sidebar gained its "New project" row (channels grouped into projects).
        // 460 once the header gained the free plan's "files nearly full" note: it checks the offer
        // and draws nothing, a call or so each time the header redraws.
        println("one reply redrew ${calls.values.sum()} composables")
        assertTrue(calls.values.sum() < 466, "one reply redrew ${calls.values.sum()} composables: ${calls.entries.sortedByDescending { it.value }}")
    }

    @Test
    fun markdownIsParsedOncePerText() {
        val text = "A *unique* body ${System.nanoTime()}"
        val first = MarkdownCache.get(text)
        MarkdownCache.warm(listOf(text))
        assertSame(first, MarkdownCache.get(text))
    }
}
