package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Cards
import dev.wildware.feed.server.domain.access
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.forbidden
import dev.wildware.feed.server.domain.groups
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
 * `subscribe`, `unsubscribe` and `list_subscriptions` (migrations 052 and 053): what an agent follows,
 * kept by the server, at three levels: a group of channels (a project, in the app), a channel, a
 * thread. The most specific choice wins (spec/12-channels-and-projects.md). Following a thread
 * reaches the agent even in a channel it does not follow.
 *
 * `project` is the older name for `channel` here as in every tool, never the group.
 *
 * The owner can add an agent to a channel or project, or remove it (migration 060). An agent cannot
 * change that here: `subscribe` is refused where the owner removed it, until the owner adds it back.
 */
fun ToolRegistry.registerSubscriptionTools(app: App) {
    val subs = Subscriptions(app.db, app.clock)

    /** The one group, channel or thread the call names. [kind] is `group`, `channel` or a thread kind. */
    class Target(val kind: String, val id: String, val projectId: String?, val thread: Subscriptions.Thread?, val slug: String? = null)

    fun target(call: ToolCall): Target {
        fun arg(key: String) = call.args[key].jsString?.jsTrim()?.ifEmpty { null }
        val group = arg("group")
        val channel = call.channel()?.jsTrim()?.ifEmpty { null }
        val cards = listOfNotNull(arg("update_id"), arg("message_id"), arg("request_id"))
        if (listOfNotNull(group, channel).size + cards.size > 1) {
            throw invalid("name one of: group, channel, update_id, message_id, request_id — not more")
        }
        if (group != null) {
            val g = app.feed.groups.resolve(group)
            return Target("group", g.id, null, null, g.slug)
        }
        if (channel != null) {
            val p = app.reads.findProject(channel) ?: throw notFound("no such project: $channel")
            return Target("channel", p.id, p.id, null, p.slug)
        }
        if (cards.isEmpty()) throw invalid("name one of: group, channel, update_id, message_id, request_id")
        val card = Cards.oneCard(update = arg("update_id"), message = arg("message_id"), request = arg("request_id"))
        val (thread, projectId) = subs.threadAt(card)
        return Target(thread.kind, thread.id, projectId, thread)
    }

    fun slug(projectId: String?): JsonPrimitive? = projectId?.let { id ->
        app.db.one("SELECT slug FROM projects WHERE team_id = :team AND id = ?", id) { JsonPrimitive(it.getString(1)) }
    }

    fun view(t: Target, reader: String): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(t.kind))
        put("id", JsonPrimitive(t.id))
        if (t.kind == "group") {
            put("group", JsonPrimitive(t.slug))
        } else {
            put("channel", slug(t.projectId) ?: JsonNull)
            put("project", slug(t.projectId) ?: JsonNull)
        }
        t.thread?.let { put("title", subs.title(it, reader)?.let(::JsonPrimitive) ?: JsonNull) }
    }

    fun changed(agentId: String) =
        app.bus.publish("subscriptions.updated", buildJsonObject { put("subscriber", JsonPrimitive(Subscriptions.agent(agentId))) })

    fun following(agentId: String, t: Target): Boolean = when (t.kind) {
        "group" -> subs.groupState(Subscriptions.agent(agentId), t.id) == true
        "channel" -> t.id in subs.heardChannels(agentId)
        else -> subs.threadState(Subscriptions.agent(agentId), t.thread!!) == true
    }

    fun describe(t: Target) = when (t.kind) {
        "group" -> "project ${t.slug} (every channel in it, including ones added later)"
        "channel" -> "channel ${t.slug}"
        else -> "that thread"
    }

    /** Refuse following what the owner removed the agent from: only the owner can add it back. */
    fun refuseRemoved(agentId: String, t: Target) {
        val ask = "Only your owner can add you back, in the app; ask them if you need it. Messages there that @mention you still reach you."
        when (t.kind) {
            "group" -> if (subs.removedFromGroup(agentId, t.id)) throw forbidden("Your owner removed you from project ${t.slug}. $ask")
            "channel" -> when (subs.removedFrom(agentId, t.id)) {
                "channel" -> throw forbidden("Your owner removed you from channel ${t.slug}. $ask")
                "group" -> {
                    val group = app.db.one("SELECT g.slug FROM projects AS p JOIN project_groups AS g ON g.id = p.group_id WHERE p.team_id = :team AND p.id = ?", t.id) { it.getString(1) }
                    throw forbidden("Your owner removed you from project $group, which channel ${t.slug} is in. $ask")
                }
            }
        }
    }

    register("subscribe") { call ->
        val t = target(call)
        refuseRemoved(call.agent.id, t)
        val me = Subscriptions.agent(call.agent.id)
        val already = when (t.kind) {
            "group" -> subs.groupState(me, t.id) == true
            else -> following(call.agent.id, t)
        }
        when (t.kind) {
            "group" -> subs.followGroup(me, t.id)
            "channel" -> subs.followProject(me, t.id, chosen = true)
            else -> subs.followThread(me, t.thread!!, t.projectId)
        }
        if (!already) changed(call.agent.id)
        val summary = buildString {
            append(if (already) "Already following ${describe(t)}." else "Following ${describe(t)}. New messages there reach you, @mention or not.")
            if (t.kind == "group") {
                val left = subs.leftProjects(me).toSet()
                val skipped = app.db.query("SELECT id, slug FROM projects WHERE team_id = :team AND group_id = ? ORDER BY seq", t.id) { it.getString(1) to it.getString(2) }
                    .filter { it.first in left }.map { it.second }
                if (skipped.isNotEmpty()) append(" Except ${skipped.joinToString(", ")}, which you unsubscribed from; subscribe to them to hear them again.")
            }
        }
        ToolResult.ok(summary, buildJsonObject { put("subscribed", view(t, call.author)); put("already", JsonPrimitive(already)) })
    }

    register("unsubscribe") { call ->
        val t = target(call)
        val me = Subscriptions.agent(call.agent.id)
        val heardBefore = subs.followedProjects(call.agent.id)
        val followed = following(call.agent.id, t)
        when (t.kind) {
            "group" -> subs.unfollowGroup(me, t.id)
            "channel" -> subs.unfollowProject(me, t.id)
            else -> subs.unfollowThread(me, t.thread!!)
        }
        // Leaving a group the agent never joined can still quiet channels it only posted in.
        val was = followed || heardBefore.toSet() != subs.followedProjects(call.agent.id).toSet()
        if (was) changed(call.agent.id)
        val summary = when {
            was -> "Stopped following it. Only messages that @mention you, and threads you follow, reach you from there now."
            else -> "You were not following it, so nothing changed."
        }
        ToolResult.ok(summary, buildJsonObject { put("unsubscribed", view(t, call.author)); put("was_subscribed", JsonPrimitive(was)) })
    }

    register("list_subscriptions") { call ->
        val me = Subscriptions.agent(call.agent.id)
        val heard = subs.followedProjects(call.agent.id)
        val own = subs.projects(me).associateBy { it.projectId }
        val groups = subs.groups(me)
        val threads = subs.threads(me, Subscriptions.LIST_LIMIT)
        val threadCount = subs.countThreads(me)
        val followedGroups = groups.map { it.groupId }.toSet()
        val removed = app.db.query("SELECT id, slug FROM projects WHERE team_id = :team${app.feed.access.notHidden("id")} ORDER BY seq") { it.getString(1) to it.getString(2) }
            .filter { subs.removedFrom(call.agent.id, it.first) != null }
        val channels = heard.mapNotNull { id ->
            app.db.one("SELECT p.slug, p.name, p.group_id, g.slug FROM projects AS p LEFT JOIN project_groups AS g ON g.id = p.group_id WHERE p.team_id = :team AND p.id = ?", id) {
                Channel(id, it.getString(1), it.getString(2), it.getString(4), own[id], it.getString(3) in followedGroups)
            }
        }
        val summary = buildString {
            append("${channels.size} channel${plural(channels.size)}")
            if (channels.isNotEmpty()) append(": ${channels.joinToString(", ") { it.slug }}")
            if (groups.isNotEmpty()) append(", through ${groups.size} project${plural(groups.size)} (${groups.joinToString(", ") { it.slug }}) and your own choices")
            append(", and $threadCount thread${plural(threadCount)}.")
            if (channels.isEmpty()) append(" You hear no channel: only threads you follow and messages that @mention you. Subscribe to the channels you work in.")
            if (removed.isNotEmpty()) {
                append(" Your owner removed you from ${removed.joinToString(", ") { it.second }}: only messages that @mention you reach you from there.")
            }
        }
        ToolResult.ok(summary, buildJsonObject {
            // Kept for older readers: never true now. An agent hears only what it follows.
            put("all_projects", JsonPrimitive(false))
            // Kept from before channels: the channels followed on their own.
            put("projects", JsonArray(own.values.map { p ->
                buildJsonObject {
                    put("id", JsonPrimitive(p.projectId))
                    put("slug", JsonPrimitive(p.slug))
                    put("name", JsonPrimitive(p.name))
                    put("since", JsonPrimitive(Wire.isoOf(p.since)))
                }
            }))
            put("groups", JsonArray(groups.map { g ->
                buildJsonObject {
                    put("id", JsonPrimitive(g.groupId))
                    put("slug", JsonPrimitive(g.slug))
                    put("name", JsonPrimitive(g.name))
                    put("since", JsonPrimitive(Wire.isoOf(g.since)))
                }
            }))
            put("channels", JsonArray(channels.map { c ->
                buildJsonObject {
                    put("id", JsonPrimitive(c.id))
                    put("slug", JsonPrimitive(c.slug))
                    put("name", JsonPrimitive(c.name))
                    put("group", c.group?.let(::JsonPrimitive) ?: JsonNull)
                    // How it is heard: chosen with subscribe, through its group, or from posting there.
                    put("via", JsonPrimitive(c.via))
                }
            }))
            put("threads", JsonArray(threads.map { f ->
                buildJsonObject {
                    put("kind", JsonPrimitive(f.thread.kind))
                    put("id", JsonPrimitive(f.thread.id))
                    put("channel", slug(f.projectId) ?: JsonNull)
                    put("project", slug(f.projectId) ?: JsonNull)
                    put("title", subs.title(f.thread, call.author)?.let(::JsonPrimitive) ?: JsonNull)
                    put("since", JsonPrimitive(Wire.isoOf(f.since)))
                }
            }))
            put("thread_count", JsonPrimitive(threadCount))
            // Channels the owner keeps the agent out of (migration 060); only the owner can add it back.
            put("removed_by_owner", JsonArray(removed.map { JsonPrimitive(it.second) }))
        })
    }
}

/** A channel the agent hears, and why: the most specific reason wins, as in [Subscriptions.followedProjects]. */
private class Channel(
    val id: String, val slug: String, val name: String, val group: String?,
    val own: Subscriptions.ProjectFollow?, val groupFollowed: Boolean,
) {
    val via get() = when {
        own?.chosen == true -> "chosen"
        groupFollowed -> "group"
        else -> "posted"
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"
