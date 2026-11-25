package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Telemetry
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsString
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private val JWS = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

/**
 * The iPhone app bought the Team plan with Apple's in-app purchase: an owner or admin posts the
 * signed transaction here, and this server relays it to the control plane, which checks it with
 * Apple and puts the team on Team (docs/HOSTING.md §7, "App Store purchases"). Only on a hosted team
 * (one with a plan, on a server with a control plane).
 */
fun Route.appStoreRoutes(app: App) {
    endpoint(
        "/api/plan/app-store",
        HttpMethod.Post to {
            val team = app.teams.current()
            if (team == null || team.plan == null || !app.controlPlane.enabled) {
                call.respondOwner(404, Wire.error("not_found", "This dashboard has no plan to buy."))
                return@to
            }
            val body = call.jsonObjectBody()
            val jws = body["signedTransaction"].jsString ?: throw invalid("signedTransaction is required")
            if (jws.length > 16_384 || !JWS.matches(jws)) throw invalid("signedTransaction must be the purchase's signed transaction")
            val (status, text) = try {
                withContext(Dispatchers.IO) { app.controlPlane.relayAppStore(team.id, jws) }
            } catch (e: Exception) {
                Telemetry.event(Telemetry.Level.WARN, "app_store.relay_failed", "error.type" to e.javaClass.simpleName)
                call.respondOwner(502, Wire.error("control_plane_unreachable", "The purchase went through, but the plan couldn't be updated yet. It will be, shortly."))
                return@to
            }
            val answer = runCatching { Wire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            // Only the control plane's own answers about the purchase go back. Its 401 or 404 would mean
            // this server is set up wrong, which the app must not take for its own sign-in failing.
            if (answer == null || status !in setOf(200, 400, 409)) {
                Telemetry.event(Telemetry.Level.WARN, "app_store.relay_refused", "outcome" to status.toString())
                call.respondOwner(502, Wire.error("control_plane_unreachable", "The purchase went through, but the plan couldn't be updated yet. It will be, shortly."))
                return@to
            }
            call.respondOwner(status, answer)
        },
    )
}
