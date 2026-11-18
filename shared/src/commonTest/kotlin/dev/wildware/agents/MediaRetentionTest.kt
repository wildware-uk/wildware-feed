package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media retention: removed media as the server sends it, the setting's calls and form, and the live event. */
class MediaRetentionTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // --- Reading removed media --------------------------------------------------------------

    @Test
    fun `removed media keeps its fields and says when and after how long`() {
        val media = json.decodeFromString(
            MediaView.serializer(),
            """{"id":"md1","updateId":"u1","kind":"image","mime":"image/png","status":"ready","bytes":2048,"width":640,"height":480,
               "filename":"chart.png","variants":[],"removed":{"at":"2026-11-18T03:12:00.000Z","afterDays":30}}""",
        )
        assertTrue(media.isRemoved)
        assertEquals(MediaView.Removed("2026-11-18T03:12:00.000Z", 30), media.removed)
        assertEquals(emptyList(), media.variants)
        assertEquals("chart.png", media.filename)
        assertEquals(640, media.width)
    }

    @Test
    fun `media that is kept has no removed key and reads as before`() {
        val media = json.decodeFromString(MediaView.serializer(), """{"id":"md2","kind":"video","status":"ready","variants":["original","poster"]}""")
        assertFalse(media.isRemoved)
        assertNull(media.removed)
    }

    @Test
    fun `a card's media list mixes removed and kept media in place`() {
        val update = json.decodeFromString(
            UpdateView.serializer(),
            """{"id":"u1","seq":3,"projectId":"p1","agentId":"a1","body":"Look","createdAt":1,"media":[
                {"id":"a","kind":"image","status":"ready","variants":["original"]},
                {"id":"b","kind":"audio","status":"ready","variants":[],"removed":{"at":"2026-11-18T03:00:00Z","afterDays":7}}
            ]}""",
        )
        assertEquals(listOf(false, true), update.media.map { it.isRemoved })
        assertEquals(7, update.media[1].removed?.afterDays)
    }

    // --- Words ---------------------------------------------------------------------------------

    @Test
    fun `the placeholder and the warning say the days plainly`() {
        assertEquals("Media removed after 30 days", removedMediaLabel(30))
        assertEquals("Media removed after 1 day", removedMediaLabel(1))
        assertEquals(
            "Media older than 14 days will be deleted for good, each night. Pinned cards and open requests keep theirs.",
            retentionWarning(14),
        )
        assertEquals("Forever", MediaRetention.label(null))
    }

    // --- The form --------------------------------------------------------------------------------

    @Test
    fun `a saved length opens on its preset, or on custom with its number`() {
        assertEquals(RetentionPeriod.Forever, RetentionForm.of(MediaRetention()).period)
        assertEquals(RetentionPeriod.Preset(30), RetentionForm.of(MediaRetention(30)).period)
        val custom = RetentionForm.of(MediaRetention(45))
        assertEquals(RetentionPeriod.Custom, custom.period)
        assertEquals("45", custom.customDays)
        assertEquals(45, custom.days)
        assertFalse(custom.changed)
    }

    @Test
    fun `custom days must be a whole number from 1 to 3650`() {
        val form = RetentionForm.of(MediaRetention()).choose(RetentionPeriod.Custom)
        val wanted = "Choose a number of days from 1 to 3650."
        assertEquals(wanted, form.problem, "empty")
        assertEquals(wanted, form.typeCustom("0").problem)
        assertEquals(wanted, form.typeCustom("3651").problem)
        assertNull(form.typeCustom("1").problem)
        assertNull(form.typeCustom("3650").problem)
        assertEquals(3650, form.typeCustom("3650").chosen?.days)
        // Only digits go in, four at most.
        assertEquals("12", form.typeCustom("1x2").customDays)
        assertEquals("1234", form.typeCustom("123456").customDays)
        assertNull(form.typeCustom("0").chosen, "nothing to send while it is wrong")
    }

    @Test
    fun `at least one kind stays ticked`() {
        var form = RetentionForm.of(MediaRetention(30, kinds = listOf("image", "video")))
        form = form.toggle("image")
        assertEquals(listOf("video"), form.kinds)
        assertFalse(form.canToggle("video"))
        assertEquals(form, form.toggle("video"), "the last kind can't be switched off")
        assertTrue(form.canToggle("audio"))
        // Kinds go back in the dashboard's order, whatever order they were ticked in.
        assertEquals(listOf("video", "audio", "file"), form.toggle("file").toggle("audio").chosen?.kinds)
        assertEquals("Choose at least one kind of media.", form.copy(kinds = emptyList()).problem)
    }

    @Test
    fun `turning it on, shortening it or adding a kind asks first, the rest does not`() {
        val off = RetentionForm.of(MediaRetention())
        assertTrue(off.choose(RetentionPeriod.Preset(90)).deletesMore, "turning it on")
        assertFalse(off.deletesMore, "nothing changed")

        val thirty = RetentionForm.of(MediaRetention(30, kinds = listOf("image")))
        assertTrue(thirty.choose(RetentionPeriod.Preset(7)).deletesMore, "shorter")
        assertFalse(thirty.choose(RetentionPeriod.Preset(90)).deletesMore, "longer")
        assertFalse(thirty.choose(RetentionPeriod.Forever).deletesMore, "turning it off")
        assertTrue(thirty.toggle("video").deletesMore, "a kind it left alone")
        assertFalse(RetentionForm.of(MediaRetention(30, listOf("image", "video"))).toggle("video").deletesMore, "a kind taken off")
        assertEquals("Media older than 7 days will be deleted for good, each night. Pinned cards and open requests keep theirs.", thirty.choose(RetentionPeriod.Preset(7)).warning)
    }

    @Test
    fun `changing anything closes the confirm step`() {
        val asking = RetentionForm.of(MediaRetention()).choose(RetentionPeriod.Preset(7)).copy(confirming = true)
        assertFalse(asking.choose(RetentionPeriod.Preset(14)).confirming)
        assertFalse(asking.toggle("file").confirming)
        assertFalse(asking.typeCustom("9").confirming)
    }

    // --- The calls -------------------------------------------------------------------------------

    private val seen = mutableListOf<Pair<HttpRequestData, String>>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String) =
        DashboardClient("https://dash.test/", "device-key", MockEngine { request ->
            seen += request to request.textOf()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    @Test
    fun `the setting is read with the device key`() = runTest {
        val setting = client(body = """{"days":30,"kinds":["image","video"]}""").mediaRetention()

        val (request, _) = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://dash.test/api/settings/media-retention", request.url.toString())
        assertEquals("Bearer device-key", request.headers[HttpHeaders.Authorization])
        assertEquals(MediaRetention(30, listOf("image", "video")), setting)
        assertEquals(MediaRetention(null, MediaRetention.AllKinds), client(body = """{"days":null,"kinds":["image","video","audio","file"]}""").mediaRetention())
    }

    @Test
    fun `saving sends days, null for forever, and the kinds`() = runTest {
        val stored = client(body = """{"days":7,"kinds":["image"]}""").setMediaRetention(MediaRetention(7, listOf("image")))
        client(body = """{"days":null,"kinds":["image"]}""").setMediaRetention(MediaRetention(null, listOf("image")))

        assertEquals(MediaRetention(7, listOf("image")), stored)
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Put), seen.map { it.first.method })
        assertEquals(listOf("""{"days":7,"kinds":["image"]}""", """{"days":null,"kinds":["image"]}"""), seen.map { it.second })
    }

    @Test
    fun `a refusal carries the dashboard's own words`() = runTest {
        val refused = assertFailsWith<DashboardException> {
            client(HttpStatusCode.BadRequest, """{"error":"invalid_argument","message":"choose at least one kind of media"}""")
                .setMediaRetention(MediaRetention(7, emptyList()))
        }
        assertEquals(400, refused.status)
        assertEquals("choose at least one kind of media", refused.message)
    }

    // --- The live event --------------------------------------------------------------------------

    private fun TestScope.started(server: FakeDashboard): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    private fun removedEvent(mediaId: String, updateId: String? = null, messageId: String? = null, requestId: String? = null): JsonObject = buildJsonObject {
        put("mediaId", mediaId); put("updateId", updateId); put("messageId", messageId); put("requestId", requestId)
        put("taskId", null as String?); put("kind", "image"); put("afterDays", 30)
    }

    private val ready = MediaView("md1", updateId = "u1", kind = "image", status = "ready", variants = listOf("original", "thumb-640"))
    private val gone = ready.copy(variants = emptyList(), removed = MediaView.Removed("2026-11-18T03:00:00Z", 30))

    @Test
    fun `media removed from an update turns its picture into the placeholder without a reload`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5).copy(media = listOf(ready))) }
        val dashboard = started(server)
        fun shown() = (dashboard.everything.state.value.allCards.single { it.id == "u1" } as UpdateCard).update.media.single()
        assertFalse(shown().isRemoved)
        val reads = server.count("/api/snapshot?")

        server.seq = 11
        server.updates = listOf(FakeDashboard.update("u1", 5).copy(media = listOf(gone)))
        server.connections.single().event("media.removed", 11, removedEvent("md1", updateId = "u1"))
        runCurrent()

        assertEquals(reads + 1, server.count("/api/snapshot?"))
        assertTrue(shown().isRemoved)
        assertEquals(30, shown().removed?.afterDays)
    }

    @Test
    fun `media removed from a reply re-reads only that reply's thread`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            updates = listOf(FakeDashboard.update("u1", 5))
            messages = listOf(FakeDashboard.message("m1", 6, "agent:a1", "Screenshot", 6_000, updateId = "u1"))
        }
        val dashboard = started(server)
        val whole = server.calls.count { it == "/api/messages" }
        val snapshots = server.count("/api/snapshot?")

        server.connections.single().event("media.removed", 11, removedEvent("md1", messageId = "m1"))
        runCurrent()

        assertEquals(whole, server.calls.count { it == "/api/messages" })
        assertEquals(1, server.count("/api/messages?update=u1"))
        assertEquals(snapshots, server.count("/api/snapshot?"), "media on a message is no reason to re-read the updates")
        assertTrue(dashboard.everything.state.value.loaded)
    }

    @Test
    fun `media removed from a question re-reads the questions`() = runTest {
        val server = FakeDashboard(testScheduler).apply { requests = listOf(FakeDashboard.request("r1", 1)) }
        started(server)
        val reads = server.count("/api/snapshot/requests")

        server.connections.single().event("media.removed", 11, removedEvent("md1", requestId = "r1"))
        runCurrent()

        assertEquals(reads + 1, server.count("/api/snapshot/requests"))
    }
}
