package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.requiredText
import io.ktor.http.HttpMethod
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The other ends of the admin API's one-time secrets (docs/HOSTING.md §5, §7), on the team's own
 * address and with no admin key:
 *
 * - a sign-in ticket the control plane got for someone, traded for a device key by the app
 *   (`POST /api/devices/ticket`) or for the web app's cookie by a browser (`POST /auth/ticket`);
 * - a setup link, where someone the admin API added sets their first password (`/setup/<code>`).
 *
 * A ticket works once, for a minute, on its own team's address only; one that doesn't work is
 * `401 invalid_ticket` however it failed. Both are rate limited like a password sign-in.
 */
fun Route.ticketRoutes(app: App) {
    endpoint(
        "/api/devices/ticket",
        HttpMethod.Post to {
            val body = call.jsonObjectBody(BodyLimits.PUBLIC)
            val ticket = (body["ticket"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("ticket must be a string")
            val name = (body["deviceName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("deviceName must be a string")
            requiredText(name, "deviceName", 100)
            val person = call.redeemTicket(app, ticket)
            if (person == null) {
                call.respondJson(401, Wire.error("invalid_ticket", "That sign-in has expired or was used already. Sign in again."), Wire.JSON_UTF8, "Cache-Control" to "no-store")
                return@to
            }
            val (device, token) = app.devices.mint(name, person.id)
            call.respondOwner(201, buildJsonObject {
                put("device", buildJsonObject {
                    put("id", JsonPrimitive(device.id))
                    put("name", JsonPrimitive(device.name))
                    put("createdAt", JsonPrimitive(Wire.isoOf(device.createdAt)))
                })
                put("token", JsonPrimitive(token))
            })
        },
    )
    endpoint(
        "/auth/ticket",
        // Posted from the control plane's page, and from nowhere else: otherwise any website could
        // post its own ticket and sign a visitor in to the wrong team (docs/HOSTING.md §7).
        HttpMethod.Post to {
            val allowed = app.config.controlPlaneOrigin
                ?: return@to call.respondRaw(404, "Not Found", "text/plain;charset=utf-8")
            if (call.request.headers["Origin"] != allowed) {
                return@to call.respondRaw(403, "This sign-in must come from the sign-in page.", "text/plain;charset=utf-8", "Cache-Control" to "no-store")
            }
            val form = parseQueryString(call.bodyText(BodyLimits.PUBLIC).orEmpty())
            val person = call.redeemTicket(app, form["ticket"].orEmpty())
            if (person == null) return@to call.movePage("/?error=invalid_ticket")
            val (_, token) = app.devices.mint("Browser", person.id)
            call.movePage("/", "Set-Cookie" to OwnerCookie.set(token, app.config, call))
        },
    )
    endpoint(
        "/setup/{code}",
        HttpMethod.Get to {
            val code = call.parameters["code"].orEmpty()
            val ok = app.teams.peekCode("setup", code) != null
            call.setupPage(if (ok) 200 else 404, if (ok) SetupPage.form(null) else SetupPage.gone())
        },
        HttpMethod.Post to {
            val code = call.parameters["code"].orEmpty()
            if (call.pageOrigin(app.config) == false) return@to call.setupPage(403, SetupPage.gone())
            val form = parseQueryString(call.bodyText(BodyLimits.PUBLIC).orEmpty())
            val password = form["password"].orEmpty()
            if (password != form["again"].orEmpty()) return@to call.setupPage(400, SetupPage.form("The two passwords aren't the same."))
            if (password.length < 8) return@to call.setupPage(400, SetupPage.form("A password needs at least 8 characters."))
            val redeemed = app.teams.redeemCode("setup", code) ?: return@to call.setupPage(404, SetupPage.gone())
            val person = app.people.find(redeemed.membershipId)?.takeIf { it.active }
                ?.let { Passwords.gate.run { app.people.setOwnPassword(it, password, redeemed.email) } }
                ?: return@to call.setupPage(404, SetupPage.gone())
            call.setupPage(200, SetupPage.done(person.email.orEmpty(), app.teams.address()))
        },
    )
}

/** The person a sign-in ticket in this team is for, used up; null for any other ticket. Counted like a wrong password when it fails. */
private fun ApplicationCall.redeemTicket(app: App, ticket: String): dev.wildware.feed.server.domain.People.Person? {
    val address = runCatching { clientAddress(app.config) }.getOrElse { "unknown" }
    if (!app.loginLimiter.check(address).allowed) return null
    val redeemed = app.teams.redeemCode("ticket", ticket.trim())
    // Only while the account still has the email the ticket was made for.
    val person = redeemed?.let { app.people.find(it.membershipId) }?.takeIf { it.active && it.email != null && it.email == redeemed.email }
    if (person == null) app.loginLimiter.record(address)
    return person
}

/**
 * A page that moves itself to [to]. Not a 303: the web app's cookie is SameSite=Strict, and a browser
 * won't send it on a redirect that began on another site. A move from this page is this site's own.
 */
private suspend fun ApplicationCall.movePage(to: String, vararg headers: Pair<String, String>) = respondRaw(
    200,
    """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=$to">""" +
        """<title>Signing in</title></head><body><p><a href="$to">Continue</a></p></body></html>""",
    "text/html;charset=utf-8",
    "Cache-Control" to "no-store",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    *headers,
)

private suspend fun ApplicationCall.setupPage(status: Int, html: String) = respondRaw(
    status, html, "text/html;charset=utf-8",
    "Cache-Control" to "no-store",
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
)

/** The page a setup link opens: choose a password, once. */
object SetupPage {
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(title: String, body: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>${escape(title)}</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem;color:#1d1d1f;background:#fff}
@media (prefers-color-scheme:dark){body{color:#f5f5f7;background:#111}input{background:#222;color:#f5f5f7}}
label{display:block;margin-top:1rem;font-weight:600}input{width:100%;box-sizing:border-box;padding:.6rem;border-radius:.5rem;border:1px solid #888;font:inherit}
button{margin-top:1.2rem;padding:.7rem 1.2rem;border-radius:.6rem;border:0;background:#2f6fed;color:#fff;font:inherit;font-weight:600}
.error{color:#c62828}</style></head>
<body>$body</body></html>
"""

    fun form(error: String?) = page(
        "Choose your password",
        """<h1>Choose your password</h1>
<p>You'll sign in to Wildware Feed with your email and this password.</p>
<form method="post">
<label for="password">Password</label><input id="password" name="password" type="password" autocomplete="new-password" minlength="8" required autofocus>
<label for="again">The same again</label><input id="again" name="again" type="password" autocomplete="new-password" minlength="8" required>
${error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()}
<button type="submit">Set password</button>
</form>""",
    )

    fun gone() = page("This link doesn't work", "<h1>This link doesn't work</h1><p>It has expired or was used already. Ask for a new one.</p>")

    fun done(email: String, address: String) = page(
        "Password set",
        "<h1>Your password is set</h1><p>Open Wildware Feed, connect to <b>${escape(address)}</b>, and sign in as <b>${escape(email)}</b>.</p>",
    )
}
