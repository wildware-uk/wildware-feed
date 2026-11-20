package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.AgentWork
import dev.wildware.feed.server.domain.AgentWork.Subscription
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.Subscriptions
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
 * `reaction` on its lines, and a person's tick or vote on its `block`s (migration 057). Frames carry no ids; there is no replay. Ends within about five seconds
 * of the token being revoked (decided fix 11).
 *
 * With no `?project=` it hears what the agent follows on the server ([Subscriptions]), read afresh
 * on every event, so a new subscription takes effect without reconnecting. `?project=` (older
 * bridges) names the projects instead, and still hears followed threads and mentions anywhere.
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

    withStreamSlot(app) {
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
                    if (event.type == "block.updated") blockFrame(app, agent.id, subscribed, event.payload)?.let(sink::send)
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
}

private const val MESSAGE_LIMIT = 5
private const val CLIENT_ID_MAX = 200
private val WATCHED = setOf(
    "message.created", "message.deleted", "messages.read", "update.updated", "message.updated",
    "reaction.updated", "request.created", "request.answered", "subscriptions.updated", "block.updated",
)

private fun ApplicationCall.subscription(app: App): Subscription {
    // `?channel=` is the newer name for `?project=` (migration 053); old bridges send `?project=`.
    val wanted = (request.queryParameters.getAll("project").orEmpty() + request.queryParameters.getAll("channel").orEmpty())
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
    val p = projectId?.let { id -> app.db.one("SELECT slug, name FROM projects WHERE team_id = :team AND id = ?", id) { it.getString(1) to it.getString(2) } }
    return (p?.first?.let(::JsonPrimitive) ?: JsonNull) to (p?.second?.let(::JsonPrimitive) ?: JsonNull)
}

private fun str(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

private fun messageItem(app: App, m: AgentWork.Line): JsonObject {
    val (slug, name) = projectNames(app, m.projectId)
    val quoted = app.work.quoted(m)
    val media = app.db.query("SELECT id, kind FROM media WHERE team_id = :team AND message_id = ? ORDER BY seq", m.id) {
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
                // On a team, which person (migration 055); never for an agent, as before.
                if (parent.author == "human") app.work.authorName(parent.author, parent.userId)?.let { put("author_name", JsonPrimitive(it)) }
                put("body", JsonPrimitive(body))
                put("truncated", JsonPrimitive(truncated))
            }
        } ?: JsonNull)
        put("request_id", str(m.requestId))
        put("author", JsonPrimitive(m.author))
        val authorName = app.work.authorName(m.author, m.userId)
        put("author_name", str(authorName))
        if (m.author == "human" && authorName != null) put("author_user_id", JsonPrimitive(m.userId))
        put("body", JsonPrimitive(m.body))
        put("created_at", JsonPrimitive(Wire.isoOf(m.createdAt)))
        put("media", JsonArray(media))
    }
}

private fun answerFrame(app: App, agentId: String, subscribed: Subscription, payload: JsonObject): String? {
    val requestId = payload["requestId"]?.jsonPrimitive?.content ?: return null
    data class Req(val agentId: String, val projectId: String?, val updateId: String?, val state: String, val kind: String, val question: String, val answer: String?, val decidedAt: Long?)
    val r = app.db.one("SELECT agent_id, project_id, update_id, state, kind, question, answer, decided_at FROM approvals WHERE team_id = :team AND id = ?", requestId) {
        Req(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7),
            it.getLong(8).let { v -> if (it.wasNull()) null else v })
    } ?: return null
    if (r.agentId != agentId || !inScope(r.projectId, subscribed)) return null
    val state = when (r.state) { "approved", "rejected" -> "answered"; else -> r.state }
    if (state == "withdrawn") return null
    val value: JsonElement = r.answer?.let { (Wire.json.parseToJsonElement(it) as? JsonObject)?.get("value") }
        ?: if (r.state == "approved" || r.state == "rejected") JsonPrimitive(r.state == "approved") else JsonNull
    val (slug, name) = projectNames(app, r.projectId)
    val by = if (state == "answered") app.db.one("SELECT decided_by FROM approvals WHERE team_id = :team AND id = ?", requestId) { it.getString(1) } else null
    val byName = dev.wildware.feed.server.domain.People.teamName(app.db, by)
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
        // On a team, who answered (migration 055).
        if (byName != null) put("answered_by", buildJsonObject { put("user_id", JsonPrimitive(by)); put("name", JsonPrimitive(byName)) })
    })
}

private fun reactionFrame(app: App, agentId: String, subscribed: Subscription, payload: JsonObject): String? {
    if (payload["actor"]?.jsonPrimitive?.content != "human") return null
    val messageId = (payload["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val m = app.work.find(messageId) ?: return null
    if (m.deletedAt != null) return null
    val mine = "agent:$agentId"
    val concerns = m.author == mine ||
        (m.updateId != null && app.db.one("SELECT agent_id FROM updates WHERE team_id = :team AND id = ?", m.updateId) { it.getString(1) } == agentId) ||
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
        // On a team, who reacted (migration 055).
        val by = (payload["userId"] as? JsonPrimitive)?.content ?: dev.wildware.feed.server.domain.People.FOUNDER
        dev.wildware.feed.server.domain.People.teamName(app.db, by)?.let { who ->
            put("by", buildJsonObject { put("user_id", JsonPrimitive(by)); put("name", JsonPrimitive(who)) })
        }
    })
}

/**
 * A person ticked a line or voted on one of this agent's blocks (migration 057): it hears what, on
 * which block and card, and who. Its own changes, and anything on another agent's card, it does not.
 */
private fun blockFrame(app: App, agentId: String, subscribed: Subscription, payload: JsonObject): String? {
    if (payload["actor"]?.jsonPrimitive?.content != "human") return null
    val action = payload["action"]?.jsonPrimitive?.content ?: return null
    if (action != "check" && action != "vote") return null
    val card = payload["card"] as? JsonObject ?: return null
    val kind = card["kind"]?.jsonPrimitive?.content ?: return null
    val id = card["id"]?.jsonPrimitive?.content ?: return null
    val author = when (kind) {
        "update" -> app.db.one("SELECT agent_id FROM updates WHERE team_id = :team AND id = ?", id) { "agent:" + it.getString(1) }
        "request" -> app.db.one("SELECT agent_id FROM approvals WHERE team_id = :team AND id = ?", id) { "agent:" + it.getString(1) }
        else -> app.db.one("SELECT author FROM messages WHERE team_id = :team AND id = ?", id) { it.getString(1) }
    }
    if (author != "agent:$agentId") return null
    val projectId = (payload["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (!inScope(projectId, subscribed)) return null
    val (slug, name) = projectNames(app, projectId)
    val by = (payload["userId"] as? JsonPrimitive)?.content ?: dev.wildware.feed.server.domain.People.FOUNDER
    return Frames.named("block", buildJsonObject {
        put("type", JsonPrimitive("block"))
        put("action", JsonPrimitive(action))
        put("card", card)
        put("update_id", if (kind == "update") JsonPrimitive(id) else JsonNull)
        put("message_id", if (kind == "message") JsonPrimitive(id) else JsonNull)
        put("request_id", if (kind == "request") JsonPrimitive(id) else JsonNull)
        put("block_id", payload["blockId"] ?: JsonNull)
        put("block_type", payload["blockType"] ?: JsonNull)
        put("block_title", payload["blockTitle"] ?: JsonNull)
        if (action == "check") {
            put("item_id", payload["item"] ?: JsonNull)
            put("item_text", payload["itemText"] ?: JsonNull)
            put("done", payload["done"] ?: JsonNull)
        } else {
            put("options", payload["options"] ?: JsonArray(emptyList()))
        }
        put("project", slug)
        put("project_name", name)
        // On a team, who it was (migration 055).
        dev.wildware.feed.server.domain.People.teamName(app.db, by)?.let { who ->
            put("by", buildJsonObject { put("user_id", JsonPrimitive(by)); put("name", JsonPrimitive(who)) })
        }
    })
}
