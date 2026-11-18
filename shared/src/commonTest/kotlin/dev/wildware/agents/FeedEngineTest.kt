package dev.wildware.agents

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedEngineTest {
    private fun TestScope.started(server: FakeDashboard, readMarks: ReadMarks = ReadMarks()): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, readMarks, clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    private fun payload(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }

    @Test
    fun `the first read fills the feed and the stream opens with no cursor`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5), FakeDashboard.update("u2", 7))
        }
        val dashboard = started(server)

        val feed = dashboard.everything.state.value
        assertTrue(feed.loaded)
        assertEquals(StreamStatus.Live, feed.status)
        assertEquals(listOf("u2", "u1"), feed.cards.map { it.id })
        assertEquals("Builder", (feed.cards.first() as UpdateCard).agentName)
        assertEquals("Mega", (feed.cards.first() as UpdateCard).projectName)
        assertNull(server.connections.single().lastEventId)
        assertEquals(listOf("mega", "other"), dashboard.projects.value.map { it.project.slug })
    }

    @Test
    fun `after a drop the stream resumes from the last event and nothing is missed or doubled`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        val dashboard = started(server)
        val first = server.connections.single()

        server.seq = 11
        server.updates += FakeDashboard.update("u2", 11)
        first.event("update.created", 11, payload("updateId" to "u2", "projectId" to "p1", "agentId" to "a1"))
        runCurrent()
        assertEquals(listOf("u2", "u1"), dashboard.everything.state.value.cards.map { it.id })
        val readsBeforeDrop = server.count("/api/snapshot?")

        first.drop()
        runCurrent()
        assertEquals(StreamStatus.Offline, dashboard.status.value)
        assertEquals(1, server.connections.size, "waits before reconnecting")

        advanceTimeBy(2_001)
        runCurrent()
        val second = server.connections[1]
        assertEquals("11", second.lastEventId)
        assertEquals("11", second.query)
        assertEquals(StreamStatus.Live, dashboard.status.value)

        // The server replays the tail: the frame already in hand is dropped without a read.
        second.event("update.created", 11, payload("updateId" to "u2", "projectId" to "p1", "agentId" to "a1"))
        runCurrent()
        assertEquals(readsBeforeDrop, server.count("/api/snapshot?"))

        server.seq = 12
        server.updates += FakeDashboard.update("u3", 12)
        second.event("update.created", 12, payload("updateId" to "u3", "projectId" to "p1", "agentId" to "a1"))
        runCurrent()
        assertEquals(listOf("u3", "u2", "u1"), dashboard.everything.state.value.cards.map { it.id })
        assertEquals(readsBeforeDrop + 1, server.count("/api/snapshot?"))
    }

    @Test
    fun `a reconnect never resumes from below what the reads already hold`() = runTest {
        val server = FakeDashboard(testScheduler).apply { seq = 40 }
        val dashboard = started(server)

        // No events arrived, so the stream itself has seen nothing; the reads are good to 40.
        server.connections.single().drop()
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals("40", server.connections[1].lastEventId)
        assertEquals(StreamStatus.Live, dashboard.status.value)
    }

    @Test
    fun `reconnect waits double each time and reset once connected`() = runTest {
        val server = FakeDashboard(testScheduler)
        started(server)
        server.streamStatus = HttpStatusCode.ServiceUnavailable
        server.connections.single().drop()
        runCurrent()

        val attemptTimes = mutableListOf<Long>()
        var seen = server.count("/api/stream")
        repeat(40) {
            advanceTimeBy(1_000)
            runCurrent()
            val now = server.count("/api/stream")
            if (now > seen) attemptTimes += testScheduler.currentTime
            seen = now
        }
        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(4_000L, 8_000L, 16_000L), gaps.take(3))
    }

    @Test
    fun `a revoked key signs out and stops retrying`() = runTest {
        val server = FakeDashboard(testScheduler).apply { streamStatus = HttpStatusCode.Unauthorized }
        val dashboard = started(server)

        assertEquals(StreamStatus.SignedOut, dashboard.status.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, server.count("/api/stream"))
    }

    @Test
    fun `a line that goes quiet past the heartbeat is replaced`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        val first = server.connections.single()

        advanceTimeBy(30_000)
        first.heartbeat()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, server.connections.size, "a heartbeat keeps it alive")

        advanceTimeBy(46_000)
        runCurrent()
        assertTrue(server.connections.size >= 2)
        assertEquals(StreamStatus.Live, dashboard.status.value)
    }

    @Test
    fun `resync after a server restart adopts the lower seq and reloads`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            seq = 500
            updates = listOf(FakeDashboard.update("u1", 400))
        }
        val dashboard = started(server)

        server.seq = 3
        server.updates = listOf(FakeDashboard.update("u9", 2))
        server.connections.single().resync(3)
        runCurrent()

        assertEquals(3, dashboard.stream.cursor)
        assertEquals(listOf("u9"), dashboard.everything.state.value.cards.map { it.id })

        // New events from the restarted server count again rather than looking like replays.
        server.seq = 4
        server.updates += FakeDashboard.update("u10", 4)
        server.connections.single().event("update.created", 4, payload("updateId" to "u10", "projectId" to "p1", "agentId" to "a1"))
        runCurrent()
        assertEquals(listOf("u10", "u9"), dashboard.everything.state.value.cards.map { it.id })
    }

    @Test
    fun `a deleted update leaves at once without a read`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5), FakeDashboard.update("u2", 6)) }
        val dashboard = started(server)
        val reads = server.count("/api/snapshot?")

        server.connections.single().event("update.deleted", 11, payload("updateId" to "u1", "projectId" to "p1"))
        runCurrent()

        assertEquals(listOf("u2"), dashboard.everything.state.value.cards.map { it.id })
        assertEquals(reads, server.count("/api/snapshot?"))
    }

    @Test
    fun `a deleted post leaves after the threads are read again`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(FakeDashboard.message("m1", 3, "human", "Look at this", 3_000))
        }
        val dashboard = started(server)
        assertIs<PostCard>(dashboard.everything.state.value.cards.single())

        server.seq = 11
        server.messages = emptyList()
        server.connections.single().event("message.deleted", 11, payload("messageId" to "m1", "projectId" to "p1"))
        runCurrent()

        assertTrue(dashboard.everything.state.value.cards.isEmpty())
    }

    @Test
    fun `an answered question leaves the waiting list`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            requests = listOf(FakeDashboard.request("r1", 1), FakeDashboard.request("r2", 2))
        }
        val dashboard = started(server)
        assertEquals(listOf("r1", "r2"), dashboard.waiting.value.map { it.id })
        assertEquals(listOf("r1", "r2"), dashboard.everything.state.value.waiting.map { it.id })
        assertEquals(2, dashboard.projects.value.first { it.project.slug == "mega" }.waiting)

        server.seq = 11
        server.requests = listOf(FakeDashboard.request("r2", 2))
        server.connections.single().event("request.answered", 11, buildJsonObject {
            put("requestId", "r1"); put("agentId", "a1"); put("state", "answered"); put("settledAt", "2026-11-18T10:00:00Z")
        })
        runCurrent()

        assertEquals(listOf("r2"), dashboard.waiting.value.map { it.id })
        assertEquals(1, dashboard.projects.value.first { it.project.slug == "mega" }.waiting)
    }

    @Test
    fun `a question asked in a thread sits under its line, not at the top`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5))
            messages = listOf(FakeDashboard.message("m1", 6, "agent:a1", "Should I?", 6_000, updateId = "u1"))
            requests = listOf(FakeDashboard.request("r1", 1, messageId = "m1"))
        }
        val dashboard = started(server)

        val state = dashboard.everything.state.value
        assertTrue(state.waiting.isEmpty())
        assertEquals("r1", state.cards.single().replies.single().questions.single().id)
        assertEquals(listOf("r1"), dashboard.waiting.value.map { it.id })
    }

    @Test
    fun `pinned cards go first and a reply moves a card up`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(
                FakeDashboard.update("old", 1, createdAt = 1_000),
                FakeDashboard.update("mid", 2, createdAt = 2_000),
                FakeDashboard.update("pin", 3, createdAt = 500, pinned = true),
            )
            messages = listOf(FakeDashboard.message("post", 4, "human", "A post", 1_500))
        }
        val dashboard = started(server)
        assertEquals(listOf("pin"), dashboard.everything.state.value.pinned.map { it.id })
        assertEquals(listOf("mid", "post", "old"), dashboard.everything.state.value.cards.map { it.id })

        server.seq = 11
        server.messages += FakeDashboard.message("reply", 11, "agent:a2", "On it", 9_000, updateId = "old")
        server.connections.single().event("message.created", 11, payload("messageId" to "reply", "author" to "agent:a2"))
        runCurrent()

        val cards = dashboard.everything.state.value.cards
        assertEquals(listOf("old", "mid", "post"), cards.map { it.id })
        assertEquals(9_000, cards.first().activityAt)
        assertEquals("Scout", cards.first().replies.single().authorName)
    }

    @Test
    fun `a burst of events is one read, and events at or below the read are ignored`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        val reads = server.count("/api/snapshot?")
        val connection = server.connections.single()

        connection.event("update.created", 9, payload("updateId" to "x", "projectId" to "p1"))
        connection.event("update.created", 10, payload("updateId" to "y", "projectId" to "p1"))
        runCurrent()
        assertEquals(reads, server.count("/api/snapshot?"), "already in the read stamped 10")

        server.seq = 13
        connection.event("update.created", 11, payload("updateId" to "a", "projectId" to "p1"))
        connection.event("update.updated", 12, payload("updateId" to "a", "projectId" to "p1"))
        connection.event("media.ready", 13, payload("mediaId" to "md"))
        runCurrent()
        assertEquals(reads + 1, server.count("/api/snapshot?"))
        assertEquals(13, dashboard.stream.cursor)
    }

    @Test
    fun `a project feed ignores other projects' events`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5, projectId = "p1"), FakeDashboard.update("u2", 6, projectId = "p2"))
            requests = listOf(FakeDashboard.request("r1", 1, projectId = "p1"), FakeDashboard.request("r2", 2, projectId = "p2"))
        }
        val dashboard = started(server)
        val mega = dashboard.feed("mega")
        runCurrent()
        assertEquals(listOf("u1"), mega.state.value.cards.map { it.id })
        assertEquals(listOf("r1"), mega.state.value.waiting.map { it.id })
        val scopedReads = server.count("/api/snapshot?limit=50&project=mega")

        server.seq = 11
        server.updates += FakeDashboard.update("u3", 11, projectId = "p2")
        server.connections.single().event("update.created", 11, payload("updateId" to "u3", "projectId" to "p2"))
        runCurrent()

        assertEquals(scopedReads, server.count("/api/snapshot?limit=50&project=mega"))
        assertEquals(listOf("u3", "u2", "u1"), dashboard.everything.state.value.cards.map { it.id })
    }

    @Test
    fun `while scrolled down new updates wait behind a count`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        val dashboard = started(server)
        val feed = dashboard.everything
        feed.hold(true)
        runCurrent()

        server.seq = 11
        server.updates += FakeDashboard.update("u2", 11)
        server.connections.single().event("update.created", 11, payload("updateId" to "u2", "projectId" to "p1"))
        runCurrent()
        assertEquals(listOf("u1"), feed.state.value.cards.map { it.id })
        assertEquals(1, feed.state.value.newCount)

        feed.flush()
        runCurrent()
        assertEquals(listOf("u2", "u1"), feed.state.value.cards.map { it.id })
        assertEquals(0, feed.state.value.newCount)
        assertTrue((feed.state.value.cards.first() as UpdateCard).isNew)
    }

    @Test
    fun `agents' replies count as unread until the card is read, the owner's never do`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5))
            messages = listOf(FakeDashboard.message("m1", 6, "agent:a1", "Old news", 6_000, updateId = "u1"))
        }
        val dashboard = started(server)
        assertEquals(0, dashboard.everything.state.value.cards.single().unread, "a card first seen starts level")

        server.seq = 12
        server.messages += listOf(
            FakeDashboard.message("m2", 11, "agent:a1", "Done", 11_000, updateId = "u1"),
            FakeDashboard.message("m3", 12, "human", "Thanks", 12_000, updateId = "u1"),
        )
        server.connections.single().event("message.created", 12, payload("messageId" to "m3"))
        runCurrent()
        assertEquals(1, dashboard.everything.state.value.cards.single().unread)
        assertEquals(1, dashboard.projects.value.first { it.project.slug == "mega" }.unread)

        dashboard.everything.markRead("u1")
        runCurrent()
        assertEquals(0, dashboard.everything.state.value.cards.single().unread)
    }

    @Test
    fun `an emoji on an update re-reads the update and leaves the threads alone`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        val dashboard = started(server)
        val snapshots = server.count("/api/snapshot?")
        val threads = server.count("/api/messages")

        server.seq = 11
        server.updates = listOf(FakeDashboard.update("u1", 5).copy(reactions = listOf(ReactionView("x", 11, CardRef("update", "u1"), null, "human", "👍"))))
        server.connections.single().event("reaction.updated", 11, buildJsonObject {
            putJsonObject("card") { put("kind", "update"); put("id", "u1") }
            put("actor", "human"); put("emoji", "👍"); put("on", true)
        })
        runCurrent()

        assertEquals(snapshots + 1, server.count("/api/snapshot?"))
        assertEquals(threads, server.count("/api/messages"))
        assertEquals("👍", dashboard.everything.state.value.cards.single().reactions.single().emoji)
    }

    @Test
    fun `presence drops an agent whose heartbeat is older than the window`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            agents = listOf(LiveAgentView("a1", "Builder", lastHeartbeatAt = 0))
        }
        val dashboard = started(server)
        assertEquals(listOf("Builder"), dashboard.online.value.map { it.name })

        advanceTimeBy(95_000)
        runCurrent()
        assertTrue(dashboard.online.value.isEmpty())
    }

    @Test
    fun `reviving re-reads everything and can replace the connection`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        val reads = server.count("/api/snapshot?")

        dashboard.revive(reconnect = true)
        runCurrent()

        assertEquals(2, server.connections.size)
        assertEquals(reads + 1, server.count("/api/snapshot?"))
        assertFalse(dashboard.status.value == StreamStatus.Offline)
    }

    private fun FakeDashboard.wholeThreadReads() = calls.count { it == "/api/messages" }

    @Test
    fun `a reply whose event names its update re-reads only that thread`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5), FakeDashboard.update("u2", 6))
            messages = listOf(
                FakeDashboard.message("m1", 7, "agent:a1", "On u1", 7_000, updateId = "u1"),
                FakeDashboard.message("m2", 8, "agent:a2", "On u2", 8_000, updateId = "u2"),
            )
        }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages += FakeDashboard.message("m3", 11, "agent:a1", "More on u1", 11_000, updateId = "u1")
        server.connections.single().event("message.created", 11, buildJsonObject {
            put("messageId", "m3"); put("projectId", "p1"); put("author", "agent:a1")
            put("updateId", "u1"); put("replyTo", null as String?); put("requestId", null as String?); put("taskId", null as String?)
        })
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?update=u1"))
        val cards = dashboard.everything.state.value.cards.associateBy { it.id }
        assertEquals(listOf("m1", "m3"), cards.getValue("u1").replies.map { it.message.id })
        assertEquals(listOf("m2"), cards.getValue("u2").replies.map { it.message.id }, "other threads are left as they were")
    }

    @Test
    fun `a new message whose event doesn't name its thread re-reads everything`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages += FakeDashboard.message("m1", 11, "agent:a1", "Hi", 11_000, updateId = "u1")
        server.connections.single().event("message.created", 11, payload("messageId" to "m1", "projectId" to "p1", "author" to "agent:a1"))
        runCurrent()

        assertEquals(whole + 1, server.wholeThreadReads())
        assertEquals("m1", dashboard.everything.state.value.cards.single().replies.single().message.id)
    }

    @Test
    fun `an emoji, an edit or a delivery on a line already held re-reads only its thread`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5))
            messages = listOf(
                FakeDashboard.message("m1", 7, "agent:a1", "On u1", 7_000, updateId = "u1"),
                FakeDashboard.message("q1", 8, "human", "Why?", 8_000, requestId = "r1"),
            )
            requests = listOf(FakeDashboard.request("r1", 1))
        }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()
        val connection = server.connections.single()

        server.reactions = listOf(ReactionView("x1", 11, CardRef("message", "m1"), "m1", "human", "👍"))
        connection.event("reaction.updated", 11, buildJsonObject {
            putJsonObject("card") { put("kind", "message"); put("id", "m1") }
            put("messageId", "m1"); put("projectId", "p1"); put("actor", "human"); put("emoji", "👍"); put("on", true)
        })
        connection.event("message.updated", 12, payload("messageId" to "q1", "projectId" to "p1", "author" to "human"))
        connection.event("message.delivered", 13, payload("messageId" to "m1", "agentId" to "a2", "projectId" to "p1"))
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?update=u1"), "the emoji and the delivery share one read")
        assertEquals(1, server.count("/api/messages?request=r1"))
        assertEquals("👍", dashboard.everything.state.value.cards.single().replies.single().reactions.single().emoji)
    }

    @Test
    fun `a project feed ignores other projects' talk`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        dashboard.feed("mega")
        runCurrent()
        val scoped = server.count("/api/messages?project=mega")

        server.connections.single().event("message.created", 11, payload("messageId" to "m9", "projectId" to "p2", "author" to "agent:a1"))
        runCurrent()

        assertEquals(scoped, server.count("/api/messages?project=mega"))
    }

    /** A message event as the server now sends it: the thread named, null where it has none. */
    private fun messageEvent(messageId: String, updateId: String? = null, replyTo: String? = null, requestId: String? = null, taskId: String? = null) =
        buildJsonObject {
            put("messageId", messageId); put("projectId", "p1"); put("author", "agent:a1")
            put("updateId", updateId); put("replyTo", replyTo); put("requestId", requestId); put("taskId", taskId)
        }

    @Test
    fun `a reply under a post re-reads only that post's thread`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(
                FakeDashboard.message("post1", 3, "human", "Have a look", 3_000),
                FakeDashboard.message("post2", 4, "human", "And this", 4_000),
            )
        }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages += FakeDashboard.message("r1", 11, "agent:a1", "Looked", 11_000, replyTo = "post1")
        server.connections.single().event("message.created", 11, messageEvent("r1", replyTo = "post1"))
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?replyTo=post1"))
        val cards = dashboard.everything.state.value.cards
        assertEquals(listOf("post1", "post2"), cards.map { it.id }, "the reply moves its post up")
        assertEquals(listOf("r1"), cards.first().replies.map { it.message.id })
        assertTrue(cards[1].replies.isEmpty())
    }

    @Test
    fun `a new post reads itself in`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages += FakeDashboard.message("post1", 11, "agent:a1", "A note", 11_000)
        server.connections.single().event("message.created", 11, messageEvent("post1"))
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?replyTo=post1"))
        assertIs<PostCard>(dashboard.everything.state.value.cards.single())
    }

    @Test
    fun `a deleted post takes its replies with it without reading everything`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(
                FakeDashboard.message("post1", 3, "human", "Have a look", 3_000),
                FakeDashboard.message("r1", 4, "agent:a1", "Looked", 4_000, replyTo = "post1"),
                FakeDashboard.message("post2", 5, "human", "Keep me", 5_000),
            )
        }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages = server.messages.filter { it.id == "post2" }
        server.connections.single().event("message.deleted", 11, messageEvent("post1"))
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(listOf("post2"), dashboard.everything.state.value.cards.map { it.id })
    }

    @Test
    fun `a task's line re-reads that task's thread`() = runTest {
        val server = FakeDashboard(testScheduler)
        started(server)
        val whole = server.wholeThreadReads()

        server.messages += FakeDashboard.message("t-line", 11, "agent:a1", "Started", 11_000).copy(taskId = "t1")
        server.connections.single().event("message.created", 11, messageEvent("t-line", taskId = "t1"))
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?task=t1"))
    }

    @Test
    fun `a pin on a post this feed holds re-reads that post, one it doesn't hold reads everything`() = runTest {
        val server = FakeDashboard(testScheduler).apply { messages = listOf(FakeDashboard.message("post1", 3, "human", "Pin me", 3_000)) }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()

        server.messages = listOf(server.messages.single().copy(pinned = true))
        // A pin carries only which message, as the server's card routes publish it.
        server.connections.single().event("message.updated", 11, payload("messageId" to "post1", "projectId" to "p1", "author" to ""))
        runCurrent()
        assertEquals(whole, server.wholeThreadReads())
        assertEquals(listOf("post1"), dashboard.everything.state.value.pinned.map { it.id })

        server.connections.single().event("message.updated", 12, payload("messageId" to "elsewhere", "projectId" to "p1", "author" to ""))
        runCurrent()
        assertEquals(whole + 1, server.wholeThreadReads())
    }

    @Test
    fun `a picture becoming ready on a reply re-reads only that reply's thread`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5))
            messages = listOf(FakeDashboard.message("m1", 6, "agent:a1", "Screenshot", 6_000, updateId = "u1"))
        }
        val dashboard = started(server)
        val whole = server.wholeThreadReads()
        val snapshots = server.count("/api/snapshot?")

        server.connections.single().event("media.ready", 11, buildJsonObject {
            put("mediaId", "md1"); put("updateId", null as String?); put("messageId", "m1"); put("requestId", null as String?); put("kind", "image")
        })
        // A picture on a message nobody has loaded yet: its own event will bring it.
        server.connections.single().event("media.ready", 12, buildJsonObject {
            put("mediaId", "md2"); put("updateId", null as String?); put("messageId", "unknown"); put("requestId", null as String?); put("kind", "image")
        })
        runCurrent()

        assertEquals(whole, server.wholeThreadReads())
        assertEquals(1, server.count("/api/messages?update=u1"))
        assertTrue(dashboard.everything.state.value.loaded)
        assertEquals(snapshots, server.count("/api/snapshot?"), "a picture on a message is no reason to re-read the updates")
    }

    @Test
    fun `a picture becoming ready on a question re-reads the questions`() = runTest {
        val server = FakeDashboard(testScheduler).apply { requests = listOf(FakeDashboard.request("r1", 1)) }
        started(server)
        val reads = server.count("/api/snapshot/requests")

        server.connections.single().event("media.ready", 11, buildJsonObject {
            put("mediaId", "md1"); put("updateId", null as String?); put("messageId", null as String?); put("requestId", "r1"); put("kind", "image")
        })
        runCurrent()

        assertEquals(reads + 1, server.count("/api/snapshot/requests"))
    }
}
