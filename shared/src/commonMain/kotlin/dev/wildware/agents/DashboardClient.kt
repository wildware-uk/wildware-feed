package dev.wildware.agents

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The dashboard refused a call. [status] is the HTTP status, [code] the dashboard's error code when it gave one. */
class DashboardException(val status: Int, val code: String?, message: String) : Exception(message)

/** A file going up with a post: a shared screenshot, photo or document. */
class Attachment(val filename: String, val mimeType: String, val bytes: ByteArray)

/** A newly registered phone. [token] is returned once and is the phone's owner key from then on. */
@Serializable
data class DeviceRegistration(val device: Device, val token: String) {
    @Serializable
    data class Device(val id: String, val name: String, val createdAt: String)
}

enum class ApnsEnvironment(val wireName: String) { Sandbox("sandbox"), Production("production") }

/**
 * Everything the app's native parts ask of the dashboard. Every call acts as the owner,
 * authenticated by the phone's device key, except [registerDevice], which uses the web login.
 */
class DashboardClient(
    server: String,
    private val deviceToken: String?,
    engine: HttpClientEngine,
) {
    /** The dashboard's address, ending in a slash. Media paths go after it. */
    val serverUrl: String = server.trimEnd('/') + "/"

    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    internal val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        // No limits by default, so ordinary calls behave as before. The live stream sets its own.
        install(HttpTimeout)
        defaultRequest { url(serverUrl) }
    }

    /**
     * The header a media request needs: images and files are behind the owner's key too. Null for a
     * cookie session ([SignIn.CookieSession]), where the browser sends the cookie instead.
     */
    val authorizationHeader: String? get() = deviceToken?.takeIf { it != SignIn.CookieSession }?.let { "Bearer $it" }

    // --- Reading the feed -------------------------------------------------------------------

    /** Projects, the newest page of updates, tags and agent names. [projectSlug] narrows it to one project. */
    @Throws(Exception::class)
    suspend fun snapshot(projectSlug: String? = null, limit: Int = 50): FeedSnapshot =
        http.get("api/snapshot") {
            authorise()
            parameter("limit", limit)
            if (projectSlug != null) parameter("project", projectSlug)
        }.decoded()

    /** One page further into the past: pass the [UpdatesPage.nextCursor] the last page gave. */
    @Throws(Exception::class)
    suspend fun olderUpdates(cursor: String, projectSlug: String? = null, limit: Int = 50): FeedSnapshot =
        http.get("api/snapshot/updates") {
            authorise()
            parameter("limit", limit)
            parameter("cursor", cursor)
            if (projectSlug != null) parameter("project", projectSlug)
        }.decoded()

    /**
     * Every thread in scope: replies, posts, and who has read and reacted to them. [updateId],
     * [requestId], [replyTo] (a post and its replies) or [taskId] reads one thread alone;
     * [projectSlug] one project's; none of them, every project's.
     */
    @Throws(Exception::class)
    suspend fun messages(
        projectSlug: String? = null,
        updateId: String? = null,
        requestId: String? = null,
        replyTo: String? = null,
        taskId: String? = null,
    ): MessagesSnapshot =
        http.get("api/messages") {
            authorise()
            if (projectSlug != null) parameter("project", projectSlug)
            if (updateId != null) parameter("update", updateId)
            if (requestId != null) parameter("request", requestId)
            if (replyTo != null) parameter("replyTo", replyTo)
            if (taskId != null) parameter("task", taskId)
        }.decoded()

    /** Every question waiting on the owner, oldest first. */
    @Throws(Exception::class)
    suspend fun waitingRequests(): RequestsSnapshot = http.get("api/snapshot/requests") { authorise() }.decoded()

    /** Who is online, by recent heartbeat. */
    @Throws(Exception::class)
    suspend fun liveAgents(): AgentsSnapshot = http.get("api/snapshot/agents") { authorise() }.decoded()

    /** The bell: what the owner was told about, newest first, and how many are new. */
    @Throws(Exception::class)
    suspend fun notifications(): NotificationsSnapshot = http.get("api/notifications") { authorise() }.decoded()

    /** Marks [ids] seen, or every notification when [ids] is null ("Mark all read"). */
    @Throws(Exception::class)
    suspend fun markNotificationsSeen(ids: List<String>?) {
        http.post("api/notifications/seen") {
            authorise()
            jsonBody(buildJsonObject { if (ids != null) put("ids", JsonArray(ids.map(::JsonPrimitive))) })
        }.requireSuccess()
    }

    /**
     * The owner opened these cards: the bell's rows about them are seen, and every device takes
     * their notifications down. Never "everything", whatever [cards] holds.
     */
    @Throws(Exception::class)
    suspend fun markCardsSeen(cards: List<String>) {
        if (cards.isEmpty()) return
        http.post("api/notifications/cards-seen") {
            authorise()
            jsonBody(buildJsonObject { put("cards", JsonArray(cards.map(::JsonPrimitive))) })
        }.requireSuccess()
    }

    /**
     * Of the notification [tags] this device still shows, the ones the owner has dealt with on
     * some device, so they can come down. Empty on a dashboard too old to say.
     */
    @Throws(Exception::class)
    suspend fun handledNotifications(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        val response = http.post("api/notifications/handled") {
            authorise()
            jsonBody(buildJsonObject { put("tags", JsonArray(tags.take(MaxClearTags).map(::JsonPrimitive))) })
        }
        if (response.status.value == 404) return emptyList()
        return response.decoded<HandledNotifications>().handled
    }

    // --- The owner's actions, sent straight away. The outbox calls these. ----------------------

    /** Posts or replies as the owner. The live stream brings it back into the feed. */
    @Throws(Exception::class)
    suspend fun postMessage(message: NewMessage): MessageView =
        http.post("api/messages") {
            authorise()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToJsonElement(NewMessage.serializer(), message))
        }.decoded<MessageCreatedBody>().message

    /** Takes back a post, a reply, or an agent's line. Deleting a post takes its replies with it. */
    @Throws(Exception::class)
    suspend fun deleteMessage(messageId: String) {
        http.delete("api/messages/${messageId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /**
     * Puts one of the project's tags on a card, or takes it off. [tag] is the tag's id, or with
     * [create] a new word the project doesn't have yet. Says on or off, so sending it twice does no harm.
     */
    @Throws(Exception::class)
    suspend fun tagCard(card: CardRef, tag: String, on: Boolean, create: Boolean = false) {
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/tags") {
            authorise()
            jsonBody(buildJsonObject {
                put("tag", tag)
                put("on", on)
                if (create) put("create", true)
            })
        }.requireSuccess()
    }

    /** Sets a field on a card, corrects it, or takes it off when [value] is null. */
    @Throws(Exception::class)
    suspend fun setCardField(card: CardRef, name: String, value: String?) {
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/fields") {
            authorise()
            jsonBody(buildJsonObject {
                put("name", name)
                put("value", value)
            })
        }.requireSuccess()
    }

    /** What the owner follows: a level per project, and the threads they followed or unfollowed. */
    @Throws(Exception::class)
    suspend fun follows(): FollowsSnapshot = http.get("api/subscriptions") { authorise() }.decoded()

    /** Follows the thread a card is in, or unfollows it: whether its replies alert the owner. */
    @Throws(Exception::class)
    suspend fun followThread(card: CardRef, following: Boolean) {
        http.put("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/follow") {
            authorise()
            jsonBody(buildJsonObject { put("following", following) })
        }.requireSuccess()
    }

    /** How much of a channel alerts the owner; null clears it, so the channel follows its project. */
    @Throws(Exception::class)
    suspend fun setProjectNotify(slug: String, level: NotifyLevel?) {
        http.put("api/projects/${slug.encodeURLPathPart()}/subscription") {
            authorise()
            jsonBody(buildJsonObject { put("level", level?.wire?.let(::JsonPrimitive) ?: JsonNull) })
        }.requireSuccess()
    }

    /** How much of a project (a group of channels) alerts the owner; null clears it. */
    @Throws(Exception::class)
    suspend fun setGroupNotify(slug: String, level: NotifyLevel?) {
        http.put("api/groups/${slug.encodeURLPathPart()}/subscription") {
            authorise()
            jsonBody(buildJsonObject { put("level", level?.wire?.let(::JsonPrimitive) ?: JsonNull) })
        }.requireSuccess()
    }

    /** Makes a project (a named group of channels), or gives back the one with that name's slug. */
    @Throws(Exception::class)
    suspend fun createGroup(name: String): GroupView =
        http.post("api/groups") {
            authorise()
            jsonBody(buildJsonObject { put("name", name) })
        }.decoded<GroupBody>().group

    /** Renames a project. */
    @Throws(Exception::class)
    suspend fun renameGroup(slug: String, name: String): GroupView = patchGroup(slug, buildJsonObject { put("name", name) })

    /** A project's accent colour, #rrggbb, or null to take it off. */
    @Throws(Exception::class)
    suspend fun setGroupAccent(slug: String, accent: String?): GroupView =
        patchGroup(slug, buildJsonObject { put("theme", buildJsonObject { put("accent", accent?.let(::JsonPrimitive) ?: JsonNull) }) })

    /** Archives a project (it and its channels list under Archived), or brings it back. */
    @Throws(Exception::class)
    suspend fun setGroupArchived(slug: String, archived: Boolean): GroupView =
        patchGroup(slug, buildJsonObject { put("status", if (archived) "archived" else "active") })

    /** Deletes a project. Its channels stay, in no project. */
    @Throws(Exception::class)
    suspend fun deleteGroup(slug: String) {
        http.delete("api/groups/${slug.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Puts a channel in a project (by group id or slug), or in none with null. */
    @Throws(Exception::class)
    suspend fun setProjectGroup(slug: String, groupId: String?): ProjectView =
        patchProject(slug, buildJsonObject { put("groupId", groupId?.let(::JsonPrimitive) ?: JsonNull) })

    private suspend fun patchGroup(slug: String, body: JsonObject): GroupView =
        http.patch("api/groups/${slug.encodeURLPathPart()}") {
            authorise()
            jsonBody(body)
        }.decoded<GroupBody>().group

    /** Pins a card above the feed, or unpins it. Says which, so sending it twice does no harm. */
    @Throws(Exception::class)
    suspend fun pinCard(card: CardRef, pinned: Boolean) {
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/pin") {
            authorise()
            jsonBody(buildJsonObject { put("pinned", pinned) })
        }.requireSuccess()
    }

    /** Makes a project. The dashboard hands back the one it already has when the name's slug is taken. */
    @Throws(Exception::class)
    suspend fun createProject(name: String, description: String?): ProjectView =
        http.post("api/projects") {
            authorise()
            jsonBody(buildJsonObject {
                put("name", name)
                if (!description.isNullOrBlank()) put("description", description)
            })
        }.decoded<ProjectBody>().project

    /** Renames a project and sets what it is for; a blank [description] takes it off. */
    @Throws(Exception::class)
    suspend fun renameProject(slug: String, name: String, description: String?): ProjectView =
        patchProject(slug, buildJsonObject {
            put("name", name)
            put("description", description?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
        })

    /**
     * Makes picture [mediaId] (an image already on the dashboard) a project's logo, or takes the logo
     * off with null, which also stops it standing in for the name. The colours are left alone.
     */
    @Throws(Exception::class)
    suspend fun setProjectLogo(slug: String, mediaId: String?): ProjectView =
        patchProject(slug, buildJsonObject {
            put("theme", buildJsonObject { put("logoMediaId", mediaId?.let(::JsonPrimitive) ?: JsonNull) })
        })

    /** Shows a project's logo instead of its name, or beside it. Needs a logo to be set. */
    @Throws(Exception::class)
    suspend fun setLogoInsteadOfName(slug: String, instead: Boolean): ProjectView =
        patchProject(slug, buildJsonObject {
            put("theme", buildJsonObject { put("logoReplacesName", instead) })
        })

    /** Renames an agent: every card it posted is relabelled. A name the dashboard refuses comes back as a [DashboardException]. */
    @Throws(Exception::class)
    suspend fun renameAgent(agentId: String, name: String) {
        http.patch("api/agents/${agentId.encodeURLPathPart()}") {
            authorise()
            jsonBody(buildJsonObject { put("name", name) })
        }.requireSuccess()
    }

    /**
     * The MCP clients signed in as an agent over OAuth (a dashboard from before OAuth has none),
     * newest first.
     */
    @Throws(Exception::class)
    suspend fun agentConnections(agentId: String): List<AgentConnection> {
        val response = http.get("api/agents/${agentId.encodeURLPathPart()}/connections") { authorise() }
        if (response.status.value == 404) return emptyList()
        return response.decoded<ConnectionsBody>().connections
    }

    /**
     * Which agents hear a channel ([group] false) or a project, a group of channels ([group] true),
     * by slug: every agent of the team, each with whether it hears it.
     */
    @Throws(Exception::class)
    suspend fun agentMembers(slug: String, group: Boolean): List<AgentMember> =
        http.get("api/${if (group) "groups" else "projects"}/${slug.encodeURLPathPart()}/agents") { authorise() }.decoded<AgentMembersBody>().agents

    /**
     * Adds an agent to a channel or project ([hears] true), or removes it. A removal sticks until it
     * is added back: posting there does not bring it back. The agent is told either way.
     */
    @Throws(Exception::class)
    suspend fun setAgentMember(slug: String, group: Boolean, agentId: String, hears: Boolean): AgentMember =
        http.put("api/${if (group) "groups" else "projects"}/${slug.encodeURLPathPart()}/agents/${agentId.encodeURLPathPart()}") {
            authorise()
            jsonBody(buildJsonObject { put("hears", hears) })
        }.decoded<AgentMemberBody>().agent

    /** Every agent, removed ones too (marked by [AgentRecord.revokedAt]): old cards still name them. */
    @Throws(Exception::class)
    suspend fun agents(): List<AgentRecord> = http.get("api/agents") { authorise() }.decoded<AgentsBody>().agents

    /**
     * Removes an agent: its token and every app signed in as it stop working at once, and it leaves
     * the agent lists. What it posted stays.
     */
    @Throws(Exception::class)
    suspend fun removeAgent(agentId: String) {
        http.delete("api/agents/${agentId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Revokes one of an agent's connections: that client is signed out; the agent and its other tokens keep working. */
    @Throws(Exception::class)
    suspend fun revokeAgentConnection(agentId: String, connectionId: String) {
        http.delete("api/agents/${agentId.encodeURLPathPart()}/connections/${connectionId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Pins a project to the top of the project list, or unpins it. */
    @Throws(Exception::class)
    suspend fun setProjectPinned(slug: String, pinned: Boolean): ProjectView =
        patchProject(slug, buildJsonObject { put("pinned", pinned) })

    /** Archives a project (listed apart, finished with) or brings it back. */
    @Throws(Exception::class)
    suspend fun setProjectArchived(slug: String, archived: Boolean): ProjectView =
        patchProject(slug, buildJsonObject { put("status", if (archived) "archived" else "active") })

    /** A project's own page and accent colours, as #rrggbb; null takes one off. */
    @Throws(Exception::class)
    suspend fun setProjectColours(slug: String, background: String?, accent: String?): ProjectView =
        patchProject(slug, buildJsonObject {
            put("theme", buildJsonObject {
                put("background", background?.let(::JsonPrimitive) ?: JsonNull)
                put("accent", accent?.let(::JsonPrimitive) ?: JsonNull)
            })
        })

    private suspend fun patchProject(slug: String, body: JsonObject): ProjectView =
        http.patch("api/projects/${slug.encodeURLPathPart()}") {
            authorise()
            jsonBody(body)
        }.decoded<ProjectBody>().project

    /** Deletes an update card. */
    @Throws(Exception::class)
    suspend fun deleteUpdate(updateId: String) {
        http.delete("api/updates/${updateId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Every agent waiting to be allowed in. A dashboard from before pairing answers 404. */
    @Throws(Exception::class)
    suspend fun pairings(): PairingsSnapshot = http.get("api/pairings") { authorise() }.decoded()

    /**
     * Lets an agent in, under [name] (null keeps the name it asked for). A name another agent has
     * is refused with code `name_taken`: choose another.
     */
    @Throws(Exception::class)
    suspend fun approvePairing(pairingId: String, name: String?) {
        http.post("api/pairings/${pairingId.encodeURLPathPart()}/approve") {
            authorise()
            jsonBody(buildJsonObject { if (name != null) put("name", name) })
        }.requireSuccess()
    }

    /** Turns an agent away: it hears "denied". */
    @Throws(Exception::class)
    suspend fun denyPairing(pairingId: String) {
        http.post("api/pairings/${pairingId.encodeURLPathPart()}/deny") { authorise() }.requireSuccess()
    }

    /** Lets an agent go without an answer: it hears "cancelled". */
    @Throws(Exception::class)
    suspend fun dismissRequest(requestId: String) {
        http.delete("api/requests/${requestId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /**
     * Puts an emoji on any card, or takes it off. [on] null toggles it, as a tap does.
     * Returns the card's reactions as they now stand.
     */
    @Throws(Exception::class)
    suspend fun react(card: CardRef, emoji: String, on: Boolean? = null): List<ReactionView> =
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/reactions") {
            authorise()
            jsonBody(buildJsonObject {
                put("emoji", emoji)
                if (on != null) put("on", on)
            })
        }.decoded<ReactionsBody>().reactions

    /** A card's blocks as they are now (docs/BLOCKS.md). */
    @Throws(Exception::class)
    suspend fun blocks(card: CardRef): List<BlockView> =
        http.get("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/blocks") { authorise() }.decoded<BlocksBody>().blocks

    /** Ticks or unticks one line of a checklist. Returns the block as it now stands. */
    @Throws(Exception::class)
    suspend fun checkBlockItem(card: CardRef, blockId: String, itemId: String, done: Boolean): BlockView =
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/blocks/${blockId.encodeURLPathPart()}/check") {
            authorise()
            jsonBody(buildJsonObject {
                put("item", itemId)
                put("done", done)
            })
        }.decoded<BlockBody>().block

    /** Votes on a poll: [options] replaces your vote, and none takes it back. Returns the block as it now stands. */
    @Throws(Exception::class)
    suspend fun voteBlock(card: CardRef, blockId: String, options: List<String>): BlockView =
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/blocks/${blockId.encodeURLPathPart()}/vote") {
            authorise()
            jsonBody(buildJsonObject {
                put("options", kotlinx.serialization.json.JsonArray(options.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        }.decoded<BlockBody>().block

    /** Uploads one file and returns it unattached: send its id with [postMessage] to attach it. */
    @Throws(Exception::class)
    suspend fun uploadMedia(attachment: Attachment): MediaView = uploadMedia(attachment, null)

    /** [uploadMedia], telling [sent] how many of its bytes have gone up so far. */
    internal suspend fun uploadMedia(attachment: Attachment, sent: ((Long) -> Unit)?): MediaView =
        http.post("api/media") {
            authorise()
            parameter("filename", attachment.filename)
            contentType(ContentType.parse(attachment.mimeType))
            setBody(attachment.bytes)
            if (sent != null) onUpload { bytes, _ -> sent(bytes) }
        }.decoded<MediaCreatedBody>().media

    @Throws(Exception::class)
    suspend fun summary(): DashboardSummary = http.get("api/summary") { authorise() }.decoded()

    /**
     * The latest updates and agent messages, named and as plain text. [projectSlug] narrows it
     * to one project. Reads the owner snapshot, the one read that carries agent and project names.
     */
    @Throws(Exception::class)
    suspend fun recent(projectSlug: String? = null, limit: Int = 50): RecentActivity =
        http.get("api/snapshot") {
            authorise()
            parameter("limit", limit)
            if (projectSlug != null) parameter("project", projectSlug)
        }.decoded<SnapshotBody>().toActivity()

    /**
     * Which projects may still alert this phone during a Focus. Null or empty lifts the limit,
     * so every project alerts again. The dashboard refuses slugs it doesn't know.
     */
    @Throws(Exception::class)
    suspend fun setAlertProjects(projectSlugs: List<String>?) {
        http.put("api/devices/current/alert-projects") {
            authorise()
            jsonBody(buildJsonObject {
                put("projects", projectSlugs?.takeIf { it.isNotEmpty() }?.let { slugs -> JsonArray(slugs.distinct().map(::JsonPrimitive)) } ?: JsonNull)
            })
        }.requireSuccess()
    }

    /** Which alerts this device chose, or null for every alert. */
    @Throws(Exception::class)
    suspend fun alertPrefs(): AlertPrefs? = http.get("api/devices/current/alert-prefs") { authorise() }.decoded<AlertPrefsBody>().prefs

    /** Saves which alerts this device wants (null for every alert); answers what the dashboard kept. */
    @Throws(Exception::class)
    suspend fun setAlertPrefs(prefs: AlertPrefs?): AlertPrefs? =
        http.put("api/devices/current/alert-prefs") {
            authorise()
            jsonBody(buildJsonObject {
                put("prefs", prefs?.let { json.encodeToJsonElement(AlertPrefs.serializer(), it) } ?: JsonNull)
            })
        }.decoded<AlertPrefsBody>().prefs

    /** How long the dashboard keeps media. Only the owner may read it. */
    @Throws(Exception::class)
    suspend fun mediaRetention(): MediaRetention = http.get("api/settings/media-retention") { authorise() }.decoded()

    /**
     * Saves how long the dashboard keeps media and answers what it stored. A refusal comes back as a
     * [DashboardException] whose message is the dashboard's own words, to show as they are.
     */
    @Throws(Exception::class)
    suspend fun setMediaRetention(setting: MediaRetention): MediaRetention =
        http.put("api/settings/media-retention") {
            authorise()
            jsonBody(buildJsonObject {
                put("days", setting.days)
                put("kinds", JsonArray(setting.kinds.map(::JsonPrimitive)))
            })
        }.decoded()

    /** Every device signed in to this dashboard as the owner, oldest first. */
    @Throws(Exception::class)
    suspend fun devices(): List<OwnerDevice> = http.get("api/devices") { authorise() }.decoded<DevicesBody>().devices

    /**
     * Revokes a device's key, so a lost phone can't read or answer anything more. Revoking this
     * device's own key signs it out.
     */
    @Throws(Exception::class)
    suspend fun removeDevice(deviceId: String) {
        http.delete("api/devices/${deviceId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    // --- Secrets (docs/SECRETS.md) ------------------------------------------------------------

    /** The dashboard's secrets, without values. A dashboard from before secrets answers 404. */
    @Throws(Exception::class)
    suspend fun secrets(): SecretsSnapshot = http.get("api/secrets") { authorise() }.decoded()

    /** Adds a secret. [value] goes in this one request and is never kept by the app. */
    @Throws(Exception::class)
    suspend fun createSecret(name: String, value: String, note: String?, scope: SecretScope): SecretView =
        http.post("api/secrets") {
            authorise()
            jsonBody(buildJsonObject {
                put("name", name)
                put("value", value)
                if (note != null) put("note", note)
                put("allAgents", scope.allAgents)
                put("agentIds", JsonArray(scope.agentIds.map(::JsonPrimitive)))
            })
        }.decoded<SecretBody>().secret

    /** Changes a secret: any of a new [value], its [scope], or whether it is [paused]. Null leaves it. */
    @Throws(Exception::class)
    suspend fun changeSecret(secretId: String, value: String? = null, scope: SecretScope? = null, paused: Boolean? = null): SecretView =
        http.patch("api/secrets/${secretId.encodeURLPathPart()}") {
            authorise()
            jsonBody(buildJsonObject {
                if (value != null) put("value", value)
                if (scope != null) {
                    put("allAgents", scope.allAgents)
                    put("agentIds", JsonArray(scope.agentIds.map(::JsonPrimitive)))
                }
                if (paused != null) put("paused", paused)
            })
        }.decoded<SecretBody>().secret

    @Throws(Exception::class)
    suspend fun deleteSecret(secretId: String) {
        http.delete("api/secrets/${secretId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** The value, for the owner's eyes ([copy] false) or clipboard ([copy] true). The dashboard logs which. */
    @Throws(Exception::class)
    suspend fun revealSecret(secretId: String, copy: Boolean): RevealedSecret =
        http.post("api/secrets/${secretId.encodeURLPathPart()}/reveal") {
            authorise()
            jsonBody(buildJsonObject { put("purpose", if (copy) "copy" else "reveal") })
        }.decoded()

    /** The audit, newest first; [secretId] narrows it to one secret. */
    @Throws(Exception::class)
    suspend fun secretAudit(secretId: String? = null): List<SecretAuditEntry> =
        http.get("api/secrets/audit") {
            authorise()
            if (secretId != null) parameter("secret", secretId)
        }.decoded<SecretAuditBody>().entries

    /**
     * Answers an agent's request_secret: [value] is stored under the name it asked for, and the
     * agent hears only that it is ready. Never queued: it goes now or not at all.
     */
    @Throws(Exception::class)
    suspend fun provideSecret(requestId: String, value: String) {
        http.post("api/requests/${requestId.encodeURLPathPart()}/secret") {
            authorise()
            jsonBody(buildJsonObject { put("value", value) })
        }.requireSuccess()
    }

    // ---- people (a team, on a server with migration 055) ---------------------------------------

    /** Who this device belongs to, and whether the team has more than one person. Null on a server from before teams. */
    @Throws(Exception::class)
    suspend fun me(): MeBody? {
        val response = http.get("api/me") { authorise() }
        if (response.status.value == 404) return null
        return response.decoded()
    }

    /** Everyone who has been in the team, removed people too (marked). Emails only for owners and admins. */
    @Throws(Exception::class)
    suspend fun people(): List<Person> = http.get("api/people") { authorise() }.decoded<PeopleBody>().people

    /** Invites still waiting. Owners and admins only. */
    @Throws(Exception::class)
    suspend fun invites(): List<Invite> = http.get("api/invites") { authorise() }.decoded<InvitesBody>().invites

    /** A new invite for [role] ("member", "admin" or "owner"), optionally for one [email]. Its code is shown once. */
    @Throws(Exception::class)
    suspend fun createInvite(role: String, email: String?): CreatedInvite = http.post("api/invites") {
        authorise()
        jsonBody(buildJsonObject {
            put("role", role)
            if (!email.isNullOrBlank()) put("email", email.trim())
        })
    }.decoded()

    @Throws(Exception::class)
    suspend fun revokeInvite(inviteId: String) {
        http.delete("api/invites/${inviteId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Owners only: someone else's role. */
    @Throws(Exception::class)
    suspend fun setRole(personId: String, role: String): Person = http.patch("api/people/${personId.encodeURLPathPart()}") {
        authorise()
        jsonBody(buildJsonObject { put("role", role) })
    }.decoded<PersonBody>().person

    /** Removes someone from the team (or, with your own id, leaves it). Their keys stop working at once. */
    @Throws(Exception::class)
    suspend fun removePerson(personId: String) {
        http.delete("api/people/${personId.encodeURLPathPart()}") { authorise() }.requireSuccess()
    }

    /** Changes your own name. */
    @Throws(Exception::class)
    suspend fun rename(name: String): Person = http.patch("api/me") {
        authorise()
        jsonBody(buildJsonObject { put("name", name) })
    }.decoded<PersonBody>().person

    /** Swaps the web view's login cookie for a long-lived device key. */
    @Throws(Exception::class)
    suspend fun registerDevice(name: String, sessionCookie: String): DeviceRegistration =
        http.post("api/devices") {
            header(HttpHeaders.Cookie, sessionCookie)
            jsonBody(buildJsonObject { put("name", name.take(100)) })
        }.decoded()

    @Throws(Exception::class)
    suspend fun setApnsToken(token: String, environment: ApnsEnvironment) {
        http.put("api/devices/current/apns") {
            authorise()
            jsonBody(buildJsonObject {
                put("token", token)
                put("environment", environment.wireName)
            })
        }.requireSuccess()
    }

    /** Pass null to say the Live Activity has ended and its token should no longer be pushed to. */
    @Throws(Exception::class)
    suspend fun setLiveActivityToken(token: String?) = putLiveActivity(buildJsonObject { put("updateToken", token) })

    @Throws(Exception::class)
    suspend fun setPushToStartToken(token: String?) = putLiveActivity(buildJsonObject { put("pushToStartToken", token) })

    @Throws(Exception::class)
    suspend fun answer(requestId: String, value: JsonElement) {
        http.post("api/requests/$requestId/answer") {
            authorise()
            jsonBody(buildJsonObject { put("value", value) })
        }.requireSuccess()
    }

    /**
     * Answers a request from a notification button. Returns false when the button no longer
     * answers anything, such as a stale notification for a request that changed shape.
     */
    @Throws(Exception::class)
    suspend fun answerFromAction(
        requestId: String,
        kind: String,
        options: List<String>?,
        actionId: String,
        typedText: String?,
    ): Boolean {
        val requestKind = RequestKind.fromWire(kind) ?: return false
        val value = RequestActions.answerFor(requestKind, options, actionId, typedText) ?: return false
        answer(requestId, value)
        return true
    }

    /** Answers [request] with what was said to Siri. Returns false when the words did not clearly pick an answer. */
    @Throws(Exception::class)
    suspend fun answerSpoken(request: PendingRequest, spoken: String): Boolean {
        val value = SpokenAnswer.parse(request, spoken) ?: return false
        answer(request.id, value)
        return true
    }

    /** Replies in a thread. [thread] is the `reply` object a notification carried, used as-is. */
    @Throws(Exception::class)
    suspend fun reply(thread: Map<String, String>, body: String): String =
        postMessage(thread + ("body" to body), mediaIds = emptyList()).id

    /** Posts [text] to [projectSlug]'s feed and marks it as work, so an agent on that project picks it up. */
    @Throws(Exception::class)
    suspend fun createTicket(projectSlug: String, text: String): String {
        val messageId = postToProject(projectSlug, text)
        markAsWork(messageId)
        return messageId
    }

    /**
     * Sends one item from the outbox. A failed send comes back as a result saying whether to try again,
     * not as an error. [attachments] are the item's files read back from disk, in order.
     */
    @Throws(Exception::class)
    suspend fun deliver(item: OutboxItem, attachments: List<Attachment>): DeliveryResult = deliverQueued(item, attachments)

    /** [sendKey] names this post for every try, so one sent twice is stored once ([sendMessage]). */
    internal suspend fun postToProject(projectSlug: String, text: String, sendKey: String? = null): String =
        postMessage(mapOf("project" to projectSlug, "body" to text), mediaIds = emptyList(), sendKey).id

    internal suspend fun markAsWork(messageId: String) = setCardStatus(CardRef(CardKinds.Message, messageId), "todo")

    /** Sets a card's work state ("todo", "in_progress", "blocked", "done", "cancelled"), or null for "not work". */
    @Throws(Exception::class)
    suspend fun setCardStatus(card: CardRef, status: String?) {
        http.post("api/cards/${card.kind.encodeURLPathPart()}/${card.id.encodeURLPathPart()}/status") {
            authorise()
            jsonBody(buildJsonObject { put("status", status) })
        }.requireSuccess()
    }

    /**
     * Posts what was shared from another app into a project's feed. Files alone say where they came
     * from ("Shared from my phone: shot.png"), as the iPhone's share sheet does.
     */
    @Throws(Exception::class)
    suspend fun share(projectSlug: String, text: String?, attachments: List<Attachment>): String =
        send(mapOf("project" to projectSlug), text?.trim()?.takeIf { it.isNotEmpty() } ?: shareCaption(attachments), attachments)

    /**
     * Posts what the composer bar holds into [thread]: `{"project": slug}` for a feed post,
     * `{"request": id}` to talk over a question an agent asked. The dashboard needs some text, so
     * files sent alone go up under their names.
     */
    @Throws(Exception::class)
    suspend fun send(thread: Map<String, String>, text: String?, attachments: List<Attachment>): String =
        sendMessage(thread, text, attachments).id

    /** [send], answering with the message as the dashboard stored it too, when it could be read. */
    internal suspend fun sendMessage(
        thread: Map<String, String>,
        text: String?,
        attachments: List<Attachment>,
        /**
         * The same on every try of one send. When an answer is lost on the way back (a dropped
         * connection, a timeout) and the send goes again, the dashboard hands back the first message
         * instead of storing a copy.
         */
        sendKey: String? = null,
        /** How many bytes of all the files together have gone up so far. */
        sent: ((Long) -> Unit)? = null,
    ): Posted {
        val body = text?.trim()?.takeIf { it.isNotEmpty() } ?: namesOf(attachments)
        var done = 0L
        val mediaIds = attachments.map { file ->
            val before = done
            uploadMedia(file, sent?.let { report -> { bytes -> report(before + bytes) } }).id.also {
                done += file.bytes.size
                sent?.invoke(done)
            }
        }
        return postMessage(thread + ("body" to body), mediaIds, sendKey)
    }

    fun close() = http.close()

    private suspend fun upload(attachment: Attachment): String = uploadMedia(attachment).id

    private suspend fun postMessage(fields: Map<String, String>, mediaIds: List<String>, sendKey: String? = null): Posted {
        val body = http.post("api/messages") {
            authorise()
            jsonBody(JsonObject(buildMap {
                fields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                if (mediaIds.isNotEmpty()) put("mediaIds", JsonArray(mediaIds.map(::JsonPrimitive)))
                if (sendKey != null) put("sendKey", JsonPrimitive(sendKey))
            }))
        }.decoded<PostedBody>()
        // Only the id has to be there. A message this version can't read in full is still sent:
        // failing here would send it again.
        val message = runCatching { json.decodeFromJsonElement(MessageView.serializer(), body.message) }.getOrNull()
        val id = message?.id ?: body.message["id"]?.jsonPrimitive?.contentOrNull
            ?: throw DashboardException(500, null, "The dashboard didn't say which message it made.")
        return Posted(id, message)
    }

    /** A message the dashboard took: its id, and the whole row when it could be read. */
    internal class Posted(val id: String, val message: MessageView?)

    @Serializable
    private class PostedBody(val message: JsonObject)

    private suspend fun putLiveActivity(body: JsonObject) {
        http.put("api/devices/current/live-activity") {
            authorise()
            jsonBody(body)
        }.requireSuccess()
    }

    internal fun HttpRequestBuilder.authorise() {
        val token = deviceToken ?: throw DashboardException(401, "unauthenticated", "This phone is not linked to the dashboard yet.")
        // A cookie session: the browser sends the cookie with the request itself.
        if (token == SignIn.CookieSession) return
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun HttpRequestBuilder.jsonBody(body: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    internal suspend inline fun <reified T> HttpResponse.decoded(): T {
        requireSuccess()
        return body()
    }

    internal suspend fun HttpResponse.requireSuccess() {
        if (status.isSuccess()) return
        val error = runCatching { json.decodeFromString<ErrorBody>(bodyAsText()) }.getOrNull()
        throw DashboardException(status.value, error?.error, error?.message ?: "The dashboard answered ${status.value}.")
    }

    @Serializable
    internal data class ErrorBody(val error: String? = null, val message: String? = null)


    /** What files sent with no words say: just their names, "report.pdf, logs.zip". */
    private fun namesOf(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) throw DashboardException(400, "invalid_argument", "There is nothing to send.")
        return attachments.joinToString(", ") { it.filename }.take(MaxCaption)
    }

    private fun shareCaption(attachments: List<Attachment>): String = when (attachments.size) {
        0 -> throw DashboardException(400, "invalid_argument", "There is nothing to share.")
        1 -> "Shared from my phone: ${attachments[0].filename}"
        else -> "Shared ${attachments.size} files from my phone"
    }

    private companion object {
        /** Well inside the dashboard's limit on a message's words. */
        const val MaxCaption = 2_000
    }
}

/** A device signed in as the owner. Times are ISO-8601; [lastSeenAt] is null until it is first used. */
@Serializable
data class OwnerDevice(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
    /** It takes push alerts (an iPhone). */
    val hasApns: Boolean = false,
)

@kotlinx.serialization.Serializable
internal data class DevicesBody(val devices: List<OwnerDevice> = emptyList())

/** An MCP client the owner allowed in the browser as an agent (OAuth). */
@Serializable
data class AgentConnection(
    val id: String,
    /** What the client calls itself. */
    val clientName: String,
    /** Where its sign-in went back to: an https or loopback origin, or `scheme:` for an app's own links. */
    val redirectOrigin: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

/**
 * One agent of the team, for one channel or project: whether it hears it, and why (`all`: it has
 * never chosen, so it hears every channel; `chosen`, `group` or `posted`). [removed] is where the
 * owner took it out (`channel`, or `group` through the channel's project), or null.
 */
@Serializable
data class AgentMember(
    val agentId: String,
    val name: String,
    val hears: Boolean,
    val via: String? = null,
    val removed: String? = null,
)

/** An agent as the owner's list has it. */
@Serializable
data class AgentRecord(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
    /** Set once the owner removed it. */
    val revokedAt: String? = null,
)

@kotlinx.serialization.Serializable
internal data class AgentsBody(val agents: List<AgentRecord> = emptyList())

@kotlinx.serialization.Serializable
internal data class AgentMembersBody(val agents: List<AgentMember> = emptyList())

@kotlinx.serialization.Serializable
internal data class AgentMemberBody(val agent: AgentMember)

@kotlinx.serialization.Serializable
internal data class ConnectionsBody(val connections: List<AgentConnection> = emptyList())

@kotlinx.serialization.Serializable
internal data class ProjectBody(val project: ProjectView)

@kotlinx.serialization.Serializable
internal data class GroupBody(val group: GroupView)

@kotlinx.serialization.Serializable
internal data class AlertPrefsBody(val prefs: AlertPrefs? = null)

/** A person in the team (migration 055). [role] is "owner", "admin" or "member". */
@Serializable
data class Person(
    val id: String,
    val name: String,
    val handle: String = "",
    val role: String = "member",
    val founder: Boolean = false,
    val email: String? = null,
    val createdAt: String = "",
    val removedAt: String? = null,
    /** When they last used the app on any device; the people list only. */
    val lastSeenAt: String? = null,
    /** They used it within the last five minutes, as an agent counts as online. */
    val online: Boolean = false,
) {
    val removed: Boolean get() = removedAt != null
}

/** `GET /api/me`: you, and whether the team has ever had more than one person. */
@Serializable
data class MeBody(val person: Person, val team: Boolean = false)

/** An invite still waiting to be used. */
@Serializable
data class Invite(
    val id: String,
    val email: String? = null,
    val role: String = "member",
    val createdBy: String = "",
    val createdAt: String = "",
    val expiresAt: String = "",
)

/** A new invite: [link] opens the join page; [token] is the code to paste. Shown once. */
@Serializable
data class CreatedInvite(val invite: Invite, val token: String, val link: String)

@Serializable
internal data class PeopleBody(val people: List<Person> = emptyList())

@Serializable
internal data class PersonBody(val person: Person)

@Serializable
internal data class InvitesBody(val invites: List<Invite> = emptyList())
