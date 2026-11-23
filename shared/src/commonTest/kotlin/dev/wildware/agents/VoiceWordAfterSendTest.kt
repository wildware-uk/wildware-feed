package dev.wildware.agents

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sent voice note keeps its unsure words on this device, and swapping one edits the message. */
class VoiceWordAfterSendTest {
    private val json = Json { encodeDefaults = true }
    private val friday = VoiceWord(8, 14, 0.3, listOf("Monday"))

    private fun TestScope.started(server: FakeDashboard): Pair<Dashboard, OwnerActions> {
        val clock = { testScheduler.currentTime }
        val client = server.client()
        val outbox = OutboxQueue(client, MemoryOutboxStorage(), backgroundScope, clock = clock)
        val dashboard = Dashboard(client, backgroundScope, ReadMarks(), outbox, clock = clock)
        outbox.start()
        dashboard.start()
        runCurrent()
        return dashboard to OwnerActions(outbox, clock = clock, newId = { "n1" })
    }

    private fun FakeDashboard.answering(body: String) {
        onWrite = { request, _ ->
            val sent = FakeDashboard.message("m1", 5, "human", body, 5_000)
            val code = if (request.url.encodedPath == "/api/messages") HttpStatusCode.Created else HttpStatusCode.OK
            respond("""{"message":${json.encodeToString(MessageView.serializer(), sent)}}""", code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun Dashboard.post(id: String): ThreadLine = (everything.state.value.allCards.first { it.id == id } as PostCard).post

    @Test
    fun `a sent note keeps its unsure words and a swap edits it`() = runTest {
        val server = FakeDashboard(testScheduler).apply { answering("Ship on Friday") }
        val (dashboard, actions) = started(server)

        actions.post(ProjectRef("mega", "Mega"), "Ship on Friday", voice = OwnerActions.Voice(VoiceNote.UndoMillis, listOf(friday)))
        runCurrent()
        advanceTimeBy(VoiceNote.UndoMillis + 100)
        assertEquals(listOf(friday), dashboard.post("m1").words, "still offered once it is up")
        assertTrue(!dashboard.post("m1").sending)

        server.answering("Ship on Monday")
        val text = dashboard.chooseSentWord("m1", 0, "Monday")
        assertEquals("Ship on Monday", text)
        actions.edit(CardRef(CardKinds.Message, "m1"), text!!)
        runCurrent()
        assertEquals("PATCH /api/messages/m1" to """{"body":"Ship on Monday"}""", server.writes.last())
        assertEquals("Ship on Monday", dashboard.post("m1").message.body)
        assertEquals(emptyList(), dashboard.post("m1").words, "the swapped word is settled")
    }

    @Test
    fun `keeping a word changes nothing`() = runTest {
        val server = FakeDashboard(testScheduler).apply { answering("Ship on Friday") }
        val (dashboard, actions) = started(server)
        actions.post(ProjectRef("mega", "Mega"), "Ship on Friday", voice = OwnerActions.Voice(VoiceNote.UndoMillis, listOf(friday)))
        runCurrent()
        advanceTimeBy(VoiceNote.UndoMillis + 100)

        assertNull(dashboard.chooseSentWord("m1", 0, null))
        runCurrent()
        assertEquals(emptyList(), dashboard.post("m1").words)
    }
}
