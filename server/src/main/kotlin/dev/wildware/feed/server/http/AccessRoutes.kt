package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.ChannelAccess
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.access
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.forbidden
import dev.wildware.feed.server.domain.groups
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.notFound
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Who may read, post in and manage a channel or a project (spec/14): the Permissions panel's reads
 * and writes, what one person or agent can reach, and the log of changes.
 *
 * Anyone who can read a channel sees who else can. Changing it needs Manage there; agents never
 * change permissions (their tokens don't reach these routes). Every change is logged, and the apps
 * are told with an `access.updated` event that names nothing, so they read again what they may see.
 */
fun Route.accessRoutes(app: App) {
    fun RoutingContext.param(name: String) = call.parameters[name] ?: ""
    val access = { app.feed.access }

    fun panel(kind: String, id: String): JsonObject {
        val a = access()
        val (private, groupPrivate) = a.privacy(kind, id)
        val entries = if (kind == "group") a.groupEntries(id) else a.channelEntries(id)
        val mine = if (kind == "group") a.groupLevel(id) else a.level(id)
        return buildJsonObject {
            put(if (kind == "group") "groupId" else "projectId", id)
            put("private", private)
            if (kind == "channel") put("groupPrivate", groupPrivate)
            put("yourLevel", mine.wire)
            put("canManage", mine == ChannelAccess.Level.MANAGE)
            put("entries", JsonArray(entries.map { it.json }))
        }
    }

    fun changed() = app.bus.publish("access.updated", JsonObject(emptyMap()))

    suspend fun RoutingContext.setPrivate(kind: String, id: String) {
        val body = call.jsonObjectBody()
        val private = flag(body["private"], "private")
        access().setPrivate(kind, id, private, call.person)
        changed()
        call.respondOwner(200, panel(kind, id))
    }

    suspend fun RoutingContext.setLevel(kind: String, id: String) {
        val body = call.jsonObjectBody()
        if ("level" !in body) throw invalid("level must be none, read, post or manage, or null to inherit")
        val raw = nullableText(body["level"], "level")
        val level = raw?.let { ChannelAccess.Level.of(it) ?: throw invalid("level must be none, read, post or manage, or null to inherit") }
        val principal = when (param("kind")) {
            "member", "person" -> Viewer.MEMBER + param("id")
            "agent" -> Viewer.AGENT + param("id")
            else -> throw invalid("kind must be member or agent")
        }
        access().setLevel(kind, id, principal, level, call.person)
        changed()
        call.respondOwner(200, panel(kind, id))
    }

    endpoint(
        "/api/projects/{reference}/access",
        HttpMethod.Get to {
            val project = app.feed.projects.resolve(param("reference"))
            call.respondOwner(200, panel("channel", project.id))
        },
        HttpMethod.Put to {
            val project = app.feed.projects.resolve(param("reference"))
            setPrivate("channel", project.id)
        },
    )
    endpoint(
        "/api/projects/{reference}/access/{kind}/{id}",
        HttpMethod.Put to {
            val project = app.feed.projects.resolve(param("reference"))
            setLevel("channel", project.id)
        },
    )
    endpoint(
        "/api/groups/{reference}/access",
        HttpMethod.Get to {
            val group = app.feed.groups.resolve(param("reference"))
            call.respondOwner(200, panel("group", group.id))
        },
        HttpMethod.Put to {
            val group = app.feed.groups.resolve(param("reference"))
            setPrivate("group", group.id)
        },
    )
    endpoint(
        "/api/groups/{reference}/access/{kind}/{id}",
        HttpMethod.Put to {
            val group = app.feed.groups.resolve(param("reference"))
            setLevel("group", group.id)
        },
    )

    // What one person can reach: yourself, or anyone for an admin. Only channels you can see are listed.
    endpoint(
        "/api/people/{id}/access",
        HttpMethod.Get to {
            val id = param("id")
            val me = call.person
            val person = app.people.find(id)?.takeIf { it.active } ?: throw notFound("no such person: $id")
            if (id != me.id && me.role.rank < People.Role.ADMIN.rank) throw forbidden("only an admin can see what someone else can reach")
            call.respondOwner(200, buildJsonObject {
                put("memberId", person.id)
                put("role", person.role.wire)
                put("channels", JsonArray(access().reachOf(Viewer.MEMBER + person.id)))
            })
        },
    )
    endpoint(
        "/api/agents/{id}/access",
        HttpMethod.Get to {
            val agent = app.agents.find(param("id"))?.takeIf { it.revokedAt == null } ?: throw notFound("no such agent: ${param("id")}")
            call.respondOwner(200, buildJsonObject {
                put("agentId", agent.id)
                put("channels", JsonArray(access().reachOf(Viewer.AGENT + agent.id)))
            })
        },
    )

    // The log of every change, newest first, for owners and admins.
    endpoint(
        "/api/access/audit",
        HttpMethod.Get to {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val entries = access().auditLog(limit, before)
            call.respondOwner(200, buildJsonObject {
                put("entries", JsonArray(entries))
                put("nextCursor", if (entries.size == limit) (entries.last()["seq"] as JsonPrimitive) else JsonNull)
            })
        },
    )
}
