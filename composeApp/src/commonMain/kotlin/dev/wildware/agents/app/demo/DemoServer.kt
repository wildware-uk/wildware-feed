package dev.wildware.agents.app.demo

import dev.wildware.agents.AgentsSnapshot
import dev.wildware.agents.CardFieldView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.CardTagView
import dev.wildware.agents.FeedSnapshot
import dev.wildware.agents.HumanAuthor
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.MessageView
import dev.wildware.agents.MessagesSnapshot
import dev.wildware.agents.NotificationsSnapshot
import dev.wildware.agents.ProjectTheme
import dev.wildware.agents.ProjectView
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestView
import dev.wildware.agents.RequestsSnapshot
import dev.wildware.agents.TagView
import dev.wildware.agents.UpdateView
import dev.wildware.agents.UpdatesPage
import io.ktor.client.engine.HttpClientEngine
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A dashboard that lives in memory, for "Try a demo". It answers the app the way the real one
 * does (the same paths and the same JSON), through a mock network, so the whole app runs on it
 * unchanged and nothing leaves the device.
 *
 * Writes change what it holds: a reply is there on the next read, an answered question is gone,
 * a reaction, pin, tag or field sticks. After each write it tells the app's live stream to read
 * again, as the real dashboard does after a gap. Throw it away to forget everything.
 */
@OptIn(ExperimentalTime::class)
class DemoServer(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val streams = mutableListOf<ByteChannel>()
    private var seq = 100L
    private var ids = 0

    private val data = DemoData(now())
    private var projects = data.projects
    private var updates = data.updates
    private var messages = data.messages
    private var reactions = data.messageReactions
    private var requests = data.requests
    private var tags = data.tags
    private var cardTags = data.cardTags
    private var fields = data.fields

    /** The alerts this "device" asked for, as JSON; null for the defaults. */
    private var alertPrefs: JsonElement = JsonNull

    /** A network engine that never leaves the device. Each session asks for its own. */
    fun engine(): HttpClientEngine = MockEngine { request -> route(request) }

    /** Ends the open live streams, when the demo is left. */
    fun close() {
        streams.forEach { it.cancel(null) }
        streams.clear()
    }

    private suspend fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath.removePrefix("/")
        if (request.method == HttpMethod.Get) return read(path, request)
        val body = request.text()
        val reply = lock.withLock { write(request.method, path, body) }
        // Every open stream hears that things changed and reads again.
        val tell = lock.withLock { streams.toList() to seq }
        tell.first.forEach { channel ->
            runCatching {
                channel.writeStringUtf8("id: ${tell.second}\nevent: resync\ndata: {\"type\":\"resync\",\"reason\":\"ahead\",\"seq\":${tell.second}}\n\n")
            }
        }
        return reply
    }

    private suspend fun MockRequestHandleScope.read(path: String, request: HttpRequestData): HttpResponseData = lock.withLock {
        when (path) {
            "api/stream" -> {
                val channel = ByteChannel(autoFlush = true)
                streams += channel
                channel.writeStringUtf8("retry: 2000\n\n: connected\n\n")
                respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
            "api/snapshot" -> {
                val slug = request.url.parameters["project"]
                val projectId = projects.firstOrNull { it.slug == slug }?.id
                ok(
                    json.encodeToString(
                        FeedSnapshot(
                            seq = seq,
                            projects = projects,
                            tags = tags,
                            cardTags = cardTags,
                            cardFields = fields,
                            cardApps = emptyList(),
                            updates = UpdatesPage(updates.filter { slug == null || it.projectId == projectId }, null, false),
                            agentNames = data.agentNames,
                        ),
                    ),
                )
            }
            "api/snapshot/updates" -> ok(json.encodeToString(UpdatesPage(emptyList(), null, false)))
            "api/messages" -> ok(json.encodeToString(MessagesSnapshot(seq = seq, messages = messages, reactions = reactions)))
            "api/snapshot/requests" -> ok(json.encodeToString(RequestsSnapshot(seq, requests = requests)))
            "api/snapshot/agents" -> ok(json.encodeToString(AgentsSnapshot(seq, agents = data.agents(now()))))
            "api/notifications" -> ok(json.encodeToString(NotificationsSnapshot(seq)))
            "api/devices/current/alert-prefs" -> ok("""{"prefs":$alertPrefs}""")
            else -> ok("""{"error":"not_found","message":"The demo has no $path."}""", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.write(method: HttpMethod, path: String, body: String): HttpResponseData {
        seq++
        val input = runCatching { json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val parts = path.split('/')
        return when {
            path == "api/messages" && method == HttpMethod.Post -> {
                val message = newMessage(input)
                messages = messages + message
                ok("""{"message":${json.encodeToString(message)}}""", HttpStatusCode.Created)
            }
            path.startsWith("api/messages/") && method == HttpMethod.Delete -> {
                val id = parts[2]
                messages = messages.filter { it.id != id && it.replyTo != id }
                ok("{}")
            }
            path.startsWith("api/updates/") && method == HttpMethod.Delete -> {
                val id = parts[2]
                updates = updates.filter { it.id != id }
                messages = messages.filter { it.updateId != id }
                ok("{}")
            }
            path.startsWith("api/requests/") && path.endsWith("/answer") -> {
                requests = requests.filter { it.id != parts[2] }
                ok("{}")
            }
            path.startsWith("api/requests/") && method == HttpMethod.Delete -> {
                requests = requests.filter { it.id != parts[2] }
                ok("{}")
            }
            path == "api/devices/current/alert-prefs" -> {
                alertPrefs = input["prefs"] ?: JsonNull
                ok("""{"prefs":$alertPrefs}""")
            }
            path == "api/projects" && method == HttpMethod.Post -> {
                val name = input.text("name")?.trim().orEmpty()
                if (name.isEmpty()) return bad("A project needs a name.")
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project-${ids + 1}" }
                val existing = projects.firstOrNull { it.slug == slug }
                val project = existing ?: ProjectView(
                    id = "demo-p-${++ids}", slug = slug, name = name,
                    description = input.text("description"), createdAt = now(), updatedAt = now(),
                ).also { projects = projects + it }
                ok("""{"project":${json.encodeToString(project)},"created":${existing == null}}""", if (existing == null) HttpStatusCode.Created else HttpStatusCode.OK)
            }
            path.startsWith("api/projects/") && method == HttpMethod.Patch -> {
                val slug = parts[2]
                val before = projects.firstOrNull { it.slug == slug } ?: return ok("""{"error":"not_found","message":"No such project."}""", HttpStatusCode.NotFound)
                var after = before.copy(updatedAt = now())
                input.text("name")?.let { after = after.copy(name = it) }
                if ("description" in input) after = after.copy(description = input.text("description"))
                input.text("status")?.let { after = after.copy(status = it) }
                (input["theme"] as? JsonObject)?.let { theme ->
                    val was = after.theme ?: ProjectTheme()
                    val next = was.copy(
                        background = if ("background" in theme) theme.text("background") else was.background,
                        accent = if ("accent" in theme) theme.text("accent") else was.accent,
                    )
                    after = after.copy(theme = next.takeIf { it != ProjectTheme() })
                }
                projects = projects.map { if (it.slug == slug) after else it }
                ok("""{"project":${json.encodeToString(after)}}""")
            }
            path == "api/media" -> ok(
                """{"media":{"id":"demo-media-${++ids}","kind":"file","mime":"application/octet-stream","status":"pending"}}""",
                HttpStatusCode.Created,
            )
            path.startsWith("api/cards/") && parts.size >= 5 -> card(CardRef(parts[2], parts[3]), parts[4], input)
            // Device registration, alert settings and the like: nothing to keep in a demo.
            else -> ok("{}")
        }
    }

    private fun MockRequestHandleScope.card(card: CardRef, what: String, input: JsonObject): HttpResponseData = when (what) {
        "reactions" -> {
            val emoji = input.text("emoji") ?: return bad("emoji is required")
            val mine = reactionsOn(card).any { it.isOwner && it.emoji == emoji }
            val on = input["on"]?.jsonPrimitive?.booleanOrNull ?: !mine
            setReaction(card, emoji, on)
            ok("""{"reactions":${json.encodeToString(reactionsOn(card))},"on":$on}""")
        }
        "pin" -> {
            val pinned = input["pinned"]?.jsonPrimitive?.booleanOrNull ?: true
            updates = updates.map { if (card.kind == CardKinds.Update && it.id == card.id) it.copy(pinned = pinned) else it }
            messages = messages.map { if (card.kind == CardKinds.Message && it.id == card.id) it.copy(pinned = pinned) else it }
            ok("{}")
        }
        "status" -> {
            val status = input.text("status")
            messages = messages.map { if (it.id == card.id) it.copy(status = status) else it }
            ok("{}")
        }
        "tags" -> {
            val word = input.text("tag") ?: return bad("tag is required")
            val create = input["create"]?.jsonPrimitive?.booleanOrNull == true
            var tag = tags.firstOrNull { it.id == word || it.name.equals(word, ignoreCase = true) }
            if (tag == null && create) {
                val project = projectOf(card) ?: projects.first().id
                tag = TagView("demo-tag-${++ids}", project, word, word.lowercase().replace(' ', '-'), "grey")
                tags = tags + tag
            }
            if (tag == null) return bad("No such tag.")
            val wearing = cardTags.any { it.card == card && it.tagId == tag.id }
            val on = input["on"]?.jsonPrimitive?.booleanOrNull ?: !wearing
            cardTags = cardTags.filterNot { it.card == card && it.tagId == tag.id } +
                (if (on) listOf(CardTagView("demo-ct-${++ids}", tag.id, card, HumanAuthor, now())) else emptyList())
            ok("""{"on":$on}""")
        }
        "fields" -> {
            val name = input.text("name") ?: return bad("name is required")
            val value = input["value"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            fields = fields.filterNot { it.card == card && it.name == name } +
                (if (value != null) listOf(CardFieldView("demo-f-${++ids}", card, name, name.lowercase(), value, HumanAuthor, now(), now())) else emptyList())
            ok("{}")
        }
        else -> ok("{}")
    }

    private fun newMessage(input: JsonObject): MessageView {
        val update = input.text("update")
        val replyTo = input.text("replyTo")
        val parent = replyTo?.let { id -> messages.firstOrNull { it.id == id } }
        val project = input.text("project")?.let { slug -> projects.firstOrNull { it.slug == slug }?.id }
            ?: update?.let { id -> updates.firstOrNull { it.id == id }?.projectId }
            ?: parent?.projectId
        return MessageView(
            id = "demo-m-${++ids}",
            seq = seq,
            projectId = project,
            updateId = update,
            replyTo = replyTo,
            answers = input.text("answers"),
            author = HumanAuthor,
            body = input.text("body").orEmpty(),
            createdAt = now(),
        )
    }

    private fun reactionsOn(card: CardRef): List<ReactionView> = when (card.kind) {
        CardKinds.Update -> updates.firstOrNull { it.id == card.id }?.reactions.orEmpty()
        else -> reactions.filter { it.messageId == card.id }
    }

    private fun setReaction(card: CardRef, emoji: String, on: Boolean) {
        val others = reactionsOn(card).filterNot { it.isOwner && it.emoji == emoji }
        val now = if (on) others + ReactionView("demo-x-${++ids}", seq, card, card.id.takeIf { card.kind != CardKinds.Update }, HumanAuthor, emoji, now()) else others
        when (card.kind) {
            CardKinds.Update -> updates = updates.map { if (it.id == card.id) it.copy(reactions = now) else it }
            else -> reactions = reactions.filterNot { it.messageId == card.id } + now
        }
    }

    private fun projectOf(card: CardRef): String? = when (card.kind) {
        CardKinds.Update -> updates.firstOrNull { it.id == card.id }?.projectId
        else -> messages.firstOrNull { it.id == card.id }?.projectId
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.bad(message: String) =
        ok("""{"error":"invalid_argument","message":"$message"}""", HttpStatusCode.BadRequest)

    private fun HttpRequestData.text(): String = when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> ""
    }

}
