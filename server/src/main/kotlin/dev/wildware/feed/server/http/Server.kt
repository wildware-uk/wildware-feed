package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.CrashReports
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.db.Tenant
import io.ktor.http.HttpMethod
import kotlinx.coroutines.withContext
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** Start the server for [app] on its configured host and port. */
fun serve(app: App, wait: Boolean = true): EmbeddedServer<*, *> = FeedServer(app).start(wait).engine

/**
 * The running server: one owner for starting and stopping, used by `main` and by a desktop app that
 * hosts it. [stop] stops taking requests, gives open requests [GRACE_MS], cancels what is left
 * (live streams included), then stops the jobs and closes the database, in that order.
 */
class FeedServer(val app: App) {
    lateinit var engine: EmbeddedServer<*, *>
        private set

    /** The port actually bound (useful when the config asked for 0). */
    val port: Int get() = kotlinx.coroutines.runBlocking { engine.engine.resolvedConnectors().first().port }

    fun start(wait: Boolean = false): FeedServer {
        app.startJobs()
        engine = embeddedServer(CIO, port = app.config.port, host = app.config.host) { feed(app) }
        engine.start(wait = false)
        if (wait) {
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
            Thread.currentThread().join()
        }
        return this
    }

    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { engine.stop(GRACE_MS, TIMEOUT_MS) }
        app.close()
    }

    companion object {
        const val GRACE_MS = 500L
        const val TIMEOUT_MS = 1500L
    }
}

/**
 * The whole HTTP surface, as a Ktor module the desktop app can mount too. [placeOf] says where a
 * request was sent ([Hosts]); every request to a team runs inside that team ([Tenant]), so every
 * statement it makes is that team's.
 */
fun Application.feed(app: App, placeOf: (ApplicationCall) -> Place = { Hosts.placeOf(app, it) }) {
    // A request that throws is answered with a 500; it is reported first, when reports are on.
    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            proceed()
        } catch (e: Throwable) {
            if (e !is kotlinx.coroutines.CancellationException) {
                CrashReports.requestFailed(e, call.request.httpMethod.value, call.attributes.getOrNull(RoutePattern))
            }
            throw e
        }
    }
    intercept(ApplicationCallPipeline.Monitoring) {
        val path = Guard.normalise(call.request.path())
        val admin = path == "/admin" || path.startsWith("/admin/")
        suspend fun noTeam() {
            call.respondRaw(404, """{"error":"no_such_team","message":"there is no team at this address"}""", Wire.JSON)
            finish()
        }
        when (val place = placeOf(call)) {
            Place.Nowhere -> return@intercept noTeam()
            // The directory answers the admin API and says what it is; it holds no team.
            Place.Directory -> {
                if (!admin && path != "/api/server") return@intercept noTeam()
                call.attributes.put(DirectoryKey, Unit)
                proceed()
            }
            is Place.Team -> {
                // With many teams, the admin API is only at the directory, never at a team's address.
                if (admin && app.config.multiTeam) {
                    call.respondRaw(404, """{"error":"not_found","message":"no such route"}""", Wire.JSON)
                    return@intercept finish()
                }
                val team = app.teams.find(place.id)?.takeIf { it.open } ?: return@intercept noTeam()
                call.attributes.put(TeamKey, team)
                withContext(Tenant.element(team.id)) {
                    if (team.suspended && !admin && Suspension.refuses(call)) {
                        // The control plane's own words are for the team's people; a stranger learns only that it's read-only.
                        val error = if (app.callerOf(call) != null) Suspension.error(team) else Wire.error("suspended", Suspension.GENERIC)
                        call.respondRaw(403, Wire.encode(error), Wire.JSON)
                        finish()
                    } else {
                        proceed()
                    }
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        if (guardRefused(call, app)) return@intercept finish()
    }
    routing {
        serverInfoRoute(app)
        deviceRoutes(app)
        peopleRoutes(app)
        // No event bus yet (phase 2): every snapshot is stamped seq 0.
        readRoutes(app, lastSeq = { app.bus.lastSeq })
        agentRoutes(app)
        streamRoutes(app)
        feedRoutes(app)
        requestRoutes(app)
        sessionRoutes(app)
        mediaRoutes(app)
        pairingRoutes(app)
        secretRoutes(app)
        oauthRoutes(app)
        ticketRoutes(app)
        adminRoutes(app)

        // Anything else. Under /api the guard has already turned strangers away: the owner gets a
        // JSON 404 (decided fix 12b). Elsewhere it is the web app, when this server has it, or a 404.
        route("{...}") {
            handle {
                val web = app.web
                if (Guard.isApi(call.request.path())) {
                    call.respondJson(404, Wire.error("not_found", "no such API route: ${call.request.path()}"))
                } else if (web != null && Guard.isWebApp(call.request.path())) {
                    web.serve(call)
                } else {
                    call.respondRaw(404, "Not Found", "text/plain;charset=utf-8")
                }
            }
        }
    }
}

/**
 * The owner: a device key, as `Authorization: Bearer` (the apps) or the web app's cookie
 * ([OwnerCookie]). The TS server's web session cookie is not taken: its website is retired.
 */
fun App.isOwner(call: ApplicationCall): Boolean = callerOf(call) != null

/** The device and person behind the call's key, or null when it opens nothing. */
fun App.callerOf(call: ApplicationCall): Caller? {
    val key = call.ownerKey() ?: return null
    val device = devices.authenticate(key) ?: return null
    val person = people.find(device.userId)?.takeIf { it.active } ?: return null
    return Caller(device, person)
}

/**
 * The guard: every path that is not token-authed or public needs the owner. A stranger gets
 * `401 {"error":"unauthenticated"}` everywhere — there is no login page to redirect to. The web
 * app's own files are public, as any website's code is; it holds nothing of the owner's.
 */
private suspend fun guardRefused(call: ApplicationCall, app: App): Boolean {
    val path = call.request.path()
    if (!Guard.requiresSession(path)) return false
    if (app.web != null && Guard.isWebApp(path)) return false
    val caller = app.callerOf(call)
    if (caller == null) {
        call.respondRaw(401, """{"error":"unauthenticated"}""", Wire.JSON)
        return true
    }
    call.attributes.put(CallerKey, caller)
    if (call.keyIsCookie() && !call.cookieRequestAllowed(app.config)) {
        call.respondRaw(403, """{"error":"forbidden","message":"this request did not come from the dashboard's own page"}""", Wire.JSON)
        return true
    }
    return false
}

/** The socket peer, or ADDRESS_HEADER per adapter-node's rules: the login limiter's key. */
fun ApplicationCall.clientAddress(config: dev.wildware.feed.server.Config): String {
    val header = config.addressHeader ?: return request.origin.remoteAddress
    val value = request.headers[header] ?: error("Address header was specified with ADDRESS_HEADER=$header but is absent from request")
    if (header != "x-forwarded-for") return value
    val depth = config.xffDepth
    val addresses = value.split(',')
    require(depth in 1..addresses.size) { "XFF_DEPTH must be between 1 and ${addresses.size}" }
    val peer = addresses[addresses.size - depth].trim()
    // Through Cloudflare, the peer is Cloudflare's edge and the client is in CF-Connecting-IP; that
    // header is believed only when the peer really is Cloudflare (BEHIND_CLOUDFLARE).
    if (config.behindCloudflare && dev.wildware.feed.server.CloudflareIps.contains(config.cloudflareRanges, peer)) {
        request.headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return peer
}

/**
 * One path with its methods. Several calls for the same path add to one table, so feature files can
 * each register theirs. Any other method gets SvelteKit's 405: `<METHOD> method not allowed`, with
 * `Allow` listing what there is.
 */
fun Route.endpoint(path: String, vararg handlers: Pair<HttpMethod, suspend RoutingContext.() -> Unit>) {
    for ((method, _) in handlers) registeredEndpoints += "${method.value} $path"
    val table = endpointTables.getOrPut(this) { HashMap() }
    val methods = table[path]
    if (methods != null) {
        methods.putAll(handlers)
        return
    }
    val fresh = LinkedHashMap<HttpMethod, suspend RoutingContext.() -> Unit>().apply { putAll(handlers) }
    table[path] = fresh
    route(path) {
        handle {
            call.attributes.put(RoutePattern, path)
            val m = call.request.httpMethod
            val h = fresh[m] ?: if (m == HttpMethod.Head) fresh[HttpMethod.Get] else null
            if (h == null) {
                // SvelteKit's `allowed_methods`: its fixed method order, then HEAD wherever GET is.
                val order = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
                val allowed = order.filter { o -> fresh.keys.any { it.value == o } } +
                    (if (fresh.keys.any { it == HttpMethod.Get }) listOf("HEAD") else emptyList())
                call.respondRaw(405, "${m.value} method not allowed", "text/plain;charset=utf-8", "Allow" to allowed.joinToString(", "))
                return@handle
            }
            // Behind the guard, each route needs the role Access names; a route it doesn't name is no one's.
            if (Guard.requiresSession(path)) {
                val needs = Access.rule(m.value, path)
                val caller = call.attributes.getOrNull(CallerKey)
                if (needs == null || caller == null || caller.person.role.rank < needs.rank) {
                    call.respondOwner(403, Wire.error("forbidden", if (needs == null) "nobody may call this route" else "this needs an ${needs.wire} of the team"))
                    return@handle
                }
            }
            try {
                h()
            } catch (e: DomainError) {
                call.respondDomainError(e)
            }
        }
    }
}

/** The pattern a request matched (`/api/cards/{id}`), not its path: a crash report names the route, never the ids in it. */
private val RoutePattern = io.ktor.util.AttributeKey<String>("RoutePattern")

/** Every `METHOD /pattern` any server has registered, for the test that every owner route has a rule. */
val registeredEndpoints: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

private val endpointTables = java.util.WeakHashMap<Route, HashMap<String, LinkedHashMap<HttpMethod, suspend RoutingContext.() -> Unit>>>()
