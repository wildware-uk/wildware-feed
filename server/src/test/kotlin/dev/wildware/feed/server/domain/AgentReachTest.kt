package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What reaches an agent never crosses from one channel into another it does not follow: no follows
 * means no channel, a reply in a thread follows only that thread, and an unsubscribe stays put.
 * Runs on SQLite, and on Postgres when `PG_TEST_URL` is set.
 */
class AgentReachTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val work = AgentWork(db, bus) { now }
    private val subs = Subscriptions(db) { now }

    @AfterTest fun close() = db.close()

    private fun agent(id: String, name: String) =
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES (?, ?, ?, 1)", id, name, "hash-$id")

    private fun say(author: String, body: String, project: String? = null, updateId: String? = null, replyTo: String? = null) =
        feed.messages.post(Messages.Post(author = author, body = body, project = project, updateId = updateId, replyTo = replyTo))

    /** Everything that reaches the agent now, through each way it can read: the stream's lines and get_messages. */
    private fun heard(agentId: String): List<String> {
        val streamed = work.unread(agentId, 100).map { it.body }
        val read = feed.messages.read(agentId, null, null, null).messages.map { it.body }
        assertEquals(streamed, read, "the stream and get_messages agree")
        return read
    }

    @Test
    fun `work and home never mix, either way, even when each agent replies in the other's threads`() {
        agent("W", "worker")
        agent("H", "homer")
        feed.projects.create("Work", null, null)
        feed.projects.create("Home", null, null)
        val workCard = feed.updates.post(Updates.Post(project = "work", agentId = "W", body = "work card"))
        val homeCard = feed.updates.post(Updates.Post(project = "home", agentId = "H", body = "home card"))
        assertEquals(listOf(feed.projects.resolve("work").id), subs.followedProjects("W"))
        assertEquals(listOf(feed.projects.resolve("home").id), subs.followedProjects("H"))

        val homePost = say("human", "owner's home post", project = "home")
        val workPost = say("human", "owner's work post", project = "work")
        assertEquals(listOf("owner's work post"), heard("W"))
        assertEquals(listOf("owner's home post"), heard("H"))

        // Each answers in a thread of the other's channel.
        say("agent:W", "worker replies in home", replyTo = homePost.id)
        say("agent:H", "homer comments on the work card", updateId = workCard.id)
        assertEquals(listOf(feed.projects.resolve("work").id), subs.followedProjects("W"), "a reply does not follow the channel")
        assertEquals(listOf(feed.projects.resolve("home").id), subs.followedProjects("H"))
        heard("W"); heard("H")

        say("human", "more work", project = "work")
        say("human", "on the work card", updateId = workCard.id)
        say("human", "more home", project = "home")
        say("human", "on the home card", updateId = homeCard.id)
        say("human", "in the work thread", replyTo = workPost.id)
        say("human", "in the home thread", replyTo = homePost.id)

        assertEquals(listOf("more work", "on the work card", "in the work thread", "in the home thread"), heard("W"),
            "worker hears work, and only the one home thread it replied in")
        assertEquals(listOf("on the work card", "more home", "on the home card", "in the home thread"), heard("H"),
            "homer hears home, and only the work card it commented on")
    }

    @Test
    fun `a fresh agent hears only what @mentions it, and its counts say so`() {
        agent("A", "alpha")
        agent("N", "newbie")
        feed.projects.create("P", null, null)
        feed.updates.post(Updates.Post(project = "p", agentId = "A", body = "alpha's card"))
        val post = say("human", "a post", project = "p")
        say("human", "a reply", replyTo = post.id)
        say("agent:A", "@newbie please look", project = "p")
        assertEquals(emptyList(), subs.followedProjects("N"))
        assertEquals(1, work.countWork("N").unreadMessages)
        assertEquals(listOf("@newbie please look"), heard("N"))
        assertEquals(emptyList(), work.projectsForAgent("N"))
        assertEquals(0, work.countWork("N").openTasks)
    }

    @Test
    fun `an unsubscribe never comes back as chosen without subscribe or the owner`() {
        agent("B", "beta")
        feed.projects.create("P", null, null)
        val p = feed.projects.resolve("p").id
        val me = Subscriptions.agent("B")
        subs.followProject(me, p, chosen = true)
        assertTrue(subs.projects(me).single().chosen)
        assertTrue(subs.unfollowProject(me, p))

        // Everything an agent does in a channel, after leaving it.
        val card = feed.updates.post(Updates.Post(project = "p", agentId = "B", body = "a new card"))
        val post = say("agent:B", "a top-level post", project = "p")
        say("agent:B", "a reply", replyTo = post.id)
        say("agent:B", "a comment", updateId = card.id)
        feed.messages.edit(post.id, "agent:B", "a top-level post, edited")
        subs.ownerAddChannel("B", feed.projects.create("Q", null, null).first.id)

        assertEquals(listOf(feed.projects.resolve("q").id), subs.followedProjects("B"), "p stays left")
        assertEquals(listOf(p), subs.leftProjects(me))
        say("human", "in p", project = "p")
        assertEquals(emptyList(), heard("B").filter { it == "in p" })

        subs.followProject(me, p, chosen = true)
        say("human", "in p again", project = "p")
        assertEquals(listOf("in p again"), heard("B"), "subscribe brings it back, from now on")
    }

    @Test
    fun `posting in a channel the agent left through its group does not bring it back`() {
        agent("B", "beta")
        val group = feed.groups.create("Life", null).first
        feed.projects.create("Home", null, null)
        val home = feed.projects.update("home", Projects.Patch(group = Given(group.id)))
        val me = Subscriptions.agent("B")
        subs.followGroup(me, group.id)
        assertEquals(listOf(home.id), subs.followedProjects("B"))
        subs.unfollowGroup(me, group.id)
        say("agent:B", "a post in home", project = "home")
        assertEquals(emptyList(), subs.followedProjects("B"))
    }
}
