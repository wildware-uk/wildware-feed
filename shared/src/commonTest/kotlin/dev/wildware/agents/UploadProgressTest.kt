package dev.wildware.agents

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Files the owner sends show in their thread at once, saying they are going up, and how far they have got. */
class UploadProgressTest {
    private val log = Attachment("build.log", "text/plain", ByteArray(100))
    private val clip = Attachment("take.mp3", "audio/mpeg", ByteArray(300))

    @Test
    fun `a reply's files show by name as uploading while it waits to go up`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            messages = listOf(FakeDashboard.message("p1", 1, "agent:a1", "A post", 1_000))
            // No signal: every write fails and is kept for later.
            onWrite = { _, _ -> respond("", HttpStatusCode.ServiceUnavailable) }
        }
        val client = server.client()
        val outbox = OutboxQueue(client, MemoryOutboxStorage(), backgroundScope, clock = { testScheduler.currentTime })
        val dashboard = Dashboard(client, backgroundScope, ReadMarks(), outbox, clock = { testScheduler.currentTime })
        outbox.start()
        dashboard.start()
        runCurrent()

        OwnerActions(outbox, clock = { testScheduler.currentTime }).reply(CardRef(CardKinds.Message, "p1"), null, files = listOf(log, clip))
        runCurrent()

        val line = dashboard.everything.state.value.allCards.first { it.id == "p1" }.replies.single()
        assertTrue(line.sending)
        assertEquals("", line.message.body, "the files say what they are, so the words don't repeat them")
        assertEquals(listOf("build.log", "take.mp3"), line.media.map { it.filename })
        assertEquals(listOf("file", "audio"), line.media.map { it.kind })
        assertEquals(listOf(UploadingStatus, UploadingStatus), line.media.map { it.status })
        assertEquals(emptyMap(), outbox.uploads.value, "nothing is going up between tries")
    }

    @Test
    fun `the outbox says how much of an item's files have gone up`() = runTest {
        lateinit var outbox: OutboxQueue
        val seenAtEachUpload = mutableListOf<Float?>()
        val server = FakeDashboard(testScheduler).apply {
            onWrite = { request, _ ->
                if (request.url.encodedPath == "/api/media") seenAtEachUpload += outbox.uploads.value.values.singleOrNull()
                null
            }
        }
        outbox = OutboxQueue(server.client(), MemoryOutboxStorage(), backgroundScope, clock = { testScheduler.currentTime })
        outbox.start()

        OwnerActions(outbox, clock = { testScheduler.currentTime }).reply(CardRef(CardKinds.Message, "p1"), "Logs", files = listOf(log, clip))
        runCurrent()

        // The second file starts once the first (100 of 400 bytes) is up.
        assertEquals(2, seenAtEachUpload.size)
        assertEquals(0.25f, seenAtEachUpload[1])
        assertEquals(emptyMap(), outbox.uploads.value, "sent: nothing left going up")
        assertTrue(outbox.items.value.isEmpty())
    }

    @Test
    fun `sending counts the bytes of every file together`() = runTest {
        val seen = mutableListOf<Long>()
        FakeDashboard(testScheduler).client().sendMessage(mapOf("replyTo" to "p1"), "Logs", listOf(log, clip)) { seen += it }
        assertEquals(400L, seen.last())
        assertTrue(100L in seen, "the first file's bytes are counted when it is up")
        assertEquals(seen.sorted(), seen, "it never goes backwards")
    }
}
