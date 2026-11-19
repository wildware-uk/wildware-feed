package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `GET /api/server`, public: tells an app at the "Connect to a host" step that this address is a
 * Wildware Feed server, and how to sign in to it (docs/TEAMS.md, "The sign-in screen"). It says
 * nothing a stranger can use: no names, no counts.
 */
object ServerInfo {
    const val PRODUCT = "wildware-feed"

    /** The release this server was built as, or "dev" for a build without one. */
    val version: String by lazy {
        ServerInfo::class.java.getResource("/feed-server-version.txt")?.readText()?.trim()?.ifEmpty { null } ?: "dev"
    }

    fun json(): JsonObject = buildJsonObject {
        put("product", PRODUCT)
        put("version", version)
        // What the sign-in step can offer: today only the owner's password. Teams adds
        // email-password, email-code and grant.
        put("signIn", JsonArray(listOf(JsonPrimitive("password"))))
        put("team", false)
    }
}

fun Route.serverInfoRoute(@Suppress("UNUSED_PARAMETER") app: App) {
    endpoint("/api/server", HttpMethod.Get to { call.respondJson(200, ServerInfo.json(), Wire.JSON, "Cache-Control" to "no-store") })
}
