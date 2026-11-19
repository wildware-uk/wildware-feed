package dev.wildware.agents

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerActionsTest {
    private var ids = 0

    private fun TestScope.setUp(server: FakeDashboard, storage: OutboxStorage = MemoryOutboxStorage()): Pair<OutboxQueue, OwnerActions> {
        val queue = OutboxQueue(server.client(), storage, backgroundScope, clock = { testScheduler.currentTime })
        queue.start()
        return queue to OwnerActions(queue, clock = { testScheduler.currentTime }, newId = { "id${++ids}" })
    }

    @Test
    fun `a post with a photo uploads the photo then posts with its id`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.post(ProjectRef("mega", "Mega"), "Look", listOf(Attachment("shot.png", "image/png", byteArrayOf(1, 2, 3))))
        runCurrent()

        assertEquals(listOf("POST /api/media", "POST /api/messages"), server.writes.map { it.first })
        assertEquals("""{"project":"mega","body":"Look","mediaIds":["md1"]}""", server.writes[1].second)
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a post goes up then wears its tags and a tag that fails does not post it again`() = runTest {
        val server = FakeDashboard(testScheduler)
        var tagged = 0
        server.onWrite = { request, _ ->
            if (request.url.encodedPath.endsWith("/tags") && ++tagged == 1) respond("""{"error":"internal"}""", HttpStatusCode.InternalServerError) else null
        }
        val (queue, actions) = setUp(server)

        actions.post(ProjectRef("mega", "Mega"), "Ship it", tags = listOf(OutboxTag("t1"), OutboxTag("needs review", create = true)))
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/messages" to """{"project":"mega","body":"Ship it"}""",
                "POST /api/cards/message/m-new/tags" to """{"tag":"t1","on":true}""",
                "POST /api/cards/message/m-new/tags" to """{"tag":"needs review","on":true,"create":true}""",
            ),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty(), "sent once, even though the first tag failed")
    }

    @Test
    fun `a reply answering a line says which line on the right thread`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (_, actions) = setUp(server)

        actions.reply(CardRef(CardKinds.Update, "u1"), "Yes, go", answers = "m7")
        actions.reply(CardRef(CardKinds.Message, "post1"), "Under a post")
        actions.reply(CardRef(CardKinds.Request, "r1"), "Why?")
        runCurrent()

        assertEquals(
            listOf(
                """{"update":"u1","answers":"m7","body":"Yes, go"}""",
                """{"replyTo":"post1","body":"Under a post"}""",
                """{"request":"r1","body":"Why?"}""",
            ),
            server.writes.map { it.second },
        )
    }

    @Test
    fun `answers go as typed JSON values`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (_, actions) = setUp(server)
        val request = FakeDashboard.request("r1", 1)

        actions.answer(request, "Builder", JsonPrimitive(true))
        actions.answer(request.copy(id = "r2"), "Builder", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        actions.answer(request.copy(id = "r3"), "Builder", buildJsonObject { put("action", "Approve"); put("text", "ok") })
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/requests/r1/answer" to """{"value":true}""",
                "POST /api/requests/r2/answer" to """{"value":["a","b"]}""",
                "POST /api/requests/r3/answer" to """{"value":{"action":"Approve","text":"ok"}}""",
            ),
            server.writes,
        )
    }

    @Test
    fun `a reaction is sent as on or off and never as a toggle`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (_, actions) = setUp(server)
        val mine = listOf(ReactionView("x", 1, CardRef("update", "u1"), null, HumanAuthor, "👍"))

        actions.toggleReaction(CardRef(CardKinds.Update, "u1"), "👍", current = mine)
        actions.toggleReaction(CardRef(CardKinds.Message, "m1"), "👀", current = emptyList())
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/cards/update/u1/reactions" to """{"emoji":"👍","on":false}""",
                "POST /api/cards/message/m1/reactions" to """{"emoji":"👀","on":true}""",
            ),
            server.writes,
        )
    }

    @Test
    fun `tagging says on or off and can make the word on the way`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.tag(CardRef(CardKinds.Update, "u1"), "t1", on = true)
        actions.tag(CardRef(CardKinds.Message, "m1"), "t1", on = false)
        actions.tag(CardRef(CardKinds.Update, "u1"), "needs review", on = true, create = true)
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/cards/update/u1/tags" to """{"tag":"t1","on":true}""",
                "POST /api/cards/message/m1/tags" to """{"tag":"t1","on":false}""",
                "POST /api/cards/update/u1/tags" to """{"tag":"needs review","on":true,"create":true}""",
            ),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a work state is sent as the state and not work as null`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.setStatus(CardRef(CardKinds.Update, "u1"), "in_progress")
        actions.setStatus(CardRef(CardKinds.Message, "m1"), null)
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/cards/update/u1/status" to """{"status":"in_progress"}""",
                "POST /api/cards/message/m1/status" to """{"status":null}""",
            ),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a work state waits for signal and says what it is`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { _, _ -> respond("""{"error":"unavailable"}""", HttpStatusCode.ServiceUnavailable) }
        }
        val (queue, actions) = setUp(server)

        actions.setStatus(CardRef(CardKinds.Update, "u1"), "done")
        runCurrent()

        assertEquals(listOf("Mark Done"), queue.items.value.map { it.label })
    }

    @Test
    fun `a field is set or changed or taken off by name`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.setField(CardRef(CardKinds.Update, "u1"), "branch", "fix/unread")
        actions.setField(CardRef(CardKinds.Message, "m1"), "branch", null)
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/cards/update/u1/fields" to """{"name":"branch","value":"fix/unread"}""",
                "POST /api/cards/message/m1/fields" to """{"name":"branch","value":null}""",
            ),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `pinning says pinned or unpinned and never toggles`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.pin(CardRef(CardKinds.Update, "u1"), pinned = true)
        actions.pin(CardRef(CardKinds.Message, "m1"), pinned = false)
        runCurrent()

        assertEquals(
            listOf(
                "POST /api/cards/update/u1/pin" to """{"pinned":true}""",
                "POST /api/cards/message/m1/pin" to """{"pinned":false}""",
            ),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty())
    }


    @Test
    fun `deleting or dismissing what is already gone counts as done`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { _, _ -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound) }
        }
        val (queue, actions) = setUp(server)

        actions.delete(CardRef(CardKinds.Message, "m1"))
        actions.delete(CardRef(CardKinds.Update, "u1"))
        actions.dismiss(FakeDashboard.request("r1", 1), "Builder")
        runCurrent()

        assertEquals(listOf("DELETE /api/messages/m1", "DELETE /api/updates/u1", "DELETE /api/requests/r1"), server.writes.map { it.first })
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `with no signal sends wait in order and go when it returns`() = runTest {
        var online = false
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { _, _ -> if (!online) throw IllegalStateException("No route to host") else null }
        }
        val storage = MemoryOutboxStorage()
        val (queue, actions) = setUp(server, storage)

        actions.reply(CardRef(CardKinds.Update, "u1"), "first")
        actions.reply(CardRef(CardKinds.Update, "u1"), "second")
        runCurrent()
        assertEquals(2, queue.items.value.size)
        assertEquals(1, server.writes.size, "the second waits behind the first in its thread")
        assertTrue(storage.text.contains("first"), "kept on disk in case the app closes")
        assertEquals("Trying again shortly", Outbox.status(queue.items.value.first(), testScheduler.currentTime))

        online = true
        server.writes.clear()
        advanceTimeBy(10_001)
        runCurrent()

        assertEquals(listOf("first", "second"), server.writes.map { it.second.substringAfter("\"body\":\"").substringBefore("\"") })
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a refused send gives up and stays listed`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { _, _ -> respond("""{"error":"conflict","message":"Already answered."}""", HttpStatusCode.Conflict) }
        }
        val (queue, actions) = setUp(server)

        actions.answer(FakeDashboard.request("r1", 1), "Builder", JsonPrimitive(true))
        runCurrent()

        val failed = Outbox.failed(queue.items.value).single()
        assertEquals("Already answered.", failed.lastError)
    }

    @Test
    fun `a queued answer marks its question as sending`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            requests = listOf(FakeDashboard.request("r1", 1))
            onWrite = { _, _ -> throw IllegalStateException("offline") }
        }
        val queue = OutboxQueue(server.client(), MemoryOutboxStorage(), backgroundScope, clock = { testScheduler.currentTime })
        val actions = OwnerActions(queue, clock = { testScheduler.currentTime })
        val dashboard = Dashboard(server.client(), backgroundScope, outbox = queue, clock = { testScheduler.currentTime })
        dashboard.start()
        queue.start()
        runCurrent()
        assertEquals(listOf(false), dashboard.waiting.value.map { it.sending })

        actions.answer(server.requests.single(), "Builder", JsonPrimitive(true))
        runCurrent()
        assertEquals(listOf(true), dashboard.waiting.value.map { it.sending })
    }
}
