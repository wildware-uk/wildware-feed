package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A project's feed shows that project and nothing else, whatever arrives live. The all-projects
 * feed shows everything, and the drawer's counts for other projects still move.
 */
class ProjectScopeTest {
    private fun TestScope.started(server: FakeDashboard): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    /** Mega (p1) is open; Other (p2) has a post the owner wrote, which an agent then picks up. */
    private fun FakeDashboard.twoProjects() = apply {
        updates = listOf(FakeDashboard.update("u1", 5, projectId = "p1"))
        messages = listOf(FakeDashboard.message("mine", 6, "human", "In mega", 6_000, projectId = "p1"))
    }

    private fun Dashboard.ids(slug: String?): List<String> = feed(slug).state.value.allCards.map { it.id }

    @Test
    fun `an agent acknowledging another project's post leaves this feed alone`() = runTest {
        val server = FakeDashboard(testScheduler).twoProjects()
        val dashboard = started(server)
        dashboard.feed("mega")
        runCurrent()
        assertEquals(listOf("mine", "u1").sorted(), dashboard.ids("mega").sorted())

        server.seq = 11
        server.messages += FakeDashboard.message("theirs", 11, "human", "In other", 11_000, projectId = "p2")
        val stream = server.connections.single()
        stream.event("message.created", 11, buildJsonObject {
            put("messageId", "theirs"); put("projectId", "p2"); put("author", "human")
        })
        // The ack names the message and the card, not the project.
        stream.event("ack.updated", 12, buildJsonObject {
            put("ackId", "k1"); put("agentId", "a1"); put("messageId", "theirs"); put("state", "thinking")
            putJsonObject("card") { put("kind", "message"); put("id", "theirs") }
        })
        runCurrent()

        assertFalse("theirs" in dashboard.ids("mega"), "Other's post stays out of Mega: ${dashboard.ids("mega")}")
        assertTrue("theirs" in dashboard.ids(null), "and shows in All projects")
    }

    @Test
    fun `a picture finishing on another project's message leaves this feed alone`() = runTest {
        val server = FakeDashboard(testScheduler).twoProjects()
        val dashboard = started(server)
        dashboard.feed("mega")
        runCurrent()

        server.seq = 11
        server.messages += FakeDashboard.message("pic", 11, "agent:a1", "A screenshot", 11_000, projectId = "p2")
        server.connections.single().event("media.ready", 11, buildJsonObject {
            put("mediaId", "md1"); put("messageId", "pic"); put("kind", "image")
        })
        runCurrent()

        assertFalse("pic" in dashboard.ids("mega"), "${dashboard.ids("mega")}")
    }

    @Test
    fun `other projects' live work never lands here but still moves their counts`() = runTest {
        val server = FakeDashboard(testScheduler).twoProjects()
        val dashboard = started(server)
        dashboard.feed("mega")
        runCurrent()
        fun waitingIn(slug: String) = dashboard.projects.value.first { it.project.slug == slug }.waiting

        server.seq = 11
        server.updates += FakeDashboard.update("u2", 11, projectId = "p2")
        server.requests += FakeDashboard.request("q2", 12, projectId = "p2")
        server.messages += FakeDashboard.message("post2", 13, "agent:a1", "Agent post in other", 13_000, projectId = "p2")
        val stream = server.connections.single()
        stream.event("update.created", 11, buildJsonObject { put("updateId", "u2"); put("projectId", "p2"); put("agentId", "a1") })
        stream.event("request.created", 12, buildJsonObject { put("requestId", "q2"); put("projectId", "p2"); put("agentId", "a1"); put("kind", "confirm") })
        stream.event("message.created", 13, buildJsonObject { put("messageId", "post2"); put("projectId", "p2"); put("author", "agent:a1") })
        runCurrent()

        val mega = dashboard.feed("mega").state.value
        assertEquals(listOf("mine", "u1").sorted(), mega.allCards.map { it.id }.sorted(), "Mega shows only Mega")
        assertTrue(mega.waiting.isEmpty(), "and none of Other's questions")
        val all = dashboard.ids(null)
        assertTrue(listOf("u2", "post2").all { it in all }, "All projects shows Other's work: $all")
        assertEquals(1, waitingIn("other"), "Other's count in the drawer rose")
        assertEquals(0, waitingIn("mega"))
    }
}
