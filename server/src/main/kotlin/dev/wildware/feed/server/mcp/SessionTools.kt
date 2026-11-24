package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** register_session, heartbeat, end_session (spec/05-mcp.md §5, §7.13-7.15). */
fun ToolRegistry.registerSessionTools(app: App) {
    register("register_session") { call ->
        val session = app.sessions.register(call.agent.id, call.args["meta"] as? JsonObject)
        val interval = dev.wildware.feed.server.domain.Sessions.HEARTBEAT_INTERVAL_S
        // Who does what (migration 077): an agent with no description is told to write one.
        val profile = app.profiles.ofAgent(call.agent.id)
        val noProfile = if (profile.description == null) {
            " You have no description yet: call set_profile({role, description}) to say what you do and when to contact you."
        } else {
            ""
        }
        ToolResult.ok(
            "Registered session ${session.id} for agent \"${call.agent.name}\". " +
                "Call heartbeat({session_id}) at least every $interval seconds, " +
                "and end_session({session_id}) when you finish.$noProfile $MENTION_HINT",
            buildJsonObject {
                put("session_id", JsonPrimitive(session.id))
                put("heartbeat_interval_s", JsonPrimitive(interval))
                put("profile", profileJson(profile))
            },
        )
    }

    register("heartbeat") { call ->
        val counts = app.sessions.heartbeat(call.args.string("session_id"), call.agent.id)
        val waiting = listOf(
            counts.unreadMessages to "unread message",
            counts.openTasks to "open task",
            counts.pendingApprovals to "pending approval",
        ).filter { it.first > 0 }.map { (n, noun) -> "$n $noun${if (n == 1) "" else "s"}" }
        ToolResult.ok(
            if (waiting.isEmpty()) "Heartbeat recorded. Nothing is waiting for you; carry on."
            else "Heartbeat recorded. Waiting for you: ${waiting.joinToString(", ")}.",
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("unread_messages", JsonPrimitive(counts.unreadMessages))
                put("open_tasks", JsonPrimitive(counts.openTasks))
                put("pending_approvals", JsonPrimitive(counts.pendingApprovals))
            },
        )
    }

    register("end_session") { call ->
        val (session, ended) = app.sessions.end(call.args.string("session_id"), call.agent.id)
        ToolResult.ok(
            if (ended) "Ended session ${session.id}. Agent \"${call.agent.name}\" is no longer shown as working."
            else "Session ${session.id} was already ended; nothing to do.",
            buildJsonObject {
                put("session_id", JsonPrimitive(session.id))
                put("ended", JsonPrimitive(ended))
            },
        )
    }
}

/** A required string argument (already checked against the schema). */
fun JsonObject.string(key: String): String = (this[key] as JsonPrimitive).content

/** An optional string argument. */
fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
