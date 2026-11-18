package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.Sessions
import dev.wildware.feed.server.domain.DomainError
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** Start the server for [app] on its configured host and port. */
fun serve(app: App, wait: Boolean = true): EmbeddedServer<*, *> =
    embeddedServer(CIO, port = app.config.port, host = app.config.host) { feed(app) }.start(wait = wait)

/** The whole HTTP surface, as a Ktor module the desktop app can mount too. */
fun Application.feed(app: App) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (csrfRefused(call, app)) return@intercept finish()
        if (guardRefused(call, app)) return@intercept finish()
    }
    routing {
        authRoutes(app)
        pageRoutes(app)
        deviceRoutes(app)
        // No event bus yet (phase 2): every snapshot is stamped seq 0.
        readRoutes(app, lastSeq = { 0L })
        agentRoutes(app)

        // Anything else. Under /api the guard has already turned strangers away: the owner gets a
        // JSON 404 (decided fix 12b). Elsewhere it is the site's own 404 page.
        route("{...}") {
            handle {
                if (Guard.isApi(call.request.path())) {
                    call.respondJson(404, Wire.error("not_found", "no such API route: ${call.request.path()}"))
                } else {
                    call.respondRaw(404, Pages.notFound(), "text/html")
                }
            }
        }
    }
}

/**
 * SvelteKit's CSRF rule: a form-shaped write from another origin is refused before anything runs
 * (spec/01-config-and-auth.md §3.1).
 */
private suspend fun csrfRefused(call: ApplicationCall, app: App): Boolean {
    val method = call.request.httpMethod.value
    if (method !in setOf("POST", "PUT", "PATCH", "DELETE")) return false
    val type = call.request.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() ?: return false
    if (type !in setOf("application/x-www-form-urlencoded", "multipart/form-data", "text/plain", "application/x-sveltekit-formdata")) return false
    if (call.request.headers["Origin"] == app.config.origin) return false
    val message = "Cross-site $method form submissions are forbidden"
    if (call.request.headers["Accept"] == "application/json") {
        call.respondRaw(403, """{"message":"$message"}""", Wire.JSON)
    } else {
        call.respondRaw(403, message, null)
    }
    return true
}

/** The owner by device key (a bearer decides on its own) or by cookie. */
fun App.isOwner(call: ApplicationCall): Boolean {
    val bearer = Guard.bearer(call.request.headers["Authorization"])
    if (bearer != null) return devices.authenticate(bearer) != null
    return hasSessionCookie(call)
}

fun App.hasSessionCookie(call: ApplicationCall): Boolean =
    Sessions.valid(call.request.cookies[Sessions.COOKIE, io.ktor.http.CookieEncoding.RAW], config.sessionSecret, clock())

/**
 * The guard (spec/01 §3.3): under `/api` a stranger gets 401 JSON; pages need the cookie (they never
 * look at a bearer); `/media` and `/apps` take either.
 */
private suspend fun guardRefused(call: ApplicationCall, app: App): Boolean {
    val path = call.request.path()
    if (!Guard.requiresSession(path)) return false
    if (Guard.isApi(path)) {
        if (app.isOwner(call)) return false
        call.respondRaw(401, """{"error":"unauthenticated"}""", Wire.JSON)
        return true
    }
    val normal = Guard.normalise(path)
    val handlerAuth = normal.startsWith("/media/") || normal.startsWith("/apps/")
    val ok = if (handlerAuth) app.isOwner(call) else {
        val bearer = Guard.bearer(call.request.headers["Authorization"])
        (bearer == null || app.devices.authenticate(bearer) != null) && app.hasSessionCookie(call)
    }
    if (ok) return false
    call.respondRedirect303(Guard.loginRedirect(call.request.uri))
    return true
}

/** The socket peer, or ADDRESS_HEADER per adapter-node's rules: the login limiter's key. */
fun ApplicationCall.clientAddress(): String {
    val header = System.getenv("ADDRESS_HEADER")?.takeIf { it.isNotBlank() }?.lowercase()
        ?: return request.origin.remoteAddress
    val value = request.headers[header] ?: error("Address header was specified with ADDRESS_HEADER=$header but is absent from request")
    if (header != "x-forwarded-for") return value
    val depth = System.getenv("XFF_DEPTH")?.toIntOrNull() ?: 1
    val addresses = value.split(',')
    require(depth in 1..addresses.size) { "XFF_DEPTH must be between 1 and ${addresses.size}" }
    return addresses[addresses.size - depth].trim()
}

/**
 * One path with its methods. Any other method gets SvelteKit's 405: `<METHOD> method not allowed`,
 * with `Allow` listing what there is.
 */
fun Route.endpoint(path: String, vararg handlers: Pair<HttpMethod, suspend RoutingContext.() -> Unit>) {
    route(path) {
        for ((m, h) in handlers) {
            method(m) {
                handle {
                    try {
                        h()
                    } catch (e: DomainError) {
                        call.respondDomainError(e)
                    }
                }
            }
        }
        val allowed = handlers.map { it.first.value }
        handle {
            val m = call.request.httpMethod.value
            if (m == "HEAD" && "GET" in allowed) return@handle
            call.respondRaw(405, "$m method not allowed", "text/plain;charset=utf-8", "Allow" to allowed.joinToString(", "))
        }
    }
}
