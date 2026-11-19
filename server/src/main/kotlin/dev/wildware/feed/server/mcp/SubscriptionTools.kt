package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Cards
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.jsTrim
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `subscribe`, `unsubscribe` and `list_subscriptions` (migration 052): what an agent follows, kept
 * by the server. Following a thread reaches the agent even in a project it does not follow.
 */
fun ToolRegistry.registerSubscriptionTools(app: App) {
    val subs = Subscriptions(app.db, app.clock)

    /** The one project or thread the call names. */
    class Target(val kind: String, val id: String, val projectId: String?, val thread: Subscriptions.Thread?)

    fun target(call: ToolCall): Target {
        fun arg(key: String) = call.args[key].jsString?.jsTrim()?.ifEmpty { null }
        val project = arg("project")
        val cards = listOfNotNull(arg("update_id"), arg("message_id"), arg("request_id"))
        if (project != null && cards.isNotEmpty() || cards.size > 1) {
            throw invalid("name one of: project, update_id, message_id, request_id — not more")
        }
        if (project != null) {
            val p = app.reads.findProject(project) ?: throw notFound("no such project: $project")
            return Target("project", p.id, p.id, null)
        }
        if (cards.isEmpty()) throw invalid("name one of: project, update_id, message_id, request_id")
        val card = Cards.oneCard(update = arg("update_id"), message = arg("message_id"), request = arg("request_id"))
        val (thread, projectId) = subs.threadAt(card)
        return Target(thread.kind, thread.id, projectId, thread)
    }

    fun slug(projectId: String?): JsonPrimitive? = projectId?.let { id ->
        app.db.one("SELECT slug FROM projects WHERE id = ?", id) { JsonPrimitive(it.getString(1)) }
    }

    fun view(t: Target): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(t.kind))
        put("id", JsonPrimitive(t.id))
        put("project", slug(t.projectId) ?: JsonNull)
        t.thread?.let { put("title", subs.title(it)?.let(::JsonPrimitive) ?: JsonNull) }
    }

    fun changed(agentId: String) =
        app.bus.publish("subscriptions.updated", buildJsonObject { put("subscriber", JsonPrimitive(Subscriptions.agent(agentId))) })

    fun following(agentId: String, t: Target): Boolean = if (t.thread == null) {
        subs.followedProjects(agentId)?.contains(t.id) == true
    } else {
        subs.threadState(Subscriptions.agent(agentId), t.thread) == true
    }

    register("subscribe") { call ->
        val t = target(call)
        val already = following(call.agent.id, t)
        val me = Subscriptions.agent(call.agent.id)
        if (t.thread == null) subs.followProject(me, t.id) else subs.followThread(me, t.thread, t.projectId)
        if (!already) changed(call.agent.id)
        val what = if (t.thread == null) "project ${slug(t.projectId)?.content}" else "that thread"
        val summary = if (already) "Already following $what." else "Following $what. New messages there reach you, @mention or not."
        ToolResult.ok(summary, buildJsonObject { put("subscribed", view(t)); put("already", JsonPrimitive(already)) })
    }

    register("unsubscribe") { call ->
        val t = target(call)
        val me = Subscriptions.agent(call.agent.id)
        val was = if (t.thread == null) subs.unfollowProject(me, t.id) else subs.unfollowThread(me, t.thread)
        if (was) changed(call.agent.id)
        val summary = if (was) {
            "Stopped following it. Only messages that @mention you reach you from there now."
        } else {
            "You were not following it, so nothing changed."
        }
        ToolResult.ok(summary, buildJsonObject { put("unsubscribed", view(t)); put("was_subscribed", JsonPrimitive(was)) })
    }

    register("list_subscriptions") { call ->
        val me = Subscriptions.agent(call.agent.id)
        val all = subs.followedProjects(call.agent.id) == null
        val projects = subs.projects(me)
        val threads = subs.threads(me, Subscriptions.LIST_LIMIT)
        val threadCount = subs.countThreads(me)
        val summary = buildString {
            append(if (all) "You hear every project (you have not followed one yet)" else "${projects.size} project${plural(projects.size)}")
            if (!all && projects.isNotEmpty()) append(": ${projects.joinToString(", ") { it.slug }}")
            append(", and $threadCount thread${plural(threadCount)}.")
        }
        ToolResult.ok(summary, buildJsonObject {
            put("all_projects", JsonPrimitive(all))
            put("projects", JsonArray(projects.map { p ->
                buildJsonObject {
                    put("id", JsonPrimitive(p.projectId))
                    put("slug", JsonPrimitive(p.slug))
                    put("name", JsonPrimitive(p.name))
                    put("since", JsonPrimitive(Wire.isoOf(p.since)))
                }
            }))
            put("threads", JsonArray(threads.map { f ->
                buildJsonObject {
                    put("kind", JsonPrimitive(f.thread.kind))
                    put("id", JsonPrimitive(f.thread.id))
                    put("project", slug(f.projectId) ?: JsonNull)
                    put("title", subs.title(f.thread)?.let(::JsonPrimitive) ?: JsonNull)
                    put("since", JsonPrimitive(Wire.isoOf(f.since)))
                }
            }))
            put("thread_count", JsonPrimitive(threadCount))
        })
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"
