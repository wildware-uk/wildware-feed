package dev.wildware.agents

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The owner changes the words of their own post or reply: it goes through the outbox and shows at once. */
class OwnerEditTest {
    private val json = Json { encodeDefaults = true }

    private lateinit var outbox: OutboxQueue

    private fun TestScope.started(server: FakeDashboard): Pair<Dashboard, OwnerActions> {
        val clock = { testScheduler.currentTime }
        val client = server.client()
        outbox = OutboxQueue(client, MemoryOutboxStorage(), backgroundScope, clock = clock)
        val dashboard = Dashboard(client, backgroundScope, ReadMarks(), outbox, clock = clock)
        outbox.start()
        dashboard.start()
        runCurrent()
        return dashboard to OwnerActions(outbox, clock = clock, newId = { "e1" })
    }

    private fun Dashboard.body(cardId: String): String = everything.state.value.allCards.first { it.id == cardId }.let { (it as PostCard).post.message.body }

    @Test
    fun `an edit goes up as a patch of the message`() = runTest {
        val server = FakeDashboard(testScheduler).apply { messages = listOf(FakeDashboard.message("p1", 1, "human", "ship on fridya", 1_000)) }
        val (_, actions) = started(server)

        actions.edit(CardRef(CardKinds.Message, "p1"), "ship on Friday")
        runCurrent()

        assertEquals(listOf("PATCH /api/messages/p1" to """{"body":"ship on Friday"}"""), server.writes)
    }

    @Test
    fun `the new words show at once and stay once the dashboard has them`() = runTest {
        var online = false
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(FakeDashboard.message("p1", 1, "human", "ship on fridya", 1_000))
            onWrite = { _, _ ->
                if (!online) respond("", HttpStatusCode.ServiceUnavailable)
                else {
                    val edited = FakeDashboard.message("p1", 1, "human", "ship on Friday", 1_000).copy(editedAt = 2_000)
                    respond("""{"message":${json.encodeToString(MessageView.serializer(), edited)}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val (dashboard, actions) = started(server)

        actions.edit(CardRef(CardKinds.Message, "p1"), "ship on Friday")
        runCurrent()
        assertEquals("ship on Friday", dashboard.body("p1"), "shown while it waits for signal")

        online = true
        outbox.kick(ignoreBackoff = true)
        runCurrent()
        assertEquals("ship on Friday", dashboard.body("p1"), "and kept once sent, without waiting for the stream")
    }
}
