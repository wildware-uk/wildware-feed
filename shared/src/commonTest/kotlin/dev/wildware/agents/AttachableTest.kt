package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Any file from a phone's picker or share sheet goes up as itself, with a name and type the dashboard takes. */
class AttachableTest {
    @Test
    fun `a real type is kept and a vague or broken one gives way to the name`() {
        assertEquals("application/pdf", Attachable.mimeType("application/pdf", "report.pdf"))
        assertEquals("application/pdf", Attachable.mimeType("Application/PDF; charset=binary", "report"))
        assertEquals("application/pdf", Attachable.mimeType("application/octet-stream", "report.pdf"))
        assertEquals("application/zip", Attachable.mimeType(null, "logs.zip"))
        assertEquals("text/plain", Attachable.mimeType("", "build.log"))
        assertEquals("text/plain", Attachable.mimeType("*/*", "build.log"))
        assertEquals("application/octet-stream", Attachable.mimeType(null, "save.bin"))
        assertEquals("application/octet-stream", Attachable.mimeType("not a type", "README"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Attachable.mimeType(null, "Notes.DOCX"),
        )
    }

    @Test
    fun `a name loses folders and hidden characters but keeps its extension`() {
        assertEquals("report.pdf", Attachable.fileName("/private/var/tmp/report.pdf"))
        assertEquals("report.pdf", Attachable.fileName("C:\\Users\\me\\report.pdf"))
        // A right-to-left override would make an "exe" read as a "pdf".
        assertEquals("invoicefdp.exe", Attachable.fileName("invoice\u202Efdp.exe"))
        assertEquals("ab.txt", Attachable.fileName("a\u200Bb\u0007.txt"))
        assertEquals("caf\u00E9 \uD83D\uDCC4.txt", Attachable.fileName(" caf\u00E9 \uD83D\uDCC4.txt "))
        assertEquals("shared", Attachable.fileName("  ", fallback = "shared"))
        assertEquals("file", Attachable.fileName(null))
        val long = Attachable.fileName("x".repeat(300) + ".pdf")
        assertEquals(Attachable.MaxNameLength, long.length)
        assertEquals(".pdf", long.takeLast(4))
    }

    @Test
    fun `pictures may weigh up to ten megabytes and other files twenty five`() {
        val mb = 1024L * 1024
        assertEquals(10 * mb, Attachable.maxBytes("image/jpeg"))
        assertEquals(25 * mb, Attachable.maxBytes("application/pdf"))
        assertEquals(25 * mb, Attachable.maxBytes("video/quicktime"))
        // HEIC is no picture to the dashboard: it is offered as a file.
        assertEquals(25 * mb, Attachable.maxBytes("image/heic"))
        assertNull(Attachable.refusal("a.pdf", "application/pdf", 25 * mb))
        assertEquals("a.pdf is over 25 MB, so it was left out.", Attachable.refusal("a.pdf", "application/pdf", 25 * mb + 1))
        assertEquals("a.png is over 10 MB, so it was left out.", Attachable.refusal("a.png", "image/png", 10 * mb + 1))
        assertEquals("a.txt is empty, so it was left out.", Attachable.refusal("a.txt", "text/plain", 0))
    }

    @Test
    fun `a queued file of any type keeps its name and type and goes up as them`() = runTest {
        val pdf = OutboxFile("a-0", "report.pdf", "application/pdf")
        val item = Outbox.message("a", 1, "To Garden Game", mapOf("project" to "garden-game"), null, listOf(pdf))
        assertEquals(listOf(pdf), Outbox.decode(Outbox.encode(listOf(item))).single().files)

        val uploads = mutableListOf<Pair<String?, String?>>()
        val client = DashboardClient("https://dash.test/", "key", MockEngine { request ->
            if (request.url.encodedPath.endsWith("/api/media")) {
                uploads += request.url.parameters["filename"] to request.body.contentType?.toString()
                respond("""{"media":{"id":"m1","kind":"file","mime":"application/pdf","status":"ready"}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("""{"message":{"id":"msg1"}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val result = client.deliver(item, listOf(Attachment("report.pdf", "application/pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))))
        assertEquals(DeliveryOutcome.Sent, result.outcome)
        assertEquals(listOf<Pair<String?, String?>>("report.pdf" to "application/pdf"), uploads)
    }
}
