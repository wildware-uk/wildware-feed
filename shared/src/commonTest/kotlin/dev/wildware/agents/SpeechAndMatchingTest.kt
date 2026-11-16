package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeechAndMatchingTest {
    private val projects = listOf(
        ProjectRef("garden-game", "Garden Game"),
        ProjectRef("agent-dashboard", "Agent Dashboard"),
        ProjectRef("garden-game", "Garden Game"),
    )

    @Test
    fun `a spoken name with filler words finds the project`() {
        assertEquals("garden-game", ProjectMatcher.bestMatch("the garden game agent", projects)?.slug)
        assertEquals("agent-dashboard", ProjectMatcher.bestMatch("dashboard", projects)?.slug)
        assertEquals("garden-game", ProjectMatcher.bestMatch("garden", projects)?.slug)
    }

    @Test
    fun `a run-together name still matches`() {
        assertEquals("garden-game", ProjectMatcher.bestMatch("gardengame", projects)?.slug)
    }

    @Test
    fun `nothing in common matches nothing`() {
        assertNull(ProjectMatcher.bestMatch("weather app", projects))
        assertNull(ProjectMatcher.bestMatch("the agent", projects))
    }

    @Test
    fun `status reads counts and the oldest question`() {
        val summary = summary(
            online = 2,
            requests = listOf(request("Garden Game", "Can I restart the server?")),
            inProgress = 3,
        )

        assertEquals(
            "Two agents are online. Three cards are in progress. One question is waiting. Garden Game asks: Can I restart the server?",
            Speech.status(summary),
        )
    }

    @Test
    fun `a quiet dashboard says so`() {
        assertEquals("No agents are online. Nobody is waiting on you.", Speech.status(summary(online = 0)))
    }

    @Test
    fun `a request reads its options aloud`() {
        val request = request("Builder", "Which branch?", RequestKind.Choice, listOf("main", "dev", "release"))

        assertEquals("Builder asks: Which branch? Options are: main, dev or release.", Speech.request(request))
    }

    private fun summary(online: Int, requests: List<PendingRequest> = emptyList(), inProgress: Int = 0) = DashboardSummary(
        at = "2026-11-16T12:00:00Z",
        onlineAgents = (1..online).map { OnlineAgent("a$it", "Agent $it") },
        requests = requests,
        work = WorkCounts(todo = 0, inProgress = inProgress, blocked = 0),
        projects = projects,
    )

    private fun request(
        agent: String,
        question: String,
        kind: RequestKind = RequestKind.Confirm,
        options: List<String>? = null,
    ) = PendingRequest("r1", agent, "garden-game", "Garden Game", question, kind, options, "2026-11-16T13:00:00Z")
}
