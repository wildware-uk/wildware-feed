package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a new agent stream catches up on (spec/04-stream-and-events.md §4.6, backlog across sessions):
 * not what another session of the agent was handed over two minutes ago, whatever its client id;
 * still what one was handed a moment ago, what none ever was, and everything written since the
 * stream opened. Runs on SQLite, and on Postgres when `PG_TEST_URL` is set. The clock is ours.
 */
class AgentBacklogTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val work = AgentWork(db, bus) { now }

    @AfterTest fun close() = db.close()

    private val minute = 60_000L
    private val handover = 2 * minute

    private fun setUp() {
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A', 'alpha', 'hash-A', 1)")
        feed.projects.create("P", null, null)
        // Its card in p follows the channel.
        feed.updates.post(Updates.Post(project = "p", agentId = "A", body = "alpha's card"))
    }

    private fun say(body: String) = feed.messages.post(Messages.Post(author = "human", body = body, project = "p")).id

    /** What a stream opening now would catch up on. */
    private fun backlog(openedAt: Long = now) = work.unread("A", 5, null, AgentWork.Backlog(openedAt, now - handover)).map { it.body }

    @Test
    fun `a restarted session with a new client id does not hear what the old one was handed`() {
        setUp()
        val old = say("old news")
        work.markDelivered("A", "bridge-old", listOf(old))
        now += 3 * minute
        assertEquals(emptyList(), backlog())
        assertEquals(listOf("old news"), work.unread("A", 5).map { it.body }, "still unread: get_messages and counts are unchanged")
        assertEquals(1, work.countWork("A").unreadMessages)
    }

    @Test
    fun `a line handed to a dropped session a moment ago comes to the one reconnecting`() {
        setUp()
        val m = say("just now")
        work.markDelivered("A", "bridge-dropped", listOf(m))
        now += 30_000
        assertEquals(listOf("just now"), backlog())
    }

    @Test
    fun `a line no session was ever handed comes, even behind more than a frame of old ones`() {
        setUp()
        say("never handed")
        val old = (1..6).map { say("old $it") }
        work.markDelivered("A", null, old.take(3))
        work.markDelivered("A", "bridge-x", old.drop(3))
        now += 10 * minute
        assertEquals(listOf("never handed"), backlog())
    }

    @Test
    fun `a line handed over more than a day ago is never replayed`() {
        setUp()
        val m = say("yesterday")
        work.markDelivered("A", "bridge-yesterday", listOf(m))
        now += 25 * 60 * minute
        assertEquals(emptyList(), backlog())
    }

    @Test
    fun `a line written since the stream opened is live, whoever else had it`() {
        setUp()
        val openedAt = now
        val m = say("live")
        work.markDelivered("A", "bridge-other", listOf(m))
        now += 5 * minute
        assertEquals(listOf("live"), backlog(openedAt), "only the stream's own record of what it sent stops a live line")
    }
}
