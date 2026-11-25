package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.github.CardPrs
import dev.wildware.feed.server.github.Installation
import dev.wildware.feed.server.github.PrState
import dev.wildware.feed.server.github.Webhook
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GitHub on cards (migration 079, docs/GITHUB.md): GitHub's webhook and the install's way back,
 * both public and for every team, and the team's GitHub settings.
 */
fun Route.githubRoutes(app: App) {
    // Every event, for every team: signed under the app's webhook secret, or refused.
    endpoint(
        WEBHOOK,
        HttpMethod.Post to {
            val github = app.github
            if (github == null) {
                call.respondJson(404, Wire.error("not_found", "GitHub isn't set up on this server"))
                return@to
            }
            val body = call.receiveAtMost(WEBHOOK_MAX) ?: throw dev.wildware.feed.server.http.payloadTooLarge(WEBHOOK_MAX)
            if (!PrState.signed(github.webhookSecret, body, call.request.headers["X-Hub-Signature-256"])) {
                call.respondJson(401, Wire.error("unauthenticated", "the signature doesn't match"))
                return@to
            }
            val event = call.request.headers["X-GitHub-Event"].orEmpty()
            if (event == "ping") {
                call.respondJson(200, buildJsonObject { put("ok", true) })
                return@to
            }
            val payload = runCatching { Wire.json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            val change = payload?.let { Webhook.parse(event, it) }
            var touched = 0
            if (change is Webhook.Uninstalled) {
                for (team in app.githubInstalls.removeEverywhere(change.installationId)) {
                    Tenant.with(team) { app.feed.publish("github.updated", buildJsonObject { put("installationId", change.installationId) }) }
                }
            } else if (change != null) {
                // Only the teams that linked this installation, each in its own team: never anyone else's cards.
                for (team in app.githubInstalls.teamsFor(change.installationId)) {
                    val t = app.teams.find(team) ?: continue
                    if (!t.open || t.suspended) continue
                    touched += Tenant.with(team) { CardPrs(app.feed).apply(change) }
                }
            }
            call.respondJson(200, buildJsonObject { put("ok", true); put("cards", touched) })
        },
    )

    // GitHub sends the owner's browser back here after the install, with a code and the state.
    endpoint(
        CALLBACK,
        HttpMethod.Get to {
            val github = app.github
            val params = call.request.queryParameters
            val asked = github?.readState(params["state"])
            if (github == null || asked == null) {
                call.page(400, "That link has expired", "Go back to the app and choose Connect GitHub again.")
                return@to
            }
            val (team, member) = asked
            val t = app.teams.find(team)?.takeIf { it.open }
            val code = params["code"]
            if (t == null || code.isNullOrBlank()) {
                call.page(400, "GitHub didn't finish connecting", "Go back to the app and choose Connect GitHub again.")
                return@to
            }
            val result = withContext(Tenant.element(team)) {
                val person = app.people.find(member)?.takeIf { it.active && it.role.rank >= People.Role.OWNER.rank }
                    ?: return@withContext null to "Only a team owner can connect GitHub."
                val seen = withContext(Dispatchers.IO) {
                    val token = github.api.userToken(code) ?: return@withContext null
                    github.api.userInstallations(token)
                } ?: return@withContext null to "GitHub didn't accept the sign-in. Try Connect GitHub again."
                // Only an installation this person can see on GitHub: the one just made, or all they can see.
                val wanted = params["installation_id"]?.toLongOrNull()
                val chosen: List<Installation> = if (wanted != null) seen.filter { it.id == wanted } else seen
                if (chosen.isEmpty()) return@withContext null to "Your GitHub account can't see that installation of the app."
                chosen.forEach { app.githubInstalls.add(it, Viewer.MEMBER + person.id) }
                app.feed.publish("github.updated", buildJsonObject { put("installationId", chosen.first().id) })
                chosen to null
            }
            val (linked, problem) = result
            if (linked == null) {
                call.page(403, "GitHub isn't connected", problem ?: "Try again.")
            } else {
                call.page(200, "GitHub is connected", "${linked.joinToString(", ") { it.account }} now shows pull requests on ${t.name}'s cards. You can close this tab.")
            }
        },
    )

    endpoint(
        "/api/github",
        HttpMethod.Get to {
            val available = app.github != null
            call.respondOwner(200, buildJsonObject {
                put("available", available)
                put("installations", JsonArray(if (available) app.githubInstalls.list().map { it.json } else emptyList()))
                put("canManage", call.person.role.rank >= People.Role.OWNER.rank)
            })
        },
    )

    endpoint(
        "/api/github/connect",
        HttpMethod.Post to {
            val github = app.github ?: throw DomainError("unavailable", "GitHub isn't set up on this server", 400)
            call.respondOwner(200, buildJsonObject { put("url", github.installUrl(Tenant.id, call.person.id)) })
        },
    )

    endpoint(
        "/api/github/installations/{id}",
        HttpMethod.Delete to {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw notFound("no such installation")
            if (!app.githubInstalls.remove(id)) throw notFound("no such installation")
            app.feed.publish("github.updated", buildJsonObject { put("installationId", id) })
            call.respondOwner(200, buildJsonObject { put("removed", true) })
        },
    )
}

/** A small page for the browser GitHub sent back. */
private suspend fun io.ktor.server.application.ApplicationCall.page(status: Int, title: String, text: String) {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    respondRaw(
        status,
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${esc(title)}</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:15vh auto;padding:0 1rem;color:#222}" +
            "@media(prefers-color-scheme:dark){body{background:#111;color:#eee}}</style></head>" +
            "<body><h1>${esc(title)}</h1><p>${esc(text)}</p></body></html>",
        "text/html;charset=utf-8",
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'",
    )
}

const val WEBHOOK = "/github/webhook"
const val CALLBACK = "/github/callback"
private const val WEBHOOK_MAX = 5 * 1024 * 1024
