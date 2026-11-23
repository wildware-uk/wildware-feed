package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.requiredText
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The people of the team (migration 055, docs/TEAMS.md §5): you, everyone, roles, removing and
 * leaving, and invites. Who may do what is [Access] plus the rules in [People].
 */
/** What DELETE /api/me needs in `confirm`, typed by the person: the app asks them to type it. */
const val DELETE_ACCOUNT_CONFIRM = "DELETE"

/** The server log's audit line for a deleted account, as the admin API writes its own: ids and a count, never a name or an email. */
private object AccountLog {
    fun deleted(accountId: String, teams: Collection<String>) = println("people: account deleted account=$accountId teams=${teams.size}")
}

fun Route.peopleRoutes(app: App) {
    fun changed() = app.bus.publish("people.updated", buildJsonObject { put("count", app.people.activeCount()) })

    /** Agents revoked with the person who let them in: their sessions end, and the apps hear it. */
    fun agentsRevoked(ids: List<String>) {
        for (id in ids) {
            app.sessions.endAll(id)
            app.bus.publish("agent.revoked", buildJsonObject { put("agentId", id) })
        }
    }

    endpoint(
        "/api/me",
        HttpMethod.Get to {
            call.respondOwner(200, buildJsonObject { put("person", personView(call.person, email = true)); put("team", app.people.isTeam()) })
        },
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            fun optional(key: String) = if (key in body) body[key].jsString ?: throw invalid("$key must be a string") else null
            val unknown = body.keys - setOf("name", "email", "password", "currentPassword")
            if (unknown.isNotEmpty()) throw invalid("a change to you takes name, email, password and currentPassword, not ${unknown.joinToString(", ")}")
            val update = { app.people.updateMe(call.person, optional("name"), optional("email"), optional("password"), optional("currentPassword")) }
            // A new email or password checks the current one: password work, so it waits its turn.
            val me = if ("email" in body || "password" in body) Passwords.gate.run(update) else update()
            if ("name" in body) changed()
            call.respondOwner(200, buildJsonObject { put("person", personView(me, email = true)) })
        },
        HttpMethod.Delete to {
            // Deleting your own account (App Store guideline 5.1.1(v)): typed confirmation, and the
            // password when the account has one. An owner is refused until they hand the team on.
            val body = call.jsonObjectBody()
            val unknown = body.keys - setOf("confirm", "password")
            if (unknown.isNotEmpty()) throw invalid("deleting your account takes confirm and password, not ${unknown.joinToString(", ")}")
            if (body["confirm"].jsString != DELETE_ACCOUNT_CONFIRM) throw invalid("confirm must be \"$DELETE_ACCOUNT_CONFIRM\"")
            val password = body["password"]?.takeIf { it !is JsonNull }?.let { it.jsString ?: throw invalid("password must be a string") }
            val gone = Passwords.gate.run { app.people.deleteAccount(call.person, password) }
            for ((team, devices) in gone.devicesByTeam) {
                dev.wildware.feed.server.db.Tenant.with(team) {
                    for (id in devices) app.bus.publish("device.revoked", buildJsonObject { put("deviceId", id) })
                    agentsRevoked(gone.agentsByTeam[team].orEmpty())
                    changed()
                }
            }
            AccountLog.deleted(gone.accountId, gone.devicesByTeam.keys)
            call.respondOwner(200, buildJsonObject { put("deleted", true) })
        },
    )

    endpoint(
        "/api/people",
        HttpMethod.Get to {
            // Everyone sees who is in the team; emails are for owners and admins.
            val admin = call.person.role.rank >= People.Role.ADMIN.rank
            // When each person last used the app (any of their devices), and whether that is
            // within the window that shows an agent as online.
            val seen = app.db.query("SELECT user_id, max(last_seen_at) FROM owner_devices WHERE team_id = :team AND last_seen_at IS NOT NULL GROUP BY user_id") {
                it.getString(1) to it.getLong(2)
            }.toMap()
            val now = app.clock()
            call.respondOwner(200, buildJsonObject {
                put("people", JsonArray(app.people.list().map { p ->
                    val last = seen[p.id]
                    JsonObject(personView(p, email = admin || p.id == call.person.id).jsonObject + buildJsonObject {
                        put("lastSeenAt", last?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
                        put("online", p.removedAt == null && last != null && now - last <= dev.wildware.feed.server.domain.Sessions.PRESENCE_WINDOW_MS)
                    })
                }))
            })
        },
    )

    endpoint(
        "/api/people/{id}",
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            if (body.keys.toList() != listOf("role")) throw invalid("a change to a person sets role and nothing else")
            val role = People.Role.of(body["role"].jsString) ?: throw invalid("role must be owner, admin or member")
            val letIn = app.agents.letInBy(call.parameters["id"] ?: "")
            val person = app.people.setRole(call.person, call.parameters["id"] ?: "", role)
            if (person.role.rank < People.Role.ADMIN.rank) agentsRevoked(letIn)
            changed()
            call.respondOwner(200, buildJsonObject { put("person", personView(person, email = true)) })
        },
        HttpMethod.Delete to {
            val letIn = app.agents.letInBy(call.parameters["id"] ?: "")
            val (person, devices) = app.people.remove(call.person, call.parameters["id"] ?: "")
            agentsRevoked(letIn)
            for (id in devices) app.bus.publish("device.revoked", buildJsonObject { put("deviceId", id) })
            changed()
            call.respondOwner(200, buildJsonObject { put("person", personView(person, email = true)) })
        },
    )

    endpoint(
        "/api/invites",
        HttpMethod.Get to {
            call.respondOwner(200, buildJsonObject { put("invites", JsonArray(app.people.pendingInvites().map(::inviteView))) })
        },
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val role = People.Role.of(body["role"].jsString ?: "member") ?: throw invalid("role must be owner, admin or member")
            val email = body["email"]?.takeIf { it !is JsonNull }?.let { it.jsString ?: throw invalid("email must be a string, or null") }
            val (invite, token) = app.people.invite(call.person, role, email)
            call.respondOwner(201, buildJsonObject {
                put("invite", inviteView(invite))
                // Shown once: only its hash is kept.
                put("token", token)
                put("link", "${app.teams.address()}/join/$token")
            })
        },
    )

    endpoint(
        "/api/invites/{id}",
        HttpMethod.Delete to {
            call.respondOwner(200, buildJsonObject { put("revoked", app.people.revokeInvite(call.person, call.parameters["id"] ?: "")) })
        },
    )

    // ---- someone holding an invite, with no key yet (public) ----------------------------------------

    /** Throttled per address: a token is unguessable, but nobody gets to try many. */
    suspend fun io.ktor.server.routing.RoutingContext.throttled(): Boolean {
        val verdict = app.inviteLimiter.take(call.limiterAddress(app.config))
        if (verdict.allowed) return false
        call.respondJson(
            429, Wire.error("rate_limited", "Too many tries. Try again later."), Wire.JSON_UTF8,
            "Cache-Control" to "no-store", "Retry-After" to verdict.retryAfterSeconds.toString(),
        )
        return true
    }

    fun tokenOf(body: JsonObject): String = body["token"].jsString?.let(::inviteToken) ?: throw invalid("token must be the invite's code or link")

    endpoint(
        "/api/invite/peek",
        HttpMethod.Post to {
            if (throttled()) return@to
            val invite = app.people.peek(tokenOf(call.jsonObjectBody(BodyLimits.PUBLIC)))
            if (invite == null) {
                call.respondOwner(404, Wire.error("invalid_invite", "That invite has expired, was used already, or isn't right. Ask for a new one."))
            } else {
                call.respondOwner(200, buildJsonObject {
                    put("invite", buildJsonObject {
                        put("role", invite.role.wire)
                        put("email", invite.email)
                        put("invitedBy", app.people.nameOf(invite.createdBy))
                        put("expiresAt", Wire.isoOf(invite.expiresAt))
                    })
                })
            }
        },
    )

    endpoint(
        "/api/invite/accept",
        HttpMethod.Post to {
            if (throttled()) return@to
            val body = call.jsonObjectBody(BodyLimits.PUBLIC)
            fun text(key: String) = body[key].jsString ?: throw invalid("$key must be a string")
            val deviceName = requiredText(text("deviceName"), "deviceName", 100)
            val code = tokenOf(body)
            val person = Passwords.gate.run { app.people.accept(code, text("name"), text("email"), text("password")) }
            val (device, token) = app.devices.mint(deviceName, person.id)
            changed()
            val minted = buildJsonObject {
                put("device", buildJsonObject {
                    put("id", device.id)
                    put("name", device.name)
                    put("createdAt", Wire.isoOf(device.createdAt))
                })
                put("token", if (body["cookie"].jsBoolean == true) "" else token)
                put("person", personView(person, email = true))
            }
            if (body["cookie"].jsBoolean == true) call.response.headers.append("Set-Cookie", OwnerCookie.set(token, app.config, call))
            call.respondOwner(201, minted)
        },
    )

    // The link an invite is sent as: a page that opens the app, or shows the code to paste into it.
    endpoint(
        "/join/{token}",
        HttpMethod.Get to {
            val token = call.parameters["token"]?.takeIf { dev.wildware.feed.server.auth.Tokens.wellFormed(it) }
            if (token == null) {
                call.respondRaw(404, "Not Found", "text/plain;charset=utf-8")
            } else {
                call.respondRaw(
                    200, JoinPage.html(app.teams.address(), token, webApp = app.web != null), "text/html;charset=utf-8",
                    "Cache-Control" to "no-store",
                    "Referrer-Policy" to "no-referrer",
                    "X-Content-Type-Options" to "nosniff",
                    "X-Frame-Options" to "DENY",
                    "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                )
            }
        },
    )
}

/** The invite's code from what someone pasted: the code itself, or a `/join/<code>` or `agents://invite` link. */
internal fun inviteToken(pasted: String): String? {
    val text = pasted.trim()
    if (dev.wildware.feed.server.auth.Tokens.wellFormed(text)) return text
    Regex("/join/([A-Za-z0-9_-]{43})(?:[/?#]|$)").find(text)?.let { return it.groupValues[1] }
    Regex("[?&]token=([A-Za-z0-9_-]{43})(?:&|$)").find(text)?.let { return it.groupValues[1] }
    return null
}

/** A person as the apps see them; [email] only for yourself, and for owners and admins. */
fun personView(p: People.Person, email: Boolean): JsonElement = buildJsonObject {
    put("id", p.id)
    put("name", p.name)
    put("handle", p.handle)
    put("role", p.role.wire)
    put("founder", p.founder)
    if (email) put("email", p.email)
    put("createdAt", Wire.isoOf(p.createdAt))
    put("removedAt", p.removedAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
}

fun inviteView(i: People.Invite): JsonElement = buildJsonObject {
    put("id", i.id)
    put("email", i.email)
    put("role", i.role.wire)
    put("createdBy", i.createdBy)
    put("createdAt", Wire.isoOf(i.createdAt))
    put("expiresAt", Wire.isoOf(i.expiresAt))
}

/** The `/join/<code>` page: no script, nothing loaded from anywhere, the code shown to copy. */
object JoinPage {
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * [webApp]: this server also serves the web app, so the page offers it too. The code rides in
     * the address's fragment, which a browser never sends to any server; the app reads it and
     * opens its join screen with it filled in.
     */
    fun html(base: String, token: String, webApp: Boolean = false): String {
        val open = "agents://invite?server=${java.net.URLEncoder.encode(base, Charsets.UTF_8)}&token=$token"
        val inBrowser = if (webApp) "\n<p><a href=\"${escape("${base.trimEnd('/')}/#invite=$token")}\">Or join in this browser</a></p>" else ""
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>Join the team on Wildware Feed</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;color:#1d1d1f;background:#fff}
@media (prefers-color-scheme:dark){body{color:#f5f5f7;background:#111}code{background:#222!important}}
a.b{display:inline-block;padding:.7rem 1.2rem;border-radius:.6rem;background:#2f6fed;color:#fff;text-decoration:none;font-weight:600}
code{display:block;word-break:break-all;padding:.8rem;border-radius:.5rem;background:#f2f2f5;font-size:.95rem;user-select:all}</style></head>
<body><h1>You're invited</h1>
<p>Someone has invited you to their team on Wildware Feed.</p>
<p><a class="b" href="${escape(open)}">Open in Wildware Feed</a></p>$inBrowser
<p>Or open the app, choose <b>I have an invite</b>, and paste this server address and code:</p>
<code>${escape(base)}</code><p></p><code>${escape(token)}</code>
<p>The code works once and expires in 7 days.</p></body></html>
"""
    }
}
