package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.formUrlEncode
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A dashboard server in memory: the snapshot reads answer from the fields below, and every
 * `GET /api/stream` opens a [Connection] the test writes events into and drops at will.
 */
class FakeDashboard(scheduler: TestCoroutineScheduler) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    var projects = listOf(project("p1", "mega", "Mega"), project("p2", "other", "Other"))
    var updates = listOf<UpdateView>()
    var messages = listOf<MessageView>()
    var reactions = listOf<ReactionView>()
    var requests = listOf<RequestView>()
    var agents = listOf<LiveAgentView>()
    var agentNames = mapOf("a1" to "Builder", "a2" to "Scout")

    /** The newest event seq: every read is stamped with it. */
    var seq = 10L

    /** Set to make the stream refuse: 401 signs the device out. */
    var streamStatus: HttpStatusCode = HttpStatusCode.OK

    /** Answers writes. Return null to fall through to the default success. */
    var onWrite: (MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData?)? = null

    val calls = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, String>>()
    val connections = mutableListOf<Connection>()

    class Connection(val lastEventId: String?, val query: String?) {
        val channel = ByteChannel(autoFlush = true)

        suspend fun event(type: String, seq: Long, payload: JsonObject = JsonObject(emptyMap())) {
            val envelope = buildJsonObject {
                put("type", type)
                put("seq", seq)
                put("at", "2026-11-18T10:00:00.000Z")
                put("payload", payload)
            }
            channel.writeStringUtf8("id: $seq\nevent: $type\ndata: $envelope\n\n")
        }

        suspend fun resync(seq: Long) {
            channel.writeStringUtf8("id: $seq\nevent: resync\ndata: {\"type\":\"resync\",\"reason\":\"ahead\",\"from\":99,\"seq\":$seq}\n\n")
        }

        suspend fun heartbeat() = channel.writeStringUtf8(": heartbeat now\n\n")

        /** The server goes away mid-stream. */
        fun drop() {
            channel.close(IllegalStateException("connection reset"))
        }
    }

    val engine = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(scheduler)
        reuseHandlers = true
        addHandler { request -> route(request) }
    })

    fun client(token: String? = "device-key") = DashboardClient("https://dash.test/", token, engine)

    fun count(pathPrefix: String) = calls.count { it.startsWith(pathPrefix) }

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val query = request.url.encodedQuery
        calls += if (query.isEmpty()) path else "$path?$query"
        if (request.method != HttpMethod.Get) {
            val body = request.textOf()
            writes += "${request.method.value} $path" to body
            onWrite?.invoke(this, request, body)?.let { return it }
            return when {
                path == "/api/messages" -> ok("""{"message":${json.encodeToString(MessageView.serializer(), message("m-new", seq, "human", "sent", 1))}}""", HttpStatusCode.Created)
                path.endsWith("/reactions") -> ok("""{"reactions":[],"on":true}""")
                path == "/api/media" -> ok("""{"media":${json.encodeToString(MediaView.serializer(), MediaView("md1", kind = "image", mime = "image/png", status = "pending"))}}""", HttpStatusCode.Created)
                else -> ok("{}")
            }
        }
        val project = request.url.parameters["project"]
        val projectId = projects.firstOrNull { it.slug == project }?.id
        return when (path) {
            "/api/stream" -> {
                if (streamStatus != HttpStatusCode.OK) return ok("""{"error":"unauthenticated"}""", streamStatus)
                val connection = Connection(request.headers["Last-Event-ID"], request.url.parameters["last_event_id"])
                connections += connection
                connection.channel.writeStringUtf8("retry: 2000\n\n: connected\n\n")
                respond(connection.channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            "/api/snapshot" -> ok(json.encodeToString(FeedSnapshot.serializer(), FeedSnapshot(
                seq = seq,
                projects = projects,
                tags = emptyList(),
                cardTags = emptyList(),
                cardFields = emptyList(),
                cardApps = emptyList(),
                updates = UpdatesPage(updates.filter { projectId == null || it.projectId == projectId }.sortedByDescending { it.seq }, null, false),
                agentNames = agentNames,
            )))
            "/api/messages" -> ok(json.encodeToString(MessagesSnapshot.serializer(), MessagesSnapshot(
                seq = seq,
                messages = messages
                    .filter { projectId == null || it.projectId == projectId }
                    .filter { message -> request.url.parameters["update"].let { it == null || message.updateId == it } }
                    .filter { message -> request.url.parameters["request"].let { it == null || message.requestId == it } }
                    .sortedBy { it.createdAt },
                reactions = reactions,
            )))
            "/api/snapshot/requests" -> ok(json.encodeToString(RequestsSnapshot.serializer(), RequestsSnapshot(seq, requests = requests)))
            "/api/snapshot/agents" -> ok(json.encodeToString(AgentsSnapshot.serializer(), AgentsSnapshot(seq, agents = agents)))
            else -> ok("""{"error":"not_found"}""", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    companion object {
        fun project(id: String, slug: String, name: String) = ProjectView(id = id, slug = slug, name = name)

        fun update(id: String, seq: Long, projectId: String = "p1", createdAt: Long = seq * 1000, pinned: Boolean = false, agentId: String = "a1") =
            UpdateView(id = id, seq = seq, projectId = projectId, agentId = agentId, title = "Update $id", body = "Body of $id", pinned = pinned, createdAt = createdAt)

        fun message(
            id: String,
            seq: Long,
            author: String,
            body: String,
            createdAt: Long,
            projectId: String? = "p1",
            updateId: String? = null,
            replyTo: String? = null,
            requestId: String? = null,
        ) = MessageView(id = id, seq = seq, projectId = projectId, updateId = updateId, replyTo = replyTo, requestId = requestId, author = author, body = body, createdAt = createdAt)

        fun request(id: String, seq: Long, projectId: String? = "p1", messageId: String? = null, kind: String = "confirm") =
            RequestView(id = id, seq = seq, agentId = "a1", projectId = projectId, messageId = messageId, kind = kind, question = "May I ship $id?", expiresAt = 9_999_999_999_999)
    }
}

internal fun HttpRequestData.textOf(): String = when (val content = body) {
    is FormDataContent -> content.formData.formUrlEncode()
    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
    is OutgoingContent.NoContent -> ""
    else -> content.toString()
}
