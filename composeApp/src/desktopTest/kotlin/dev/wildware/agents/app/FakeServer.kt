package dev.wildware.agents.app

import dev.wildware.agents.NotificationsSnapshot
import dev.wildware.agents.NotificationView
import dev.wildware.agents.AgentsSnapshot
import dev.wildware.agents.FeedSnapshot
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.MessageView
import dev.wildware.agents.MessagesSnapshot
import dev.wildware.agents.ProjectView
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestView
import dev.wildware.agents.RequestsSnapshot
import dev.wildware.agents.UpdateView
import dev.wildware.agents.UpdatesPage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlinx.coroutines.delay

/**
 * A dashboard in memory, answering the app through ktor's [MockEngine]: sign-in, the snapshot
 * reads, a live stream that stays open, and every write recorded for the test to check.
 */
class FakeServer {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    var password = "right"
    var projects = listOf(ProjectView(id = "p1", slug = "mega", name = "Mega"), ProjectView(id = "p2", slug = "other", name = "Other"))
    var agentNames = mapOf("a1" to "forge", "a2" to "scout")
    var updates = listOf(
        UpdateView(
            id = "u1", seq = 5, projectId = "p1", agentId = "a1",
            title = "Fixed the unread counts", body = "Counts are **per connection** now.",
            level = "success", createdAt = now() - 60_000,
        ),
    )
    var messages = listOf(
        MessageView(id = "m1", seq = 6, projectId = "p1", updateId = "u1", author = "agent:a2", body = "Confirmed on my side.", createdAt = now() - 30_000),
        MessageView(id = "m-post", seq = 7, projectId = "p1", author = "human", body = "Why is the Mac build slow?", createdAt = now() - 20_000),
    )
    var reactions = listOf<ReactionView>()
    var requests = listOf(
        RequestView(
            id = "r1", seq = 8, agentId = "a1", projectId = "p1", kind = "buttons",
            question = "Ship the new sounds?", options = listOf("Ship it", "Not now"), expiresAt = now() + 3_600_000,
        ),
    )
    var agents = listOf(
        LiveAgentView(agentId = "a1", name = "forge", lastHeartbeatAt = now(), host = "build-box", cwd = "/work/garden-game", model = "claude-opus-5-5"),
    )
    var notifications = listOf<NotificationView>()
    /** This device's alert choice, as `{"prefs":…}` JSON. */
    var alertPrefs = """{"prefs":null}"""
    var seq = 10L

    /** Make every write fail, as when the server is down: the outbox keeps them waiting. */
    @Volatile var failWrites = false

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val writes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val streams = Collections.synchronizedList(mutableListOf<ByteChannel>())

    fun engine() = MockEngine { request -> route(request) }

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    /** Holds back the comments, so cards show before their replies, as on a slow line. */
    @Volatile
    var messagesDelayMs = 0L

    fun wrote(method: String, path: String): String? = writes.lastOrNull { it.first == "$method $path" }?.second

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        calls += path + request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
        if (path == "/api/devices/sign-in") return signIn(request)
        if (path == "/api/messages" && request.method == HttpMethod.Get && messagesDelayMs > 0) delay(messagesDelayMs)
        authorizations += request.headers[HttpHeaders.Authorization]
        if (request.method != HttpMethod.Get) {
            val body = request.text()
            writes += "${request.method.value} $path" to body
            if (failWrites) return ok("""{"error":"unavailable"}""", HttpStatusCode.ServiceUnavailable)
            return when {
                path == "/api/messages" -> ok("""{"message":{"id":"m-new","author":"human","body":"sent","createdAt":1}}""", HttpStatusCode.Created)
                path.endsWith("/reactions") -> ok("""{"reactions":[],"on":true}""")
                path == "/api/devices/current/alert-prefs" -> {
                    alertPrefs = body.replace("\"prefs\"", "\"ok\":true,\"prefs\"")
                    ok(alertPrefs)
                }
                path == "/api/media" -> ok("""{"media":{"id":"md-up","kind":"image","mime":"image/png","status":"pending"}}""", HttpStatusCode.Created)
                else -> ok("{}")
            }
        }
        return when (path) {
            "/api/stream" -> {
                val channel = ByteChannel(autoFlush = true)
                streams += channel
                channel.writeStringUtf8("retry: 2000\n\n: connected\n\n")
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            "/api/snapshot" -> {
                val slug = request.url.parameters["project"]
                val projectId = projects.firstOrNull { it.slug == slug }?.id
                ok(json.encodeToString(FeedSnapshot.serializer(), FeedSnapshot(
                    seq = seq,
                    projects = projects,
                    tags = emptyList(), cardTags = emptyList(), cardFields = emptyList(), cardApps = emptyList(),
                    updates = UpdatesPage(updates.filter { slug == null || it.projectId == projectId }, null, false),
                    agentNames = agentNames,
                )))
            }
            "/api/messages" -> ok(json.encodeToString(MessagesSnapshot.serializer(), MessagesSnapshot(seq = seq, messages = messages, reactions = reactions)))
            "/api/snapshot/requests" -> ok(json.encodeToString(RequestsSnapshot.serializer(), RequestsSnapshot(seq, requests = requests)))
            "/api/devices/current/alert-prefs" -> ok(alertPrefs)
            "/api/snapshot/agents" -> ok(json.encodeToString(AgentsSnapshot.serializer(), AgentsSnapshot(seq, agents = agents)))
            "/api/notifications" -> ok(json.encodeToString(NotificationsSnapshot.serializer(), NotificationsSnapshot(
                seq, notifications = notifications, unseen = notifications.count { it.seenAt == null },
            )))
            else -> ok("""{"error":"not_found"}""", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.signIn(request: HttpRequestData): HttpResponseData {
        val body = request.text()
        writes += "POST /api/devices/sign-in" to body
        return if ("\"password\":\"$password\"" in body) {
            ok("""{"device":{"id":"d1","name":"Test device","createdAt":"2026-11-18T10:00:00Z"},"token":"key-1"}""", HttpStatusCode.Created)
        } else {
            ok("""{"error":"wrong_password","message":"That password is not right."}""", HttpStatusCode.Unauthorized)
        }
    }

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.text(): String = when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> ""
    }

    fun close() = streams.forEach { it.cancel(null) }

    companion object {
        fun now() = System.currentTimeMillis()
    }
}
