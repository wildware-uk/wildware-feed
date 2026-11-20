package dev.wildware.agents

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ticking and voting on blocks (docs/BLOCKS.md): shown at once, then the server's answer. */
class BlockActionsTest {
    private val json = Json { encodeDefaults = true }

    private fun TestScope.started(server: FakeDashboard): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    private val list = BlockView(
        id = "ship", type = BlockView.Checklist, rev = 1,
        items = listOf(ChecklistItem("1", "Tests"), ChecklistItem("2", "Notes")),
    )

    private fun Dashboard.card(): UpdateCard = everything.state.value.cards.single() as UpdateCard

    private fun event(id: String, rev: Long) = buildJsonObject {
        put("card", buildJsonObject { put("kind", "update"); put("id", id) })
        put("projectId", "p1")
        put("blockId", "ship")
        put("rev", rev)
    }

    @Test
    fun `a tick shows at once then the server's answer and a refusal puts it back`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5).copy(blocks = listOf(list))) }
        var refuse = false
        server.onWrite = { request, _ ->
            val path = request.url.encodedPath
            when {
                refuse -> respond("""{"error":"invalid_argument","message":"no"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                path.endsWith("/check") -> {
                    val answered = list.copy(rev = 2, items = list.items.map { if (it.id == "2") it.copy(done = true, doneBy = BlockActor("person", FounderId, "Sam")) else it })
                    respond("""{"block":${json.encodeToString(BlockView.serializer(), answered)}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> null
            }
        }
        val dashboard = started(server)
        val card = CardRef(CardKinds.Update, "u1")
        dashboard.checkBlockItem(card, "ship", "2", true)
        runCurrent()
        val item = dashboard.card().update.blocks.single().items[1]
        assertTrue(item.done)
        assertEquals("Sam", item.doneBy?.name, "the server's answer is kept")
        assertEquals("POST /api/cards/update/u1/blocks/ship/check" to """{"item":"2","done":true}""", server.writes.last())

        refuse = true
        dashboard.checkBlockItem(card, "ship", "1", true)
        runCurrent()
        assertEquals(listOf(false, true), dashboard.card().update.blocks.single().items.map { it.done }, "refused: back as the server has it")
    }

    @Test
    fun `a vote moves yours at once`() = runTest {
        val poll = BlockView(
            id = "pick", type = BlockView.Poll, rev = 1, question = "Which?", totalVoters = 1,
            options = listOf(PollOption("a", "Atlas", 1, listOf(BlockActor("person", "m2", "Ann"))), PollOption("b", "Beacon")),
        )
        val voted = poll.withVote(listOf("b"), FounderId)
        assertEquals(listOf(1, 1), voted.options.map { it.votes })
        assertEquals(2, voted.totalVoters)
        val moved = voted.withVote(listOf("a"), FounderId)
        assertEquals(listOf(2, 0), moved.options.map { it.votes })
        assertEquals(2, moved.totalVoters)
        val back = moved.withVote(emptyList(), FounderId)
        assertEquals(listOf(1, 0), back.options.map { it.votes })
        assertEquals(1, back.totalVoters)
    }


    @Test
    fun `two quick ticks on one checklist each settle only themselves`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5).copy(blocks = listOf(list))) }
        server.onWrite = { request, body ->
            val item = if ("\"item\":\"1\"" in body) "1" else "2"
            // The second line's answer is slow.
            if (item == "2") kotlinx.coroutines.delay(1_000)
            val answered = list.copy(
                rev = if (item == "1") 2 else 3,
                items = list.items.map { if (it.id == item || (item == "2" && it.id == "1")) it.copy(done = true) else it },
            )
            respond("""{"block":${json.encodeToString(BlockView.serializer(), answered)}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val dashboard = started(server)
        val card = CardRef(CardKinds.Update, "u1")
        dashboard.checkBlockItem(card, "ship", "1", true)
        dashboard.checkBlockItem(card, "ship", "2", true)
        runCurrent()
        assertEquals(listOf(true, true), dashboard.card().update.blocks.single().items.map { it.done }, "the first answer leaves the second tick showing")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(true, true), dashboard.card().update.blocks.single().items.map { it.done })
        assertEquals(3, dashboard.card().update.blocks.single().rev)
    }
}
