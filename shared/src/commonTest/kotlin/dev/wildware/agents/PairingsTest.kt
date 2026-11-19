package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingsTest {
    private fun TestScope.started(server: FakeDashboard): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    private fun pairing(id: String, name: String = "laptop-personal") =
        PairingView(id = id, name = name, host = "laptop", code = "K7Q-3MD", createdAt = 1_000, expiresAt = 601_000)

    @Test
    fun `agents asking to join are read at start and on each new one`() = runTest {
        val server = FakeDashboard(testScheduler).apply { pairings = listOf(pairing("p1")) }
        val dashboard = started(server)
        assertEquals(listOf("p1"), dashboard.pairings.value.map { it.id })
        assertEquals("K7Q-3MD", dashboard.pairings.value.single().code)

        server.seq = 11
        server.pairings = listOf(pairing("p1"), pairing("p2", "ci-runner"))
        server.connections.single().event("pairing.created", 11, buildJsonObject { put("pairingId", "p2"); put("state", "pending") })
        runCurrent()
        assertEquals(listOf("p1", "p2"), dashboard.pairings.value.map { it.id })
    }

    @Test
    fun `a decided pairing leaves at once before the re-read`() = runTest {
        val server = FakeDashboard(testScheduler).apply { pairings = listOf(pairing("p1"), pairing("p2")) }
        val dashboard = started(server)
        server.readDelayMillis = 60_000
        server.connections.single().event("pairing.updated", 11, buildJsonObject { put("pairingId", "p1"); put("state", "approved") })
        runCurrent()
        assertEquals(listOf("p2"), dashboard.pairings.value.map { it.id })
    }

    @Test
    fun `a dashboard from before pairing shows none and the rest still works`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        val dashboard = started(server)
        assertTrue(dashboard.pairings.value.isEmpty())
        assertEquals(listOf("u1"), dashboard.everything.state.value.cards.map { it.id })
    }

    @Test
    fun `allowing sends the chosen name and denying sends nothing else`() = runTest {
        val server = FakeDashboard(testScheduler)
        val client = server.client()
        client.approvePairing("p1", "laptop")
        client.approvePairing("p2", null)
        client.denyPairing("p3")
        assertEquals(
            listOf(
                "POST /api/pairings/p1/approve" to """{"name":"laptop"}""",
                "POST /api/pairings/p2/approve" to "{}",
                "POST /api/pairings/p3/deny" to "",
            ),
            server.writes,
        )
    }
}
