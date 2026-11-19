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
    fun `the composer talks over a question in its own thread with photos`() = runTest {
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
    fun `the asking agent and the work in progress are read when the dashboard sends them`() = runTest {
        val summary = client {
            json(
                SUMMARY
                    .replace(""""agentName":"Garden Game",""", """"agentId":"a1","agentName":"Garden Game",""")
                    .replace(""""extraFieldFromANewerServer":true""", """"working":[{"agentId":"a1","agent":"Rover","task":"Fixing fee rounding","since":1763460000},{"agentId":null,"agent":"An agent","task":"Tidy the docs","since":1763450000}],"extraFieldFromANewerServer":true"""),
            )
        }.summary()

        assertEquals("a1", summary.requests.single().agentId)
        assertEquals(
            listOf(Working("a1", "Rover", "Fixing fee rounding", 1_763_460_000), Working(null, "An agent", "Tidy the docs", 1_763_450_000)),
            summary.working,
        )
    }

    @Test
    fun `older dashboards send no asking agent or work in progress`() = runTest {
        val summary = client { json(SUMMARY) }.summary()
        assertEquals(null, summary.requests.single().agentId)
        assertEquals(emptyList(), summary.working)
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
    fun `the device reads which alerts it chose`() = runTest {
        val prefs = client { json("""{"prefs":{"types":["request","update"],"levels":["error"]}}""") }.alertPrefs()

        val (request, _) = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://dash.test/api/devices/current/alert-prefs", request.url.toString())
        assertEquals(AlertPrefs(types = listOf("request", "update"), levels = listOf("error")), prefs)
        assertEquals(null, client { json("""{"prefs":null}""") }.alertPrefs())
    }

    @Test
    fun `the device saves its choice and every alert is null`() = runTest {
        val saved = client { json("""{"ok":true,"prefs":{"types":["request"]}}""") }.setAlertPrefs(AlertPrefs(types = listOf("request")))
        client { json("""{"ok":true,"prefs":null}""") }.setAlertPrefs(null)

        assertEquals(AlertPrefs(types = listOf("request")), saved)
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Put), seen.map { it.first.method })
        assertEquals(listOf("""{"prefs":{"types":["request"]}}""", """{"prefs":null}"""), seen.map { it.second })
    }

    @Test
    fun `a focus filter names the projects that may still alert`() = runTest {
        client { json("""{"ok":true}""") }.setAlertProjects(listOf("garden-game", "harbor-app", "garden-game"))

        val (request, body) = seen.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("https://dash.test/api/devices/current/alert-projects", request.url.toString())
        assertEquals("Bearer device-key", request.headers[HttpHeaders.Authorization])
        assertEquals("""{"projects":["garden-game","harbor-app"]}""", body)
    }

    @Test
    fun `turning a focus filter off or listing nothing lifts the limit`() = runTest {
        val api = client { json("""{"ok":true}""") }
        api.setAlertProjects(null)
        api.setAlertProjects(emptyList())

        assertEquals(listOf("""{"projects":null}""", """{"projects":null}"""), seen.map { it.second })
    }

    @Test
    fun `an unknown project in a focus filter is the dashboard's refusal`() = runTest {
        val error = assertFailsWith<DashboardException> {
            client { json("""{"error":"invalid_argument","message":"unknown project: gone"}""", HttpStatusCode.BadRequest) }
                .setAlertProjects(listOf("gone"))
        }

        assertEquals(400, error.status)
        assertEquals("invalid_argument", error.code)
    }

    @Test
    fun `recent activity is named and plain`() = runTest {
        val activity = client { json(SNAPSHOT) }.recent(projectSlug = "garden-game", limit = 5)

        val (request, _) = seen.single()
        assertEquals("/api/snapshot", request.url.encodedPath)
        assertEquals("5", request.url.parameters["limit"])
        assertEquals("garden-game", request.url.parameters["project"])
        assertEquals(listOf(ProjectRef("garden-game", "Garden Game")), activity.projects)

        val update = activity.updates.single()
        assertEquals("Builder", update.agentName)
        assertEquals("garden-game", update.project?.slug)
        assertEquals("Merged\nAll tests pass.", update.text)
        assertEquals("Merged", update.headline)

        val message = activity.messages.single()
        assertEquals("m2", message.id)
        assertEquals("An agent", message.agentName)
        assertEquals("Done, see the PR.", message.text)
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

        const val SNAPSHOT = """
            {"seq":9,"at":"2026-11-16T12:00:00Z",
             "projects":[{"id":"p1","seq":1,"slug":"garden-game","name":"Garden Game","status":"active","theme":null},
                         {"id":"p2","seq":2,"slug":"old","name":"Old","status":"archived"}],
             "agentNames":{"a1":"Builder"},
             "updates":{"items":[
                {"id":"u1","seq":3,"projectId":"p1","agentId":"a1","title":null,"body":"# Merged\n\n**All** tests pass.",
                 "level":"info","createdAt":1700000000000,"deletedAt":null,"media":[]},
                {"id":"u0","seq":2,"projectId":"p1","agentId":"a1","title":"Gone","body":"x","createdAt":1600000000000,"deletedAt":1600000001000}
             ],"nextCursor":null,"hasMore":false},
             "messages":[
                {"id":"m1","projectId":"p1","author":"human","body":"how is it going?","createdAt":1700000001000},
                {"id":"m2","projectId":"p1","author":"agent:a9","body":"Done, see [the PR](https://x).","createdAt":1700000002000}
             ],
             "tags":[]}
        """
    }

    @Test
    fun `a new project is made with its name and description`() = runTest {
        val made = client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"harbor-app","description":"Ferry times"},"created":true}""", HttpStatusCode.Created) }
            .createProject("harbor-app", "Ferry times")

        val (request, body) = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://dash.test/api/projects", request.url.toString())
        assertEquals("""{"name":"harbor-app","description":"Ferry times"}""", body)
        assertEquals("harbor-app", made.slug)
    }

    @Test
    fun `renaming sends the name and description and an empty description clears it`() = runTest {
        client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"Harbour"}}""") }.renameProject("harbor-app", "Harbour", "")

        val (request, body) = seen.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("https://dash.test/api/projects/harbor-app", request.url.toString())
        assertEquals("""{"name":"Harbour","description":null}""", body)
    }

    @Test
    fun `archiving and unarchiving set the status`() = runTest {
        val c = client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"harbor-app"}}""") }
        c.setProjectArchived("harbor-app", true)
        c.setProjectArchived("harbor-app", false)

        assertEquals(listOf("""{"status":"archived"}""", """{"status":"active"}"""), seen.map { it.second })
    }

    @Test
    fun `renaming an agent patches its name`() = runTest {
        client { json("""{"agent":{"id":"a1","name":"forge-mac"}}""") }.renameAgent("a1", "forge-mac")

        assertEquals("""{"name":"forge-mac"}""", seen.single().second)
    }

    @Test
    fun `a logo is set and taken off and can stand in for the name`() = runTest {
        val c = client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"harbor-app"}}""") }
        c.setProjectLogo("harbor-app", "md1")
        c.setLogoInsteadOfName("harbor-app", true)
        c.setProjectLogo("harbor-app", null)

        assertEquals(
            listOf("""{"theme":{"logoMediaId":"md1"}}""", """{"theme":{"logoReplacesName":true}}""", """{"theme":{"logoMediaId":null}}"""),
            seen.map { it.second },
        )
    }

    @Test
    fun `pinning and unpinning a project say which`() = runTest {
        val c = client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"harbor-app","pinned":true}}""") }
        c.setProjectPinned("harbor-app", true)
        c.setProjectPinned("harbor-app", false)

        assertEquals(listOf("""{"pinned":true}""", """{"pinned":false}"""), seen.map { it.second })
    }

    @Test
    fun `colours are set and a missing one is cleared`() = runTest {
        client { json("""{"project":{"id":"p9","slug":"harbor-app","name":"harbor-app"}}""") }.setProjectColours("harbor-app", "#0f3d3e", null)

        assertEquals("""{"theme":{"background":"#0f3d3e","accent":null}}""", seen.single().second)
    }
}
