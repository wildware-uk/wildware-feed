package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.NewRequest
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.events.AppEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the owner follows decides which alerts are raised: the bell, and phone push before each phone's own preferences. */
class OwnerSubscriptionsTest {
    private val apnsConfig = Config.Apns("KEY123", "TEAM456", "/unused.p8", "dev.example.feed")
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
    )
    private val app = App(config, Database.memory(), clock = { 1_700_000_000_000 })
    private val subs = Subscriptions(app.db, app.clock)
    private val apns = Apns(app, apnsConfig) { ApnsResponse(200, null) }

    @AfterTest
    fun close() = app.close()

    private fun bell() = app.db.query("SELECT body FROM notifications ORDER BY seq") { it.getString(1) }.also {
        app.db.update("DELETE FROM notifications")
    }

    private fun update(body: String, priority: String? = null) =
        app.feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = body, priority = priority))

    private fun agentSays(body: String, updateId: String? = null, replyTo: String? = null) =
        app.feed.messages.post(Messages.Post(author = "agent:A1", body = body, updateId = updateId, replyTo = replyTo, project = if (updateId == null && replyTo == null) "p" else null))

    private fun ownerSays(body: String, updateId: String? = null, replyTo: String? = null) =
        app.feed.messages.post(Messages.Post(author = "human", body = body, updateId = updateId, replyTo = replyTo, project = if (updateId == null && replyTo == null) "p" else null))

    private fun pushed(type: String, key: String, id: String) =
        apns.alertFor(AppEvent(type, 1, "", buildJsonObject { put(key, JsonPrimitive(id)) }))!!.followed

    private fun setUp(): String {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Ada', 'x', 1)")
        app.feed.projects.create("P", null, null)
        return app.feed.projects.resolve("p").id
    }

    @Test
    fun `by default the owner hears everything, and posting follows the thread`() {
        setUp()
        val u = update("new card")
        val c = agentSays("a comment", updateId = u.id)
        assertEquals(listOf("new card", "a comment"), bell())
        assertEquals(true, pushed("update.created", "updateId", u.id))
        assertEquals(true, pushed("message.created", "messageId", c.id))
        assertEquals(null, subs.threadState(FOUNDER_SUB, Subscriptions.Thread("update", u.id)))
        ownerSays("thanks", updateId = u.id)
        assertEquals(true, subs.threadState(FOUNDER_SUB, Subscriptions.Thread("update", u.id)))
    }

    @Test
    fun `a muted project raises nothing but critical alerts and threads the owner follows`() {
        val p = setUp()
        val followed = update("followed card")
        ownerSays("I'm watching this", updateId = followed.id)
        bell()
        subs.setLevel(FOUNDER_SUB, p, Subscriptions.MUTED)
        val u = update("quiet card")
        val c = agentSays("quiet comment", updateId = u.id)
        val f = agentSays("reply where the owner spoke", updateId = followed.id)
        val loud = update("on fire", priority = "critical")
        assertEquals(listOf("reply where the owner spoke", "on fire"), bell())
        assertEquals(false, pushed("update.created", "updateId", u.id))
        assertEquals(false, pushed("message.created", "messageId", c.id))
        assertEquals(true, pushed("message.created", "messageId", f.id))
        assertEquals(true, pushed("update.created", "updateId", loud.id))
    }

    @Test
    fun `replies only hears questions and replies to the owner, not new cards or other comments`() {
        val p = setUp()
        val post = ownerSays("Owner's question in p")
        val u = update("a card")
        bell()
        subs.setLevel(FOUNDER_SUB, p, Subscriptions.REPLIES)
        agentSays("answering the owner", replyTo = post.id)
        agentSays("someone else's thread", updateId = u.id)
        agentSays("@owner have a look", updateId = u.id)
        update("another card")
        val (request, _) = app.requests.create("A1", NewRequest(kind = "confirm", question = "Ship it?", project = "p"))
        assertEquals(listOf("answering the owner", "@owner have a look", "Ship it?"), bell())
        assertEquals(true, pushed("request.created", "requestId", request.id))
    }

    @Test
    fun `unfollowing a thread silences it, following one in a muted project brings it back`() {
        val p = setUp()
        val u = update("card")
        ownerSays("me too", updateId = u.id)
        bell()
        val thread = Subscriptions.Thread("update", u.id)
        subs.setFollow(FOUNDER_SUB, thread, p, following = false)
        agentSays("after unfollowing", updateId = u.id)
        assertEquals(emptyList(), bell())
        ownerSays("back in", updateId = u.id)
        agentSays("posting follows it again", updateId = u.id)
        assertEquals(listOf("posting follows it again"), bell())

        subs.setLevel(FOUNDER_SUB, p, Subscriptions.MUTED)
        val other = update("muted card")
        subs.setFollow(FOUNDER_SUB, Subscriptions.Thread("update", other.id), p, following = true)
        bell()
        agentSays("chosen", updateId = other.id)
        assertEquals(listOf("chosen"), bell())
    }
}

/** The founding owner, as a subscriber (migration 055). */
private const val FOUNDER_SUB = "member:founder"
