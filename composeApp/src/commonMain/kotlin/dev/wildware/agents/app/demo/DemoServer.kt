package dev.wildware.agents.app.demo

import dev.wildware.agents.AgentsSnapshot
import dev.wildware.agents.CardFieldView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.CardTagView
import dev.wildware.agents.FeedSnapshot
import dev.wildware.agents.FollowsSnapshot
import dev.wildware.agents.ProjectNotifyView
import dev.wildware.agents.ThreadFollowView
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
    private var groups = data.groups
    private var groupLevels = mapOf<String, String>()
    private var updates = data.updates
    private var messages = data.messages
    private var reactions = data.messageReactions
    private var requests = data.requests
    private var tags = data.tags
    private var cardTags = data.cardTags
    private var fields = data.fields

    /** The alerts this "device" asked for, as JSON; null for the defaults. */
    private var alertPrefs: JsonElement = JsonNull

    /** What the owner chose to follow: a level per project id, and threads by (kind, id). */
    private var levels = mapOf<String, String>()
    private var followed = mapOf<Pair<String, String>, Boolean>()

    /** Agents the owner renamed in the demo, by id. */
    private var renamed = mapOf<String, String>()

    /** Agents the owner removed, by ("channel:slug" or "group:slug", agent id). Every other agent hears everything. */
    private var removedAgents = setOf<Pair<String, String>>()

    /** Which agents hear a channel or project, as `GET /api/{projects|groups}/{slug}/agents` answers. */
    private fun members(kind: String, slug: String): List<dev.wildware.agents.AgentMember> {
        val target = (if (kind == "groups") "group:" else "channel:") + slug
        val groupSlug = if (kind == "projects") projects.firstOrNull { it.slug == slug }?.groupId?.let { id -> groups.firstOrNull { it.id == id }?.slug } else null
        return (data.agentNames + renamed).entries.sortedBy { it.value.lowercase() }.map { (id, name) ->
            val removed = when {
                (target to id) in removedAgents -> if (kind == "groups") "group" else "channel"
                groupSlug != null && ("group:$groupSlug" to id) in removedAgents -> "group"
                else -> null
            }
            dev.wildware.agents.AgentMember(id, name, hears = removed == null, via = if (removed == null) "all" else null, removed = removed)
        }
    }

    /** Levels set in the demo's Permissions panels, by ("channel:<ref>" or "group:<ref>", principal), and which are private. */
    private var accessLevels = mapOf<Pair<String, String>, String>()
    private var privateOnes = setOf<String>()

    /** A channel's or project's Permissions panel, as `GET /api/{projects|groups}/{ref}/access` answers (server spec/15). */
    private fun accessPanel(kind: String, ref: String): dev.wildware.agents.AccessPanel {
        val target = (if (kind == "groups") "group:" else "channel:") + ref
        val agents = (data.agentNames + renamed).entries.sortedBy { it.value.lowercase() }.map { (id, name) ->
            val own = accessLevels[target to "agent:$id"]
            dev.wildware.agents.AccessEntry("agent:$id", "agent", id, name, level = own ?: if (target in privateOnes) "none" else "post", via = when {
                own != null -> if (kind == "groups") "group" else "channel"
                target in privateOnes -> "private"
                else -> "default"
            }, own = own)
        }
        val you = dev.wildware.agents.AccessEntry("member:founder", "member", "founder", "You", role = "owner", level = "manage", via = "owner", editable = false)
        return dev.wildware.agents.AccessPanel(
            projectId = ref.takeIf { kind == "projects" }, groupId = ref.takeIf { kind == "groups" },
            isPrivate = target in privateOnes, yourLevel = "manage", canManage = true, entries = listOf(you) + agents,
        )
    }

    /** `api/{projects|groups}/{ref}/access[/{kind}/{id}]`, as (projects|groups, ref, principal or null). */
    private fun accessPath(path: String): Triple<String, String, String?>? {
        val parts = path.split('/')
        if (parts.size < 4 || parts[0] != "api" || parts[3] != "access" || (parts[1] != "projects" && parts[1] != "groups")) return null
        return when (parts.size) {
            4 -> Triple(parts[1], parts[2], null)
            6 -> Triple(parts[1], parts[2], "${parts[4]}:${parts[5]}")
            else -> null
        }
    }

    /** A network engine that never leaves the device. Each session asks for its own. */
    fun engine(): HttpClientEngine = MockEngine { request -> route(request) }

    /** Ends the open live streams, when the demo is left. */
    fun close() {
        streams.forEach { it.cancel(null) }
        streams.clear()
    }

    /** `api/projects/{slug}/agents` or `api/groups/{slug}/agents`, as (projects|groups, slug). */
    private fun agentsPath(path: String): Pair<String, String>? {
        val parts = path.split('/')
        if (parts.size != 4 || parts[0] != "api" || parts[3] != "agents" || (parts[1] != "projects" && parts[1] != "groups")) return null
        return parts[1] to parts[2]
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
                            groups = groups,
                            tags = tags,
                            cardTags = cardTags,
                            cardFields = fields,
                            cardApps = emptyList(),
                            updates = UpdatesPage(updates.filter { slug == null || it.projectId == projectId }, null, false),
                            agentNames = data.agentNames + renamed,
                        ),
                    ),
                )
            }
            "api/snapshot/updates" -> ok(json.encodeToString(UpdatesPage(emptyList(), null, false)))
            "api/messages" -> ok(json.encodeToString(MessagesSnapshot(seq = seq, messages = messages, reactions = reactions)))
            "api/snapshot/requests" -> ok(json.encodeToString(RequestsSnapshot(seq, requests = requests)))
            "api/snapshot/agents" -> ok(json.encodeToString(AgentsSnapshot(seq, agents = data.agents(now()).map { a -> renamed[a.agentId]?.let { a.copy(name = it) } ?: a })))
            "api/notifications" -> ok(json.encodeToString(NotificationsSnapshot(seq)))
            "api/subscriptions" -> ok(
                json.encodeToString(
                    FollowsSnapshot(
                        seq,
                        projects = levels.map { (id, level) -> ProjectNotifyView(id, level) },
                        threads = followed.map { (key, on) -> ThreadFollowView(key.first, key.second, on) },
                        groups = groupLevels.map { (id, level) -> dev.wildware.agents.GroupNotifyView(id, level) },
                    ),
                ),
            )
            "api/devices/current/alert-prefs" -> ok("""{"prefs":$alertPrefs}""")
            "api/board" -> ok(json.encodeToString(boardOf(request)))
            "api/settings/board" -> ok("""{"autoRemoveDays":${boardDays ?: "null"}}""")
            else -> Regex("^api/cards/([a-z]+)/([^/]+)/assignees$").find(path)?.let { m ->
                val card = CardRef(m.groupValues[1], m.groupValues[2])
                ok(json.encodeToString(dev.wildware.agents.CardAssignees(card, assignedTo(card.id), assignable())))
            } ?: agentsPath(path)?.let { (kind, slug) ->
                ok("""{"agents":${json.encodeToString(members(kind, slug))}}""")
            } ?: accessPath(path)?.takeIf { it.third == null }?.let { (kind, ref) ->
                ok(json.encodeToString(accessPanel(kind, ref)))
            } ?: Regex("^api/(people|agents)/[^/]+/access$").find(path)?.let {
                // Everyone in the demo reaches every channel, posting.
                val rows = projects.map { p -> dev.wildware.agents.ReachRow(p.id, p.slug, p.name, p.groupId, "post", "default") }
                ok("""{"channels":${json.encodeToString(rows)}}""")
            } ?: ok("""{"error":"not_found","message":"The demo has no $path."}""", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.write(method: HttpMethod, path: String, body: String): HttpResponseData {
        seq++
        val input = runCatching { json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val parts = path.split('/')
        accessPath(path)?.takeIf { method == HttpMethod.Put }?.let { (kind, ref, principal) ->
            val target = (if (kind == "groups") "group:" else "channel:") + ref
            if (principal == null) {
                val on = input["private"]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() } == true
                privateOnes = if (on) privateOnes + target else privateOnes - target
            } else {
                val level = input["level"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
                accessLevels = if (level == null) accessLevels - (target to principal) else accessLevels + ((target to principal) to level)
            }
            return ok(json.encodeToString(accessPanel(kind, ref)))
        }
        if (path == "api/settings/board") {
            boardDays = input["autoRemoveDays"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
            return ok("""{"autoRemoveDays":${boardDays ?: "null"}}""")
        }
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
            path.startsWith("api/cards/") && path.endsWith("/follow") && method == HttpMethod.Put -> {
                followed = followed + ((parts[2] to parts[3]) to (input["following"]?.jsonPrimitive?.booleanOrNull == true))
                ok("{}")
            }
            path.startsWith("api/projects/") && path.endsWith("/subscription") && method == HttpMethod.Put -> {
                val project = projects.firstOrNull { it.slug == parts[2] } ?: return ok("{\"error\":\"not_found\",\"message\":\"No such project.\"}", HttpStatusCode.NotFound)
                val level = input.text("level")
                levels = if (level == null) levels - project.id else levels + (project.id to level)
                ok("{}")
            }
            parts.size == 5 && parts[0] == "api" && (parts[1] == "projects" || parts[1] == "groups") && parts[3] == "agents" && method == HttpMethod.Put -> {
                val (kind, slug, id) = Triple(parts[1], parts[2], parts[4])
                val hears = input["hears"]?.jsonPrimitive?.booleanOrNull ?: return bad("hears must be true or false")
                val key = (if (kind == "groups") "group:" else "channel:") + slug
                removedAgents = if (hears) removedAgents - (key to id) else removedAgents + (key to id)
                val member = members(kind, slug).firstOrNull { it.agentId == id } ?: return bad("No such agent.")
                ok("""{"agent":${json.encodeToString(member)}}""")
            }
            path.startsWith("api/groups/") && path.endsWith("/subscription") && method == HttpMethod.Put -> {
                val group = groups.firstOrNull { it.slug == parts[2] || it.id == parts[2] } ?: return bad("No such project.")
                val level = input.text("level")
                groupLevels = if (level == null) groupLevels - group.id else groupLevels + (group.id to level)
                ok("{}")
            }
            // Drags in the channel list (migration 069): each named one at its index, in that order.
            path == "api/projects/order" && method == HttpMethod.Put -> {
                val ids = (input["ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return bad("ids must be a list")
                val moveTo = if ("groupId" in input) input.text("groupId")?.let { ref -> groups.firstOrNull { it.id == ref || it.slug == ref }?.id } else null
                projects = projects.map { p ->
                    val at = ids.indexOfFirst { it == p.slug || it == p.id }
                    if (at < 0) p else p.copy(position = at.toLong(), groupId = if ("groupId" in input) moveTo else p.groupId)
                }.sortedWith(compareBy({ it.position != null }, { it.position ?: 0L }))
                ok("{}")
            }
            path == "api/groups/order" && method == HttpMethod.Put -> {
                val ids = (input["ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return bad("ids must be a list")
                groups = groups.map { g -> ids.indexOfFirst { it == g.slug || it == g.id }.let { at -> if (at < 0) g else g.copy(position = at.toLong()) } }
                ok("{}")
            }
            path == "api/groups" && method == HttpMethod.Post -> {
                val name = input.text("name")?.trim().orEmpty()
                if (name.isEmpty()) return bad("A project needs a name.")
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "group-${ids + 1}" }
                val existing = groups.firstOrNull { it.slug == slug }
                val group = existing ?: dev.wildware.agents.GroupView(id = "demo-g-${++ids}", slug = slug, name = name, createdAt = now(), updatedAt = now())
                    .also { made -> groups = (groups + made).sortedBy { it.name.lowercase() } }
                ok("""{"group":${json.encodeToString(group)},"created":${existing == null}}""", if (existing == null) HttpStatusCode.Created else HttpStatusCode.OK)
            }
            path.startsWith("api/groups/") && method == HttpMethod.Delete -> {
                val group = groups.firstOrNull { it.slug == parts[2] || it.id == parts[2] } ?: return bad("No such project.")
                groups = groups - group
                projects = projects.map { if (it.groupId == group.id) it.copy(groupId = null) else it }
                ok("""{"deleted":true,"groupId":"${group.id}"}""")
            }
            path.startsWith("api/groups/") && method == HttpMethod.Patch -> {
                val before = groups.firstOrNull { it.slug == parts[2] || it.id == parts[2] } ?: return bad("No such project.")
                var after = before.copy(updatedAt = now())
                input.text("name")?.let { after = after.copy(name = it) }
                input.text("status")?.let { after = after.copy(status = it) }
                (input["theme"] as? JsonObject)?.let { theme ->
                    if ("accent" in theme) after = after.copy(theme = theme.text("accent")?.let { ProjectTheme(accent = it) })
                }
                groups = groups.map { if (it.id == before.id) after else it }.sortedBy { it.name.lowercase() }
                ok("""{"group":${json.encodeToString(after)}}""")
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
            path.startsWith("api/agents/") && method == HttpMethod.Patch -> {
                val id = parts[2]
                val name = input.text("name")?.trim().orEmpty()
                if (name.isEmpty()) return ok("""{"error":"invalid_argument","message":"An agent needs a name."}""", HttpStatusCode.BadRequest)
                renamed = renamed + (id to name)
                ok("""{"agent":{"id":"$id","name":${json.encodeToString(name)}}}""")
            }
            path.startsWith("api/projects/") && method == HttpMethod.Patch -> {
                val slug = parts[2]
                val before = projects.firstOrNull { it.slug == slug } ?: return ok("""{"error":"not_found","message":"No such project."}""", HttpStatusCode.NotFound)
                var after = before.copy(updatedAt = now())
                input.text("name")?.let { after = after.copy(name = it) }
                if ("description" in input) after = after.copy(description = input.text("description"))
                input.text("status")?.let { after = after.copy(status = it) }
                input["pinned"]?.jsonPrimitive?.booleanOrNull?.let { after = after.copy(pinned = it) }
                if ("groupId" in input) after = after.copy(groupId = input.text("groupId")?.let { ref -> groups.firstOrNull { it.id == ref || it.slug == ref }?.id })
                (input["theme"] as? JsonObject)?.let { theme ->
                    val was = after.theme ?: ProjectTheme()
                    val logo = if ("logoMediaId" in theme) theme.text("logoMediaId") else was.logoMediaId
                    val instead = if ("logoReplacesName" in theme) theme["logoReplacesName"]?.jsonPrimitive?.booleanOrNull else was.logoReplacesName
                    val next = was.copy(
                        background = if ("background" in theme) theme.text("background") else was.background,
                        accent = if ("accent" in theme) theme.text("accent") else was.accent,
                        logoMediaId = logo,
                        // No logo, nothing to show instead of the name.
                        logoReplacesName = instead?.takeIf { it && logo != null },
                    )
                    after = after.copy(theme = next.takeIf { it != ProjectTheme() })
                }
                // Pinned first, as the dashboard lists them.
                projects = projects.map { if (it.slug == slug) after else it }.sortedByDescending { it.pinned }
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
            messages = messages.map { if (it.id == card.id) it.copy(status = status, statusAt = now()) else it }
            updates = updates.map { if (it.id == card.id) it.copy(status = status, statusAt = now()) else it }
            boardRemoved = boardRemoved - card.id
            ok("{}")
        }
        "assignees" -> {
            val wanted = (input["assignees"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val everyone = assignable()
            assigned = assigned + (card.id to wanted.mapNotNull { w -> everyone.firstOrNull { it.principal == w || it.name.equals(w, ignoreCase = true) }?.principal }.distinct().take(5))
            if (card.kind == CardKinds.Message) messages = messages.map { if (it.id == card.id && it.status == null) it.copy(status = "todo", statusAt = now()) else it }
            if (card.kind == CardKinds.Update) updates = updates.map { if (it.id == card.id && it.status == null) it.copy(status = "todo", statusAt = now()) else it }
            ok("""{"assignees":${json.encodeToString(assignedTo(card.id))}}""")
        }
        "board" -> {
            val removed = input["removed"]?.jsonPrimitive?.booleanOrNull ?: true
            boardRemoved = if (removed) boardRemoved + card.id else boardRemoved - card.id
            ok("""{"removed":$removed}""")
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

    /** Who each card is assigned to, by card id: principals, oldest first. */
    private var assigned = mapOf<String, List<String>>()

    /** Everyone the demo can assign: you, then its agents. */
    private fun assignable(): List<dev.wildware.agents.AssigneeView> =
        listOf(dev.wildware.agents.AssigneeView("member:founder", "person", "founder", "You", "you")) +
            data.agents(now()).map { a -> dev.wildware.agents.AssigneeView("agent:${a.agentId}", "agent", a.agentId, renamed[a.agentId] ?: a.name) }

    private fun assignedTo(cardId: String): List<dev.wildware.agents.AssigneeView> {
        val everyone = assignable().associateBy { it.principal }
        return assigned[cardId].orEmpty().mapNotNull { everyone[it] }
    }

    /** The board's finished cards taken off it, and the demo's auto-remove age (it never removes by age). */
    private var boardRemoved = setOf<String>()
    private var boardDays: Int? = 7

    /** The board, from the demo's cards with a work state, as the dashboard's `GET /api/board` gives it. */
    private fun boardOf(request: HttpRequestData): dev.wildware.agents.BoardSnapshot {
        val channel = request.url.parameters["channel"]?.let { slug -> projects.firstOrNull { it.slug == slug }?.id }
        val group = request.url.parameters["group"]?.let { slug -> groups.firstOrNull { it.slug == slug }?.id }
        val showRemoved = request.url.parameters["removed"] == "1"
        val only = request.url.parameters["assigned"]?.let { if (it == "me") "member:founder" else it }
        fun inScope(projectId: String?) = when {
            channel != null -> projectId == channel
            group != null -> projects.any { it.id == projectId && it.groupId == group }
            else -> true
        }
        val names = data.agentNames + renamed
        val cards = updates.filter { it.status != null && inScope(it.projectId) }.map { u ->
            dev.wildware.agents.BoardCardView(
                card = CardRef(CardKinds.Update, u.id), projectId = u.projectId,
                channel = projects.firstOrNull { it.id == u.projectId }.let { dev.wildware.agents.BoardChannel(it?.slug, it?.name) },
                status = u.status!!, statusAt = u.statusAt, createdAt = u.createdAt, movedAt = u.statusAt ?: u.createdAt,
                title = u.title?.takeIf { it.isNotBlank() } ?: u.body.lineSequence().first(), author = "agent:${u.agentId}",
                on = listOf(dev.wildware.agents.BoardWho("agent", u.agentId, names[u.agentId] ?: "An agent")),
                removed = u.id in boardRemoved,
                assignees = assignedTo(u.id),
            )
        } + messages.filter { it.status != null && inScope(it.projectId) }.map { m ->
            dev.wildware.agents.BoardCardView(
                card = CardRef(CardKinds.Message, m.id), projectId = m.projectId,
                channel = projects.firstOrNull { it.id == m.projectId }.let { dev.wildware.agents.BoardChannel(it?.slug, it?.name) },
                status = m.status!!, statusAt = m.statusAt, createdAt = m.createdAt, movedAt = m.statusAt ?: m.createdAt,
                title = m.body.lineSequence().first().take(140), author = m.author,
                on = listOfNotNull(m.agentId?.let { dev.wildware.agents.BoardWho("agent", it, names[it] ?: "An agent") } ?: dev.wildware.agents.BoardWho("person", "you", "You")),
                removed = m.id in boardRemoved,
                assignees = assignedTo(m.id),
            )
        }.filter { c ->
            when (only) {
                null -> true
                "none" -> c.assignees.isEmpty()
                else -> c.assignees.any { it.principal == only }
            }
        }
        val columns = listOf("todo", "in_progress", "blocked", "done").map { status ->
            val here = cards.filter { (if (it.status == "cancelled") "done" else it.status) == status && (showRemoved || !it.removed) }
            dev.wildware.agents.BoardColumnView(status, here.sortedByDescending { it.movedAt })
        }
        return dev.wildware.agents.BoardSnapshot(columns, boardDays)
    }

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
