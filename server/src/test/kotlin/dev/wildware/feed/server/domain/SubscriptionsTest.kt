package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Migrations
import dev.wildware.feed.server.events.Bus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionsTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val work = AgentWork(db, bus) { now }
    private val subs = Subscriptions(db) { now }

    @AfterTest fun close() = db.close()

    private fun agent(id: String, name: String) =
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES (?, ?, ?, 1)", id, name, "hash-$id")

    private fun say(author: String, body: String, project: String? = null, updateId: String? = null, replyTo: String? = null, request: String? = null) =
        feed.messages.post(Messages.Post(author = author, body = body, project = project, updateId = updateId, replyTo = replyTo, requestId = request))

    private fun unread(agentId: String) = feed.messages.read(agentId, null, null, null).messages.map { it.body }

    /** Alpha works in "p", beta only in "q"; both have read everything so far. */
    private fun twoAgents(): UpdateRow {
        agent("A", "alpha")
        agent("B", "beta")
        feed.projects.create("P", null, null)
        feed.projects.create("Q", null, null)
        feed.updates.post(Updates.Post(project = "q", agentId = "B", body = "beta's own work"))
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A", body = "alpha's work"))
        unread("A"); unread("B")
        return update
    }

    @Test
    fun `an @mention follows the thread, so a later reply with no mention reaches the agent in another project`() {
        val update = twoAgents()
        assertEquals(listOf(feed.projects.resolve("q").id), subs.followedProjects("B"))

        say("agent:A", "@beta can you look at this?", updateId = update.id)
        assertEquals(listOf("A", "B"), subs.followers(Subscriptions.Thread("update", update.id)).map { it.first })

        say("human", "Thanks both, go ahead", updateId = update.id)
        say("human", "Something else in p", project = "p")
        assertEquals(2, work.countWork("B").unreadMessages)
        assertEquals(listOf("@beta can you look at this?", "Thanks both, go ahead"), unread("B"))
        assertEquals(0, work.countWork("B").unreadMessages)
        assertEquals(listOf("Thanks both, go ahead", "Something else in p"), unread("A"))
        assertEquals(listOf(feed.projects.resolve("q").id), subs.followedProjects("B"), "the mention follows the thread, not the project")
    }

    @Test
    fun `a feed post's replies and a request's discussion are threads too`() {
        twoAgents()
        val post = say("human", "@beta, a question for you", project = "p")
        say("human", "and a follow-up", replyTo = post.id)
        assertEquals(listOf("@beta, a question for you", "and a follow-up"), unread("B"))

        val (request, _) = Requests(db, bus, Reads(db), { now }) { 1000L }.create(
            "A", NewRequest(kind = "confirm", question = "Ship it?", project = "p"),
        )
        say("human", "@beta what do you think?", request = request.id)
        say("agent:A", "I'd ship", request = request.id)
        assertEquals(listOf("@beta what do you think?", "I'd ship"), unread("B"))
        assertEquals(listOf("Thread(kind=request, id=${request.id})", "Thread(kind=message, id=${post.id})"),
            subs.threads(Subscriptions.agent("B"), 10).take(2).map { it.thread.toString() })
    }

    @Test
    fun `unsubscribing stops the replies, but an @mention still reaches`() {
        val update = twoAgents()
        say("agent:A", "@beta look", updateId = update.id)
        unread("B")
        val thread = Subscriptions.Thread("update", update.id)
        assertEquals(true, subs.unfollowThread(Subscriptions.agent("B"), thread))
        assertEquals(false, subs.unfollowThread(Subscriptions.agent("B"), thread))
        say("human", "no mention", updateId = update.id)
        say("human", "@beta one more thing", updateId = update.id)
        assertEquals(listOf("@beta one more thing"), unread("B"))
        say("human", "after the mention", updateId = update.id)
        assertEquals(listOf("after the mention"), unread("B"), "being mentioned again follows it again")
    }

    @Test
    fun `a reply follows only its thread, a top-level post follows the channel from now, and following again replays nothing`() {
        twoAgents()
        val p = feed.projects.resolve("p").id
        val q = feed.projects.resolve("q").id
        say("human", "old history in p", project = "p")
        val post = say("human", "Owner's post in p", project = "p")
        assertEquals(emptyList(), unread("B"))
        say("agent:B", "I'll take it", replyTo = post.id)
        assertEquals(listOf(q), subs.followedProjects("B"), "a reply in a thread never follows its channel")
        say("human", "great", replyTo = post.id)
        say("human", "elsewhere in p", project = "p")
        assertEquals(listOf("great"), unread("B"), "only the thread it replied in")

        say("agent:B", "a post of my own in p", project = "p")
        assertEquals(listOf(q, p), subs.followedProjects("B"), "a top-level post follows the channel")
        assertEquals(emptyList(), unread("B"), "from now on: the channel's history never turns unread")
        say("human", "next in p", project = "p")
        assertEquals(listOf("next in p"), unread("B"))

        subs.unfollowProject(Subscriptions.agent("B"), p)
        subs.unfollowThread(Subscriptions.agent("B"), Subscriptions.Thread("message", post.id))
        say("human", "while away", project = "p")
        assertEquals(emptyList(), unread("B"))
        say("agent:B", "posting after unsubscribing", project = "p")
        assertEquals(listOf(q), subs.followedProjects("B"), "posting never undoes an unsubscribe")
        assertEquals(emptyList(), subs.projects(Subscriptions.agent("B")).filter { it.projectId == p })
        subs.followProject(Subscriptions.agent("B"), p, chosen = true)
        assertEquals(emptyList(), unread("B"), "nothing from before")
        say("human", "back again", project = "p")
        assertEquals(listOf("back again"), unread("B"))
    }

    @Test
    fun `an explicit follow hears only what comes next`() {
        val update = twoAgents()
        say("human", "old news", updateId = update.id)
        subs.followThread(Subscriptions.agent("B"), Subscriptions.Thread("update", update.id), feed.projects.resolve("p").id)
        assertEquals(emptyList(), unread("B"))
        say("human", "new news", updateId = update.id)
        assertEquals(listOf("new news"), unread("B"))
    }

    @Test
    fun `@everyone follows the thread for every agent it reached`() {
        val update = twoAgents()
        agent("C", "gamma")
        say("agent:A", "@everyone heads up", updateId = update.id)
        say("human", "details follow", updateId = update.id)
        assertEquals(listOf("@everyone heads up", "details follow"), unread("B"))
        assertEquals(listOf("A", "B", "C"), subs.followers(Subscriptions.Thread("update", update.id)).map { it.first })
    }

    @Test
    fun `a new agent with no follows hears no channel, only what @mentions it`() {
        agent("N", "newbie")
        feed.projects.create("P", null, null)
        assertEquals(emptyList(), subs.followedProjects("N"))
        assertEquals(emptyList(), subs.heardChannels("N"))
        val post = say("human", "hello world", project = "p")
        say("human", "a reply", replyTo = post.id)
        say("human", "@newbie this one is for you", project = "p")
        assertEquals(1, work.countWork("N").unreadMessages)
        assertEquals(0, work.countWork("N").openTasks)
        assertEquals(listOf("@newbie this one is for you"), work.unread("N", 10).map { it.body })
        assertEquals(listOf("@newbie this one is for you"), unread("N"))
        assertEquals(emptyList(), feed.messages.read("N", null, "p", null).messages.map { it.body }, "naming a channel it does not hear reads nothing")

        subs.followProject(Subscriptions.agent("N"), feed.projects.resolve("p").id, chosen = true)
        subs.unfollowProject(Subscriptions.agent("N"), feed.projects.resolve("p").id)
        assertEquals(emptyList(), subs.followedProjects("N"))
        say("human", "unheard", project = "p")
        assertEquals(emptyList(), unread("N"))
    }

}

class SubscriptionSeedTest {
    @Test
    fun `migration 052 follows every project an agent has worked in, and no more`() {
        Database.sqliteMemory(upTo = 51).use { db ->
            db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A', 'alpha', 'h1', 1), ('B', 'beta', 'h2', 1), ('C', 'quiet', 'h3', 1)")
            for (p in listOf("P1", "P2", "P3", "P4")) {
                db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES (?, ?, ?, 1, 1)", p, p.lowercase(), p)
            }
            db.update("INSERT INTO updates (id, project_id, agent_id, body, level, created_at) VALUES ('U1', 'P1', 'A', 'u', 'info', 1)")
            db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M1', 'P2', 'agent:A', 'm', 1)")
            db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M2', 'P3', 'agent:B', 'm', 1)")
            db.update("INSERT INTO messages (id, project_id, author, body, created_at, deleted_at) VALUES ('M3', 'P4', 'agent:B', 'gone', 1, 2)")
            db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M4', 'P4', 'human', 'owner', 1)")

            Migrations.run(db)

            val rows = db.query("SELECT subscriber, project_id, after_seq, unsubscribed_at FROM project_subscriptions ORDER BY subscriber, project_id") {
                listOf(it.getString(1), it.getString(2), it.getLong(3).toString(), it.getString(4))
            }
            assertEquals(listOf(listOf("agent:A", "P1", "0", null), listOf("agent:A", "P2", "0", null), listOf("agent:B", "P3", "0", null)), rows)
            val subs = Subscriptions(db)
            assertEquals(emptyList(), subs.followedProjects("C"), "an agent that did nothing hears no channel")
            assertEquals(listOf("P3"), subs.followedProjects("B"))
        }
    }
}
