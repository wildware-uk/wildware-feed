package dev.wildware.agents

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner's own words show the moment they are sent, and stay, whether or not the live stream
 * is working. The stream is the slow path: a phone just back from the background, or a PC just
 * woken, holds a dead connection for up to the heartbeat window.
 */
class InstantReplyTest {
    private val json = Json { encodeDefaults = true }

    private class Started(val dashboard: Dashboard, val actions: OwnerActions)

    private fun TestScope.started(server: FakeDashboard, clock: () -> Long = { testScheduler.currentTime }): Started {
        val client = server.client()
        val outbox = OutboxQueue(client, MemoryOutboxStorage(), backgroundScope, clock = clock)
        val dashboard = Dashboard(client, backgroundScope, ReadMarks(), outbox, clock = clock)
        outbox.start()
        dashboard.start()
        runCurrent()
        return Started(dashboard, OwnerActions(outbox, clock = clock))
    }

    private fun post(id: String, at: Long = 1_000) = FakeDashboard.message(id, 1, "agent:a1", "A post", at)

    private fun Dashboard.repliesOn(cardId: String): List<ThreadLine> =
        everything.state.value.allCards.first { it.id == cardId }.replies

    @Test
    fun `a reply shows on its card at once, marked as sending, before the dashboard has it`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(post("p1"))
            // No signal: every write fails and is kept for later.
            onWrite = { _, _ -> respond("", HttpStatusCode.ServiceUnavailable) }
        }
        val (dashboard, actions) = started(server).let { it.dashboard to it.actions }

        actions.reply(CardRef(CardKinds.Message, "p1"), "On my way")
        runCurrent()

        val replies = dashboard.repliesOn("p1")
        assertEquals(listOf("On my way"), replies.map { it.message.body })
        assertTrue(replies.single().sending)
        assertTrue(replies.single().message.isOwner)
    }

    @Test
    fun `a sent reply stays on its card without waiting for the live stream`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(post("p1"))
            onWrite = { request, body ->
                if (request.url.encodedPath != "/api/messages") null
                else {
                    val sent = FakeDashboard.message("m9", 11, "human", "On my way", 5_000, replyTo = "p1")
                    respond("""{"message":${json.encodeToString(MessageView.serializer(), sent)}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val (dashboard, actions) = started(server).let { it.dashboard to it.actions }
        val threadReads = server.count("/api/messages?")

        actions.reply(CardRef(CardKinds.Message, "p1"), "On my way")
        runCurrent()

        // The stream said nothing (it may be dead), and nothing was read again: the reply came back with the send.
        val replies = dashboard.repliesOn("p1")
        assertEquals(listOf("m9"), replies.map { it.message.id })
        assertFalse(replies.single().sending)
        assertEquals(threadReads, server.count("/api/messages?"))
    }

    @Test
    fun `the stream's own copy of a sent reply does not double it`() = runTest {
        val server = FakeDashboard(testScheduler).apply { messages = listOf(post("p1")) }
        val (dashboard, actions) = started(server).let { it.dashboard to it.actions }

        actions.reply(CardRef(CardKinds.Message, "p1"), "sent")
        runCurrent()
        // The fake answers every POST with m-new; the server now holds it too.
        server.messages += FakeDashboard.message("m-new", 11, "human", "sent", 1, replyTo = "p1")
        server.seq = 11
        server.connections.single().event(
            "message.created", 11,
            buildJsonObject { put("messageId", "m-new"); put("projectId", "p1"); put("author", "human"); put("replyTo", "p1") },
        )
        runCurrent()

        assertEquals(listOf("m-new"), dashboard.repliesOn("p1").map { it.message.id })
    }

    @Test
    fun `a post shows in the project's feed at once`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { _, _ -> respond("", HttpStatusCode.ServiceUnavailable) }
        }
        val (dashboard, actions) = started(server).let { it.dashboard to it.actions }

        actions.post(ProjectRef("mega", "Mega"), "Look at this")
        runCurrent()

        val card = dashboard.everything.state.value.cards.single() as PostCard
        assertEquals("Look at this", card.post.message.body)
        assertEquals("p1", card.projectId)
        assertTrue(card.post.sending)
    }

    @Test
    fun `waking from sleep replaces a connection that only looks open`() = runTest {
        var offset = 0L
        val server = FakeDashboard(testScheduler)
        val (dashboard, _) = started(server, clock = { testScheduler.currentTime + offset }).let { it.dashboard to it.actions }
        assertEquals(1, server.connections.size)

        // The machine slept for ten minutes: the wall clock jumped, nothing on the line said so.
        offset += 10 * 60_000
        advanceTimeBy(Dashboard.TickMillis + 1)
        runCurrent()

        assertEquals(2, server.connections.size, "a fresh connection, not the one that died in its sleep")
        assertEquals(StreamStatus.Live, dashboard.status.value)
    }
}
