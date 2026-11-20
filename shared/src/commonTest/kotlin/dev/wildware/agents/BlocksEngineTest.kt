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

/** Blocks live (docs/BLOCKS.md): a change re-reads one card's blocks, not the page. */
class BlocksEngineTest {
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
    fun `a block change re-reads only that card's blocks and the card shows them`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5).copy(blocks = listOf(list))) }
        val dashboard = started(server)
        assertEquals(listOf(false, false), dashboard.card().update.blocks.single().items.map { it.done })
        val snapshots = server.count("/api/snapshot?")

        val ticked = list.copy(rev = 6, items = list.items.map { if (it.id == "1") it.copy(done = true) else it })
        server.blocks = mapOf("update/u1" to listOf(ticked))
        server.seq = 11
        val stream = server.connections.single()
        // A burst: an agent appending as fast as it can.
        for (seq in 11L..15L) stream.event("block.updated", seq, event("u1", seq - 9))
        runCurrent()
        advanceTimeBy(BlocksOverlay.BurstMillis + 1)
        runCurrent()

        assertEquals(listOf(true, false), dashboard.card().update.blocks.single().items.map { it.done })
        assertEquals(snapshots, server.count("/api/snapshot?"), "no page read for a block")
        assertEquals(1, server.count("/api/cards/update/u1/blocks"), "one read for the burst")

        // A replay of what is already held, and a card nothing shows, read nothing.
        stream.event("block.updated", 16, event("u1", 6))
        stream.event("block.updated", 17, event("elsewhere", 4))
        runCurrent()
        advanceTimeBy(BlocksOverlay.BurstMillis + 1)
        runCurrent()
        assertEquals(1, server.count("/api/cards/update/u1/blocks"))
        assertEquals(0, server.count("/api/cards/update/elsewhere/blocks"))
    }

    @Test
    fun `blocks come through a thread read and its cache`() = runTest {
        val wire = """{"seq":3,"messages":[],"blocks":{"m1":[{"id":"l","type":"log","lines":["a","b"],"dropped":4,"rev":3}]}}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(MessagesSnapshot.serializer(), wire)
        val log = parsed.blocks.getValue("m1").single()
        assertEquals(listOf("a", "b"), log.lines)
        assertEquals(4, log.dropped)
        assertEquals(5, log.tail, "the default")
    }
}
