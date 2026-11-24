package dev.wildware.feed.server.http

import dev.wildware.feed.server.domain.Devices
import dev.wildware.feed.server.domain.People
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Who may call each owner route (docs/TEAMS.md §1, §10). Every route behind the guard names the
 * least role it needs here; a route with no line is refused to everyone, and a test reads the
 * router's routes and fails for any that has none. Rules about *which* thing (your own device, your
 * own message, a person below you) are the domain's, on top of this.
 */
object Access {
    private val M = People.Role.MEMBER
    private val A = People.Role.ADMIN
    private val O = People.Role.OWNER

    val rules: Map<String, People.Role> = mapOf(
        // Reading the feed, and the live stream.
        "GET /api/summary" to M,
        "GET /api/plan" to M,
        "GET /api/snapshot" to M,
        "GET /api/snapshot/updates" to M,
        "GET /api/snapshot/requests" to M,
        "GET /api/snapshot/agents" to M,
        "GET /api/messages" to M,
        "GET /api/threads/{id}" to M,
        "GET /api/projects/{reference}/tags" to M,
        "GET /api/stream" to M,
        "GET /media/{id}/{variant}" to M,
        "GET /apps/{view}" to M,
        "GET /api/groups" to M,
        "GET /api/agents" to M,
        "GET /api/settings/media-retention" to M,
        "GET /api/board" to M,
        "GET /api/settings/board" to M,
        "GET /api/cards/{kind}/{id}/blocks" to M,
        "GET /api/cards/{kind}/{id}/assignees" to M,
        "GET /api/projects/{reference}/agents" to M,
        "GET /api/groups/{reference}/agents" to M,

        // Taking part: posting, reacting, answering, tagging, pins, work states, app state.
        "POST /api/messages" to M,
        "DELETE /api/messages/{id}" to M, // your own; anyone's for an admin (Messages.delete)
        "PATCH /api/messages/{id}" to M, // your own only, whatever your role (Messages.edit)
        "POST /api/messages/{id}/reactions" to M,
        "POST /api/media" to M,
        "PATCH /api/updates/{id}" to M, // pinned, and nothing else
        "POST /api/projects/{reference}/tags" to M,
        "POST /api/cards/{kind}/{id}/pin" to M,
        "POST /api/cards/{kind}/{id}/status" to M,
        "POST /api/cards/{kind}/{id}/board" to M, // a finished card off the board, or back: Post on its channel
        "POST /api/cards/{kind}/{id}/assignees" to M, // who it is assigned to: Post on its channel
        "POST /api/cards/{kind}/{id}/reactions" to M,
        "POST /api/cards/{kind}/{id}/tags" to M,
        "POST /api/cards/{kind}/{id}/fields" to M,
        "POST /api/requests/{id}/answer" to M,
        "DELETE /api/requests/{id}" to M,
        "POST /api/apps/{view}/state" to M,
        "POST /api/cards/{kind}/{id}/blocks/{block}/check" to M,
        "POST /api/cards/{kind}/{id}/blocks/{block}/vote" to M,
        "GET /api/feedback" to M,
        "POST /api/feedback" to M,

        // A channel's docs (spec/17): Read on the channel reads them, Post changes them (Docs).
        "GET /api/projects/{reference}/docs" to M,
        "POST /api/projects/{reference}/docs" to M,
        "GET /api/projects/{reference}/docs/by-title" to M,
        "GET /api/docs/{id}" to M,
        "PUT /api/docs/{id}" to M,
        "DELETE /api/docs/{id}" to M,
        "GET /api/docs/{id}/versions" to M,
        "GET /api/docs/{id}/versions/{version}" to M,
        "POST /api/docs/{id}/restore" to M,

        // Your own follows, bell and devices.
        "GET /api/cards/{kind}/{id}/followers" to M,
        "PUT /api/cards/{kind}/{id}/follow" to M,
        "PUT /api/projects/{reference}/subscription" to M,
        "PUT /api/groups/{reference}/subscription" to M,
        "GET /api/subscriptions" to M,
        "GET /api/notifications" to M,
        "POST /api/notifications/seen" to M,
        "POST /api/notifications/cards-seen" to M,
        "POST /api/notifications/handled" to M,
        "GET /api/devices" to M,
        "POST /api/devices" to M,
        "DELETE /api/devices/{id}" to M, // your own; a member's for an admin, anyone's for an owner (DeviceRoutes)
        "PUT /api/devices/current/apns" to M,
        "PUT /api/devices/current/live-activity" to M,
        "PUT /api/devices/current/alert-projects" to M,
        "GET /api/devices/current/alert-prefs" to M,
        "PUT /api/devices/current/alert-prefs" to M,

        // You, and the people of the team.
        "GET /api/me" to M,
        "PATCH /api/me" to M,
        "DELETE /api/me" to M, // your own account; People.deleteAccount refuses an owner
        "GET /api/people" to M,
        "DELETE /api/people/{id}" to M, // leaving yourself; others by role (People.remove)
        "PATCH /api/people/{id}" to O,
        "GET /api/invites" to A,
        "POST /api/invites" to A, // admins invite members only (People.invite)
        "DELETE /api/invites/{id}" to A,

        // Running the team: channels, projects, tags, retention, agents, secrets.
        "POST /api/projects" to A,
        "PATCH /api/projects/{reference}" to M, // Manage on the channel (spec/15): admins by default
        "PUT /api/projects/order" to M, // Manage on each channel, and on a project it moves into (Projects.arrange)
        "PUT /api/groups/order" to A,

        "POST /api/groups" to A,
        "PATCH /api/groups/{reference}" to M, // Manage on the project (spec/15)
        "DELETE /api/groups/{reference}" to A,
        "PUT /api/projects/{reference}/agents/{agentId}" to M, // which agents hear a channel: Manage on it (spec/15)
        "PUT /api/groups/{reference}/agents/{agentId}" to M,
        "PATCH /api/tags/{id}" to A,
        "DELETE /api/tags/{id}" to A,
        "DELETE /api/updates/{id}" to A,
        "PUT /api/settings/media-retention" to A,
        "PUT /api/settings/board" to A,
        "PATCH /api/agents/{id}" to A,
        "DELETE /api/agents/{id}" to A,
        "GET /api/agents/{id}/connections" to A,
        "DELETE /api/agents/{id}/connections/{connection}" to A,
        "GET /api/pairings" to A,
        "POST /api/pairings/{id}/approve" to A,
        "POST /api/pairings/{id}/deny" to A,
        "GET /api/secrets" to A,
        "POST /api/secrets" to A,
        "GET /api/secrets/audit" to A,
        "PATCH /api/secrets/{id}" to A,
        "DELETE /api/secrets/{id}" to A,
        "POST /api/secrets/{id}/reveal" to A,
        "POST /api/requests/{id}/secret" to A,
        "POST /api/cards/{kind}/{id}/secrets/{name}/reveal" to A, // a secret an agent attached to a post

        // Permissions (spec/15): anyone who can read a channel sees who else can; changing it needs Manage there.
        "GET /api/projects/{reference}/access" to M,
        "PUT /api/projects/{reference}/access" to M,
        "PUT /api/projects/{reference}/access/{kind}/{id}" to M,
        "GET /api/groups/{reference}/access" to M,
        "PUT /api/groups/{reference}/access" to M,
        "PUT /api/groups/{reference}/access/{kind}/{id}" to M,
        "GET /api/people/{id}/access" to M, // yourself; anyone for an admin
        "GET /api/agents/{id}/access" to M,
        "GET /api/access/audit" to A,
    )

    /** The least role [method] on [pattern] needs; HEAD is GET's. Null: nobody. */
    fun rule(method: String, pattern: String): People.Role? = rules["${if (method == "HEAD") "GET" else method} $pattern"]
}

/** Who is calling an owner route: their device and the person it belongs to, read fresh each request. */
data class Caller(val device: Devices.Device, val person: People.Person)

internal val CallerKey = AttributeKey<Caller>("Caller")

/** The caller of an owner route; the guard has always set it before a route runs. */
val ApplicationCall.caller: Caller get() = attributes[CallerKey]

val ApplicationCall.person: People.Person get() = caller.person
