package dev.wildware.agents

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
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

    /** The header a media request needs: images and files are behind the owner's key too. */
    val authorizationHeader: String? get() = deviceToken?.let { "Bearer $it" }

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

    /** Deletes an update card. */
    @Throws(Exception::class)
    suspend fun deleteUpdate(updateId: String) {
        http.delete("api/updates/${updateId.encodeURLPathPart()}") { authorise() }.requireSuccess()
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

    /** Uploads one file and returns it unattached: send its id with [postMessage] to attach it. */
    @Throws(Exception::class)
    suspend fun uploadMedia(attachment: Attachment): MediaView =
        http.post("api/media") {
            authorise()
            parameter("filename", attachment.filename)
            contentType(ContentType.parse(attachment.mimeType))
            setBody(attachment.bytes)
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

    internal suspend fun postToProject(projectSlug: String, text: String): String =
        postMessage(mapOf("project" to projectSlug, "body" to text), mediaIds = emptyList()).id

    internal suspend fun markAsWork(messageId: String) {
        http.post("api/cards/message/$messageId/status") {
            authorise()
            jsonBody(buildJsonObject { put("status", "todo") })
        }.requireSuccess()
    }

    /** Posts shared content to a project's feed. The dashboard needs some text, so files alone get a caption. */
    @Throws(Exception::class)
    suspend fun share(projectSlug: String, text: String?, attachments: List<Attachment>): String =
        send(mapOf("project" to projectSlug), text, attachments)

    /**
     * Posts what the composer bar holds into [thread]: `{"project": slug}` for a feed post,
     * `{"request": id}` to talk over a question an agent asked. Files alone get a caption.
     */
    @Throws(Exception::class)
    suspend fun send(thread: Map<String, String>, text: String?, attachments: List<Attachment>): String =
        sendMessage(thread, text, attachments).id

    /** [send], answering with the message as the dashboard stored it too, when it could be read. */
    internal suspend fun sendMessage(thread: Map<String, String>, text: String?, attachments: List<Attachment>): Posted {
        val body = text?.trim()?.takeIf { it.isNotEmpty() } ?: shareCaption(attachments)
        val mediaIds = attachments.map { upload(it) }
        return postMessage(thread + ("body" to body), mediaIds)
    }

    fun close() = http.close()

    private suspend fun upload(attachment: Attachment): String = uploadMedia(attachment).id

    private suspend fun postMessage(fields: Map<String, String>, mediaIds: List<String>): Posted {
        val body = http.post("api/messages") {
            authorise()
            jsonBody(JsonObject(buildMap {
                fields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                if (mediaIds.isNotEmpty()) put("mediaIds", JsonArray(mediaIds.map(::JsonPrimitive)))
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


    private fun shareCaption(attachments: List<Attachment>): String = when (attachments.size) {
        0 -> throw DashboardException(400, "invalid_argument", "There is nothing to share.")
        1 -> "Shared from my phone: ${attachments[0].filename}"
        else -> "Shared ${attachments.size} files from my phone"
    }
}
