package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardClientTest {
    private val seen = mutableListOf<Pair<HttpRequestData, String>>()

    private fun client(token: String? = "device-key", reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DashboardClient("https://dash.test/", token, MockEngine { request ->
            seen += request to request.bodyText()
            reply(request)
        })

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `summary is read with the device key`() = runTest {
        val summary = client { json(SUMMARY) }.summary()

        val (request, _) = seen.single()
        assertEquals("https://dash.test/api/summary", request.url.toString())
        assertEquals("Bearer device-key", request.headers[HttpHeaders.Authorization])
        assertEquals(RequestKind.Buttons, summary.requests.single().kind)
        assertEquals(listOf("Yes", "Later"), summary.requests.single().options)
    }

    @Test
    fun `registering a device uses the web login cookie instead of a key`() = runTest {
        val registration = client(token = null) {
            json("""{"device":{"id":"d1","name":"Phone","createdAt":"2026-11-16T12:00:00Z"},"token":"new-key"}""", HttpStatusCode.Created)
        }.registerDevice("Phone", "ad_session=abc")

        val (request, body) = seen.single()
        assertEquals("ad_session=abc", request.headers[HttpHeaders.Cookie])
        assertEquals(null, request.headers[HttpHeaders.Authorization])
        assertEquals("""{"name":"Phone"}""", body)
        assertEquals("new-key", registration.token)
    }

    @Test
    fun `a notification button answers its request`() = runTest {
        val answered = client { json("""{"request":{}}""") }
            .answerFromAction("r1", "buttons", listOf("Yes", "Later"), "option-1", null)

        assertTrue(answered)
        val (request, body) = seen.single()
        assertEquals("/api/requests/r1/answer", request.url.encodedPath)
        assertEquals("""{"value":"Later"}""", body)
    }

    @Test
    fun `a stale button sends nothing`() = runTest {
        val answered = client { error("should not be called") }
            .answerFromAction("r1", "buttons", listOf("Yes"), "option-4", null)

        assertFalse(answered)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a ticket is a feed post marked as work`() = runTest {
        val id = client { request ->
            if (request.url.encodedPath == "/api/messages") json("""{"message":{"id":"m9"}}""", HttpStatusCode.Created)
            else json("""{"card":{"kind":"message","id":"m9"},"status":"todo"}""")
        }.createTicket("garden-game", "fix the flaky merge test")

        assertEquals("m9", id)
        assertEquals(listOf("/api/messages", "/api/cards/message/m9/status"), seen.map { it.first.url.encodedPath })
        assertEquals("""{"project":"garden-game","body":"fix the flaky merge test"}""", seen[0].second)
        assertEquals("""{"status":"todo"}""", seen[1].second)
    }

    @Test
    fun `a reply goes into the thread the notification named`() = runTest {
        client { json("""{"message":{"id":"m2"}}""", HttpStatusCode.Created) }
            .reply(mapOf("update" to "u5"), "looks good")

        assertEquals("""{"update":"u5","body":"looks good"}""", seen.single().second)
    }

    @Test
    fun `sharing uploads each file then posts them with a caption`() = runTest {
        client { request ->
            if (request.url.encodedPath == "/api/media") json("""{"media":{"id":"x1"}}""", HttpStatusCode.Created)
            else json("""{"message":{"id":"m3"}}""", HttpStatusCode.Created)
        }.share("garden-game", null, listOf(Attachment("shot.png", "image/png", byteArrayOf(1, 2, 3))))

        val (upload, _) = seen[0]
        assertEquals(HttpMethod.Post, upload.method)
        assertEquals("shot.png", upload.url.parameters["filename"])
        assertEquals("image/png", upload.body.contentType.toString())
        assertEquals(
            """{"project":"garden-game","body":"Shared from my phone: shot.png","mediaIds":["x1"]}""",
            seen[1].second,
        )
    }

    @Test
    fun `the composer talks over a question in its own thread, photos and all`() = runTest {
        client { request ->
            if (request.url.encodedPath == "/api/media") json("""{"media":{"id":"x1"}}""", HttpStatusCode.Created)
            else json("""{"message":{"id":"m4"}}""", HttpStatusCode.Created)
        }.send(mapOf("request" to "r1"), " why this branch? ", listOf(Attachment("a.jpg", "image/jpeg", byteArrayOf(1))))

        assertEquals("""{"request":"r1","body":"why this branch?","mediaIds":["x1"]}""", seen[1].second)
    }

    @Test
    fun `sending nothing at all uploads nothing`() = runTest {
        assertFailsWith<DashboardException> { client { error("unreachable") }.send(mapOf("project" to "p"), " ", emptyList()) }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a question's detail and suggested answer are read when the dashboard sends them`() = runTest {
        val summary = client {
            json(SUMMARY.replace(""""expiresAt":"2026-11-16T13:00:00Z"""", """"expiresAt":"2026-11-16T13:00:00Z","detail":"Branch fee-rounding","default":"Later""""))
        }.summary()

        val request = summary.requests.single()
        assertEquals("Branch fee-rounding", request.detail)
        assertEquals("Later", request.defaultAnswer)
    }

    @Test
    fun `older dashboards send no detail or default`() = runTest {
        val request = client { json(SUMMARY) }.summary().requests.single()
        assertEquals(null, request.detail)
        assertEquals(null, request.defaultAnswer)
    }

    @Test
    fun `a refusal carries the dashboard's code and message`() = runTest {
        val error = assertFailsWith<DashboardException> {
            client { json("""{"error":"conflict","message":"already answered"}""", HttpStatusCode.Conflict) }
                .answer("r1", kotlinx.serialization.json.JsonPrimitive(true))
        }

        assertEquals(409, error.status)
        assertEquals("conflict", error.code)
        assertEquals("already answered", error.message)
    }

    @Test
    fun `without a device key nothing is sent`() = runTest {
        val error = assertFailsWith<DashboardException> { client(token = null) { error("unreachable") }.summary() }

        assertEquals(401, error.status)
        assertTrue(seen.isEmpty())
    }

    private suspend fun HttpRequestData.bodyText(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.NoContent -> ""
        else -> content.toString()
    }

    private companion object {
        const val SUMMARY = """
            {"at":"2026-11-16T12:00:00Z",
             "onlineAgents":[{"agentId":"a1","name":"Garden Game","host":null,"cwd":"/w/mm"}],
             "requests":[{"id":"r1","agentName":"Garden Game","projectSlug":"garden-game","projectName":"Garden Game",
                          "question":"Deploy now?","kind":"buttons","options":["Yes","Later"],"expiresAt":"2026-11-16T13:00:00Z"}],
             "work":{"todo":1,"inProgress":2,"blocked":0},
             "projects":[{"slug":"garden-game","name":"Garden Game"}],
             "extraFieldFromANewerServer":true}
        """
    }
}
