package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
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

/** The whole HTTP surface, as a Ktor module the desktop app can mount too. */
fun Application.feed(app: App) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (guardRefused(call, app)) return@intercept finish()
    }
    routing {
        deviceRoutes(app)
        // No event bus yet (phase 2): every snapshot is stamped seq 0.
        readRoutes(app, lastSeq = { app.bus.lastSeq })
        agentRoutes(app)
        streamRoutes(app)
        feedRoutes(app)
        requestRoutes(app)
        sessionRoutes(app)
        mediaRoutes(app)

        // Anything else. Under /api the guard has already turned strangers away: the owner gets a
        // JSON 404 (decided fix 12b). Elsewhere it is the site's own 404 page.
        route("{...}") {
            handle {
                if (Guard.isApi(call.request.path())) {
                    call.respondJson(404, Wire.error("not_found", "no such API route: ${call.request.path()}"))
                } else {
                    call.respondRaw(404, "Not Found", "text/plain;charset=utf-8")
                }
            }
        }
    }
}

/**
 * The owner: a device key (`Authorization: Bearer`). The TS server also takes its web session
 * cookie; the Kotlin server does not, because the website is deprecated (spec/README.md, scope).
 */
fun App.isOwner(call: ApplicationCall): Boolean {
    val bearer = Guard.bearer(call.request.headers["Authorization"]) ?: return false
    return devices.authenticate(bearer) != null
}

/**
 * The guard: every path that is not token-authed or public needs the owner. A stranger gets
 * `401 {"error":"unauthenticated"}` everywhere — there is no login page to redirect to.
 */
private suspend fun guardRefused(call: ApplicationCall, app: App): Boolean {
    val path = call.request.path()
    if (!Guard.requiresSession(path)) return false
    if (app.isOwner(call)) return false
    call.respondRaw(401, """{"error":"unauthenticated"}""", Wire.JSON)
    return true
}

/** The socket peer, or ADDRESS_HEADER per adapter-node's rules: the login limiter's key. */
fun ApplicationCall.clientAddress(config: dev.wildware.feed.server.Config): String {
    val header = config.addressHeader ?: return request.origin.remoteAddress
    val value = request.headers[header] ?: error("Address header was specified with ADDRESS_HEADER=$header but is absent from request")
    if (header != "x-forwarded-for") return value
    val depth = config.xffDepth
    val addresses = value.split(',')
    require(depth in 1..addresses.size) { "XFF_DEPTH must be between 1 and ${addresses.size}" }
    return addresses[addresses.size - depth].trim()
}

/**
 * One path with its methods. Several calls for the same path add to one table, so feature files can
 * each register theirs. Any other method gets SvelteKit's 405: `<METHOD> method not allowed`, with
 * `Allow` listing what there is.
 */
fun Route.endpoint(path: String, vararg handlers: Pair<HttpMethod, suspend RoutingContext.() -> Unit>) {
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
            try {
                h()
            } catch (e: DomainError) {
                call.respondDomainError(e)
            }
        }
    }
}

private val endpointTables = java.util.WeakHashMap<Route, HashMap<String, LinkedHashMap<HttpMethod, suspend RoutingContext.() -> Unit>>>()
