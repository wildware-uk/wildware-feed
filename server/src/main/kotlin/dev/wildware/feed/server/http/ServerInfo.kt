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

    /**
     * What the sign-in step can offer: the founding owner's password always, and email and password
     * once anyone has an email (migration 055). [team]: more than one person is in it.
     */
    /** The directory address of a server with many teams: it lists teams and is not itself a feed (docs/HOSTING.md §4). */
    fun directory(): JsonObject = buildJsonObject {
        put("product", PRODUCT)
        put("version", version)
        put("signIn", JsonArray(listOf(JsonPrimitive("account"))))
        put("directory", true)
    }

    fun json(emailSignIn: Boolean = false, team: Boolean = false): JsonObject = buildJsonObject {
        put("product", PRODUCT)
        put("version", version)
        put("signIn", JsonArray(listOfNotNull(JsonPrimitive("password"), JsonPrimitive("email-password").takeIf { emailSignIn })))
        put("team", team)
    }
}

fun Route.serverInfoRoute(app: App) {
    endpoint("/api/server", HttpMethod.Get to {
        if (call.attributes.contains(DirectoryKey)) return@to call.respondJson(200, ServerInfo.directory(), Wire.JSON, "Cache-Control" to "no-store")
        call.respondJson(200, ServerInfo.json(app.people.anyEmail(), app.people.activeCount() > 1), Wire.JSON, "Cache-Control" to "no-store")
    })
}
