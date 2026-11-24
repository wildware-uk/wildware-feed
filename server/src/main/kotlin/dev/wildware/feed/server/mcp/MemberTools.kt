package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.ChannelAccess
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.Profiles
import dev.wildware.feed.server.domain.Sessions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `list_members` and `set_profile` (migration 077, spec/13 "Profiles"): who is on the team and what
 * each says they do, so an agent @mentions whoever fits, an agent before a person.
 */
fun ToolRegistry.registerMemberTools(app: App) {
    register("list_members") { call ->
        val me = call.agent.id
        // A channel the agent can't read doesn't exist for it: resolve answers not_found.
        val channelId = call.channel()?.let { app.feed.projects.resolve(it).id }
        val access = ChannelAccess(app.db, app.clock)
        fun reads(principal: String) = channelId == null || access.canRead(principal, channelId)
        val now = app.clock()
        val window = Sessions.PRESENCE_WINDOW_MS
        val seen = app.db.query(
            "SELECT user_id, max(last_seen_at) FROM owner_devices WHERE team_id = :team AND last_seen_at IS NOT NULL GROUP BY user_id",
        ) { it.getString(1) to it.getLong(2) }.toMap()
        val live = app.db.query(
            "SELECT DISTINCT agent_id FROM sessions WHERE team_id = :team AND ended_at IS NULL AND last_heartbeat_at >= ?", now - window,
        ) { it.getString(1) }.toSet()

        val people = app.people.active().filter { reads("member:${it.id}") }.map { p ->
            buildJsonObject {
                put("kind", "person")
                put("id", p.id)
                put("name", p.name)
                put("handle", "@${p.handle}")
                put("role", p.profileRole)
                put("description", p.description)
                put("has_picture", p.avatarMediaId != null)
                put("online", seen[p.id]?.let { now - it <= window } == true)
                put("team_role", p.role.wire)
            }
        }
        val agents = app.agents.list(includeRevoked = false).filter { reads("agent:${it.id}") }.map { a ->
            buildJsonObject {
                put("kind", "agent")
                put("id", a.id)
                put("name", a.name)
                put("handle", "@${a.name}")
                put("role", a.profileRole)
                put("description", a.description)
                put("has_picture", a.avatarMediaId != null)
                put("online", a.id in live)
                if (a.id == me) put("you", true)
            }
        }
        val mine = app.profiles.ofAgent(me)
        val where = if (channelId != null) " who can read that channel" else ""
        val text = buildString {
            append("${people.size} ${if (people.size == 1) "person" else "people"} and ${agents.size} agent${if (agents.size == 1) "" else "s"}$where. ")
            append(MENTION_HINT)
            if (mine.description == null) append(" You have no description yet: set one with set_profile, saying what you do and when to contact you.")
        }
        ToolResult.ok(text, buildJsonObject {
            put("members", JsonArray(people + agents))
            put("people", people.size)
            put("agents", agents.size)
        })
    }

    register("set_profile") { call ->
        fun given(key: String): Given<String?>? = if (key in call.args) Given(call.args[key].jsString) else null
        val change = Profiles.Change(role = given("role"), description = given("description"), avatar = given("avatar_media_id"))
        if (change.empty) throw invalid("pass role, description, avatar_media_id, or more than one")
        val profile = app.profiles.setAgent(call.agent.id, change)
        app.bus.publish("agent.profile", buildJsonObject { put("agentId", call.agent.id) })
        ToolResult.ok(
            when {
                profile.description == null -> "Saved. You have no description now: others can't tell when to contact you."
                else -> "Saved. Your team sees this in list_members and on your profile in the app."
            },
            profileJson(profile),
        )
    }
}

/** What list_members, register_session and the instructions tell every agent before it @mentions anyone. */
const val MENTION_HINT =
    "Before you @mention someone, check list_members and pick whoever's description fits; prefer an agent responsible for the area over a person."

internal fun profileJson(profile: Profiles.Profile): JsonObject = buildJsonObject {
    put("role", profile.role)
    put("description", profile.description)
    put("avatar_media_id", profile.avatarMediaId)
}
