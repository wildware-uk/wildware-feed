package dev.wildware.agents.app

import dev.wildware.agents.MediaView
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
    /** Projects (groups of channels), as the snapshot lists them. */
    var groups = listOf<dev.wildware.agents.GroupView>()
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
    /** Media on messages, by message id, as `GET /api/messages` sends it. */
    var messageMedia = mapOf<String, List<MediaView>>()
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
    /** Questions answered lately, as a team's `GET /api/snapshot/requests` lists them (migration 055). */
    var answered = listOf<RequestView>()
    /** `GET /api/me` and `GET /api/people`; null answers 404, a server from before teams. */
    var me: String? = null
    var people: String = """{"people":[]}"""
    /** Agents asking to join, as `GET /api/pairings` lists them. */
    var pairings = listOf<dev.wildware.agents.PairingView>()
    /** When set, Allow is refused with this error code, as a taken name is (409). */
    @Volatile var refuseApprove: String? = null
    /** This device's alert choice, as `{"prefs":…}` JSON. */
    var alertPrefs = """{"prefs":null}"""
    /** Every agent, as `GET /api/agents` answers: id to (name, removed). DELETE /api/agents/{id} removes one. */
    val agentRecords: MutableMap<String, Pair<String, Boolean>> = Collections.synchronizedMap(linkedMapOf(
        "a1" to ("forge" to false), "a9" to ("old-bot" to false), "a8" to ("gone-bot" to true),
    ))

    /** Agent a1's OAuth connections, as `GET /api/agents/a1/connections` answers. */
    var connections = """{"connections":[{"id":"g1","clientName":"Cursor","redirectOrigin":"cursor:","createdAt":"2026-11-18T10:00:00Z","lastUsedAt":"2026-11-19T09:00:00Z"}]}"""

    /** The devices signed in as the owner, as `GET /api/devices` answers. */
    var devices = """{"devices":[{"id":"d1","name":"Test device","createdAt":"2026-11-18T10:00:00Z","lastSeenAt":null},{"id":"d2","name":"Old phone","createdAt":"2026-10-01T10:00:00Z","lastSeenAt":"2026-11-01T09:00:00Z"}]}"""
    /** How long the dashboard keeps media, as `GET /api/settings/media-retention` answers; null refuses with a 403. */
    var mediaRetention: String? = """{"days":null,"kinds":["image","video","audio","file"]}"""
    /** When set, a retention save is refused with this message, as the dashboard words a 400. */
    @Volatile var refuseRetention: String? = null
    /** The dashboard's secrets, as `GET /api/secrets` answers; null answers 404, as a dashboard from before secrets. */
    var secrets: String? = """{"enabled":true,"secrets":[{"id":"s1","name":"OPENAI_API_KEY","note":"For the test suite","allAgents":false,"agents":[{"id":"a1","name":"forge"}],"bytes":24,"createdBy":"owner","createdAt":"2026-11-18T10:00:00Z","updatedAt":"2026-11-18T10:00:00Z","lastUsedAt":"2026-11-19T09:00:00Z"}]}"""
    /** What a reveal answers. A made-up value: never a real secret. */
    var revealed = """{"value":"sk-test-NOT-REAL-0000","encoding":"utf8"}"""
    var seq = 10L

    /** Whether the first page says there are older updates. Older pages (`GET /api/snapshot/updates`) come back empty and say there are no more. */
    var hasMore = false

    /** `GET /api/threads/{id}` answers, by the id asked for, as JSON; any other id is a 404. */
    val threads: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    /** `GET /api/summary` answers 401, as a dashboard that signed this device out does. */
    @Volatile var summaryRefused = false

    /** Make every write fail, as when the server is down: the outbox keeps them waiting. */
    @Volatile var failWrites = false

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val writes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val streams = Collections.synchronizedList(mutableListOf<ByteChannel>())

    fun engine() = MockEngine { request -> otherHost?.invoke(this, request) ?: route(request) }

    /** Answers first, for another host: the hosted account service. Null lets this server answer. */
    @Volatile
    var otherHost: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null

    /** Sign-in tickets this server will take, each once (the hosted "Log in"). */
    val tickets: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // A synchronized list is only safe to walk while holding its lock: the app keeps calling meanwhile.
    fun count(prefix: String) = synchronized(calls) { calls.count { it.startsWith(prefix) } }

    /** Holds back the comments, so cards show before their replies, as on a slow line. */
    @Volatile
    var messagesDelayMs = 0L

    fun wrote(method: String, path: String): String? = synchronized(writes) { writes.lastOrNull { it.first == "$method $path" }?.second }

    /** Answers [request] as this server: other fakes call it to stand behind another address. */
    internal suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        calls += path + request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
        if (path == "/api/devices/sign-in") return signIn(request)
        if (path == "/api/devices/ticket") {
            val body = request.text()
            writes += "POST /api/devices/ticket" to body
            val ticket = Regex("\"ticket\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return if (ticket != null && tickets.remove(ticket)) {
                ok("""{"device":{"id":"d1","name":"Test device","createdAt":"2026-11-18T10:00:00Z"},"token":"key-1"}""", HttpStatusCode.Created)
            } else {
                ok("""{"error":"invalid_ticket","message":"That sign-in has expired or was used already. Sign in again."}""", HttpStatusCode.Unauthorized)
            }
        }
        // Public, and asked before any key exists: "Connect to a host", step 1.
        if (path == "/api/server") return ok("""{"product":"wildware-feed","version":"dev","signIn":["password"],"team":false}""")
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
                path == "/api/settings/media-retention" -> refuseRetention?.let { ok("""{"error":"invalid_argument","message":"$it"}""", HttpStatusCode.BadRequest) }
                    ?: ok(body.also { mediaRetention = it })
                // Drags in the channel list: kept, so a reload shows them where they were dropped.
                path == "/api/projects/order" -> {
                    val order = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
                    val ids = (order["ids"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    val moveTo = order["groupId"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    projects = projects.map { p ->
                        val at = ids.indexOf(p.slug)
                        if (at < 0) p else p.copy(position = at.toLong(), groupId = if ("groupId" in order) moveTo else p.groupId)
                    }.sortedWith(compareBy({ it.position != null }, { it.position ?: 0L }))
                    ok("{}")
                }
                path == "/api/groups/order" -> {
                    val ids = ((json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["ids"] as kotlinx.serialization.json.JsonArray)
                        .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    groups = groups.map { g -> ids.indexOf(g.slug).let { at -> if (at < 0) g else g.copy(position = at.toLong()) } }
                    ok("{}")
                }
                path == "/api/projects" -> ok("""{"project":{"id":"p-new","slug":"harbor-app","name":"harbor-app"},"created":true}""", HttpStatusCode.Created)
                path.startsWith("/api/projects/") -> ok("""{"project":{"id":"p1","slug":"${path.substringAfterLast('/')}","name":"x"}}""")
                path == "/api/secrets" -> ok("""{"secret":{"id":"s2","name":"NEW","createdAt":"2026-11-19T10:00:00Z","updatedAt":"2026-11-19T10:00:00Z"}}""", HttpStatusCode.Created)
                path.startsWith("/api/secrets/") && path.endsWith("/reveal") -> ok(revealed)
                path.startsWith("/api/secrets/") -> ok("""{"secret":{"id":"s1","name":"OPENAI_API_KEY","createdAt":"2026-11-18T10:00:00Z","updatedAt":"2026-11-19T10:00:00Z"}}""")
                path == "/api/media" -> ok("""{"media":{"id":"md-up","kind":"image","mime":"image/png","status":"pending"}}""", HttpStatusCode.Created)
                request.method == HttpMethod.Delete && Regex("^/api/agents/[^/]+$").matches(path) -> {
                    val id = path.substringAfterLast('/')
                    agentRecords[id]?.let { agentRecords[id] = it.first to true }
                    ok("""{"revoked":true}""")
                }
                path.startsWith("/api/pairings/") && path.endsWith("/approve") -> refuseApprove?.let {
                    ok("""{"error":"$it","message":"an agent is already called \"forge\"; choose another name"}""", HttpStatusCode.Conflict)
                } ?: ok("{}")
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
                    groups = groups,
                    tags = emptyList(), cardTags = emptyList(), cardFields = emptyList(), cardApps = emptyList(),
                    updates = UpdatesPage(updates.filter { slug == null || it.projectId == projectId }, if (hasMore) "1" else null, hasMore),
                    agentNames = agentNames,
                )))
            }
            "/api/snapshot/updates" -> ok(json.encodeToString(FeedSnapshot.serializer(), FeedSnapshot(seq = seq, updates = UpdatesPage(emptyList(), null, false))))
            // The small summary a team that isn't open is asked for: the questions waiting there.
            "/api/summary" -> if (summaryRefused) ok("""{"error":"unauthenticated","message":"Sign in again."}""", HttpStatusCode.Unauthorized)
            else ok(json.encodeToString(dev.wildware.agents.DashboardSummary.serializer(), dev.wildware.agents.DashboardSummary(
                at = "2026-11-21T10:00:00Z",
                onlineAgents = emptyList(),
                requests = requests.map { r ->
                    val project = projects.firstOrNull { it.id == r.projectId }
                    dev.wildware.agents.PendingRequest(
                        id = r.id, agentName = agentNames[r.agentId] ?: "agent", projectSlug = project?.slug, projectName = project?.name,
                        question = r.question, kind = dev.wildware.agents.RequestKind.Buttons, options = r.options, expiresAt = "2026-11-22T10:00:00Z",
                    )
                },
                work = dev.wildware.agents.WorkCounts(0, 0, 0),
                projects = projects.map { dev.wildware.agents.ProjectRef(it.slug, it.name) },
            )))
            "/api/messages" -> ok(json.encodeToString(MessagesSnapshot.serializer(), MessagesSnapshot(seq = seq, messages = messages, reactions = reactions, media = messageMedia)))
            "/api/snapshot/requests" -> ok(json.encodeToString(RequestsSnapshot.serializer(), RequestsSnapshot(seq, requests = requests, answered = answered)))
            "/api/me" -> me?.let { ok(it) } ?: ok("""{"error":"not_found"}""", HttpStatusCode.NotFound)
            "/api/people" -> ok(people)
            "/api/invites" -> ok("""{"invites":[]}""")
            "/api/devices/current/alert-prefs" -> ok(alertPrefs)
            "/api/devices" -> ok(devices)
            "/api/agents/a1/connections" -> ok(connections)
            "/api/agents" -> ok(agentRecords.entries.joinToString(",", """{"agents":[""", "]}") { (id, v) ->
                """{"id":"$id","name":"${v.first}","createdAt":"2026-11-01T10:00:00Z","lastSeenAt":null,"revokedAt":${if (v.second) "\"2026-11-18T10:00:00Z\"" else "null"}}"""
            })
            "/api/secrets" -> secrets?.let { ok(it) } ?: ok("""{"error":"not_found"}""", HttpStatusCode.NotFound)
            "/api/secrets/audit" -> ok("""{"entries":[{"id":"e1","secretId":"s1","secretName":"OPENAI_API_KEY","action":"downloaded","actor":{"kind":"agent","id":"a1","name":"forge"},"detail":"from 10.0.0.2 (curl/8.5.0)","at":"2026-11-19T09:00:00Z"}]}""")
            "/api/settings/media-retention" -> mediaRetention?.let { ok(it) } ?: ok("""{"error":"forbidden","message":"Only the owner can change this."}""", HttpStatusCode.Forbidden)
            "/api/pairings" -> ok(json.encodeToString(dev.wildware.agents.PairingsSnapshot.serializer(), dev.wildware.agents.PairingsSnapshot(seq, pairings = pairings)))
            "/api/snapshot/agents" -> ok(json.encodeToString(AgentsSnapshot.serializer(), AgentsSnapshot(seq, agents = agents)))
            "/api/notifications" -> ok(json.encodeToString(NotificationsSnapshot.serializer(), NotificationsSnapshot(
                seq, notifications = notifications, unseen = notifications.count { it.seenAt == null },
            )))
            else -> threads[path.removePrefix("/api/threads/")]?.takeIf { path.startsWith("/api/threads/") }?.let { ok(it) }
                ?: ok("""{"error":"not_found"}""", HttpStatusCode.NotFound)
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
