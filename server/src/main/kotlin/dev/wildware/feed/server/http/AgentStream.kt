package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.AgentWork
import dev.wildware.feed.server.domain.AgentWork.Subscription
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.events.AppEvent
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `GET /api/agent/stream`, what the channel bridge listens to (spec/04-stream-and-events.md §4,
 * TS `src/http/stream/agent.ts`): `work` counts whenever they change, the newest unread `message`s
 * (each handed to a client once), the `answer` to one of the agent's requests, and the owner's
 * `reaction` on its lines. Frames carry no ids; there is no replay. Ends within about five seconds
 * of the token being revoked (decided fix 11).
 */
internal suspend fun ApplicationCall.agentStream(app: App, agent: Agents.Agent, token: String) {
    val subscribed = try {
        subscription(app)
    } catch (e: DomainError) {
        return respondJson(if (e.code == "invalid_argument") 400 else 404, Wire.error(e.code, e.message))
    }
    val clientId = request.queryParameters["client"]?.trim()?.take(CLIENT_ID_MAX)?.ifEmpty { null }
    val work = app.work
    val sent = HashSet<String>()

    fun messageFrame(): String {
        val unread = work.unread(agent.id, subscribed, MESSAGE_LIMIT)
        if (unread.isEmpty()) return ""
        val delivered = work.alreadyDelivered(agent.id, clientId, unread.map { it.id })
        val fresh = unread.filter { it.id !in delivered && it.id !in sent }
        if (fresh.isEmpty()) return ""
        fresh.forEach { sent += it.id }
        work.markDelivered(agent.id, clientId, fresh.map { it.id })
        return Frames.named("message", buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("messages", JsonArray(fresh.map { m -> messageItem(app, m) }))
        })
    }

    fun workFrame(c: AgentWork.Counts) = Frames.named("work", buildJsonObject {
        put("type", JsonPrimitive("work"))
        put("unread_messages", JsonPrimitive(c.unreadMessages))
        put("open_tasks", JsonPrimitive(c.openTasks))
        put("pending_approvals", JsonPrimitive(c.pendingApprovals))
        put("at", JsonPrimitive(Wire.isoOf(app.clock())))
    })

    respondSse(
        SseContent(stillAllowed = { app.agents.authenticate(token).first != null }, now = app.clock) { sink ->
            var last: AgentWork.Counts? = null
            val (_, stop) = app.bus.subscribeFrom(
                since = null,
                listener = listener@{ event: AppEvent ->
                    if (event.type !in WATCHED) return@listener
                    val next = runCatching { work.countScoped(agent.id, subscribed) }.getOrNull() ?: return@listener
                    if (event.type == "request.answered") answerFrame(app, agent.id, subscribed, event.payload)?.let(sink::send)
                    if (event.type == "reaction.updated") reactionFrame(app, agent.id, subscribed, event.payload)?.let(sink::send)
                    if (next == last) return@listener
                    val rose = next.unreadMessages > (last?.unreadMessages ?: 0)
                    last = next
                    sink.send(workFrame(next))
                    if (rose) messageFrame().takeIf { it.isNotEmpty() }?.let(sink::send)
                },
            ) {
                sink.send(Frames.retry(Frames.RETRY_MS) + Frames.comment("connected"))
                val first = work.countScoped(agent.id, subscribed)
                last = first
                sink.send(workFrame(first))
                if (first.unreadMessages > 0) messageFrame().takeIf { it.isNotEmpty() }?.let(sink::send)
            }
            stop
        },
    )
}

private const val MESSAGE_LIMIT = 5
private const val CLIENT_ID_MAX = 200
private val WATCHED = setOf(
    "message.created", "message.deleted", "messages.read", "update.updated", "message.updated",
    "reaction.updated", "request.created", "request.answered",
)

private fun ApplicationCall.subscription(app: App): Subscription {
    val wanted = request.queryParameters.getAll("project").orEmpty()
        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return Subscription.Derived
    if ("*" in wanted) {
        if (wanted.size > 1) throw invalid("* means every project, so it cannot be listed beside others")
        return Subscription.Everything
    }
    return Subscription.Projects(wanted.map { ref -> app.reads.findProject(ref)?.id ?: throw notFound("no such project: $ref") })
}

private fun inScope(projectId: String?, subscribed: Subscription) =
    subscribed !is Subscription.Projects || projectId == null || projectId in subscribed.ids

private fun projectNames(app: App, projectId: String?): Pair<JsonElement, JsonElement> {
    val p = projectId?.let { id -> app.db.one("SELECT slug, name FROM projects WHERE id = ?", id) { it.getString(1) to it.getString(2) } }
    return (p?.first?.let(::JsonPrimitive) ?: JsonNull) to (p?.second?.let(::JsonPrimitive) ?: JsonNull)
}

private fun str(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

private fun messageItem(app: App, m: AgentWork.Line): JsonObject {
    val (slug, name) = projectNames(app, m.projectId)
    val quoted = app.work.quoted(m)
    val media = app.db.query("SELECT id, kind FROM media WHERE message_id = ? ORDER BY seq", m.id) {
        buildJsonObject { put("id", JsonPrimitive(it.getString(1))); put("kind", JsonPrimitive(it.getString(2))) }
    }
    return buildJsonObject {
        put("message_id", JsonPrimitive(m.id))
        put("project_id", str(m.projectId))
        put("project", slug)
        put("project_name", name)
        put("update_id", str(m.updateId))
        put("task_id", str(m.taskId))
        put("reply_to", str(m.replyTo))
        put("answers", str(m.answers))
        put("in_reply_to", quoted?.let { (parent, body, truncated) ->
            buildJsonObject {
                put("id", JsonPrimitive(parent.id))
                put("author", JsonPrimitive(parent.author))
                put("body", JsonPrimitive(body))
                put("truncated", JsonPrimitive(truncated))
            }
        } ?: JsonNull)
        put("request_id", str(m.requestId))
        put("author", JsonPrimitive(m.author))
        put("author_name", str(app.work.authorName(m.author)))
        put("body", JsonPrimitive(m.body))
        put("created_at", JsonPrimitive(Wire.isoOf(m.createdAt)))
        put("media", JsonArray(media))
    }
}

private fun answerFrame(app: App, agentId: String, subscribed: Subscription, payload: JsonObject): String? {
    val requestId = payload["requestId"]?.jsonPrimitive?.content ?: return null
    data class Req(val agentId: String, val projectId: String?, val updateId: String?, val state: String, val kind: String, val question: String, val answer: String?, val decidedAt: Long?)
    val r = app.db.one("SELECT agent_id, project_id, update_id, state, kind, question, answer, decided_at FROM approvals WHERE id = ?", requestId) {
        Req(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7),
            it.getLong(8).let { v -> if (it.wasNull()) null else v })
    } ?: return null
    if (r.agentId != agentId || !inScope(r.projectId, subscribed)) return null
    val state = when (r.state) { "approved", "rejected" -> "answered"; else -> r.state }
    if (state == "withdrawn") return null
    val value: JsonElement = r.answer?.let { (Wire.json.parseToJsonElement(it) as? JsonObject)?.get("value") }
        ?: if (r.state == "approved" || r.state == "rejected") JsonPrimitive(r.state == "approved") else JsonNull
    val (slug, name) = projectNames(app, r.projectId)
    return Frames.named("answer", buildJsonObject {
        put("type", JsonPrimitive("answer"))
        put("request_id", JsonPrimitive(requestId))
        put("state", JsonPrimitive(state))
        put("kind", JsonPrimitive(r.kind))
        put("question", JsonPrimitive(r.question))
        put("answer", value)
        put("project", slug)
        put("project_name", name)
        put("update_id", str(r.updateId))
        put("answered_at", r.decidedAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
    })
}

private fun reactionFrame(app: App, agentId: String, subscribed: Subscription, payload: JsonObject): String? {
    if (payload["actor"]?.jsonPrimitive?.content != "human") return null
    val messageId = (payload["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val m = app.work.find(messageId) ?: return null
    if (m.deletedAt != null) return null
    val mine = "agent:$agentId"
    val concerns = m.author == mine ||
        (m.updateId != null && app.db.one("SELECT agent_id FROM updates WHERE id = ?", m.updateId) { it.getString(1) } == agentId) ||
        app.work.threadOf(m).any { it.author == mine }
    if (!concerns || !inScope(m.projectId, subscribed)) return null
    val (slug, name) = projectNames(app, m.projectId)
    return Frames.named("reaction", buildJsonObject {
        put("type", JsonPrimitive("reaction"))
        put("message_id", JsonPrimitive(m.id))
        put("emoji", payload["emoji"] ?: JsonNull)
        put("on", payload["on"] ?: JsonNull)
        put("project", slug)
        put("project_name", name)
        put("update_id", str(m.updateId))
        put("task_id", str(m.taskId))
        put("body", JsonPrimitive(m.body.replace(Regex("\\s+"), " ").trim().take(160)))
    })
}
