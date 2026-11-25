package dev.wildware.feed.server

import dev.wildware.feed.server.http.AdminApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One call to the control plane: [url], [headers] and [body] out; the status and body back. */
fun interface ControlPlaneTransport {
    fun post(url: String, headers: List<Pair<String, String>>, body: String): Pair<Int, String>
}

class HttpControlPlaneTransport : ControlPlaneTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun post(url: String, headers: List<Pair<String, String>>, body: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body))
        for ((k, v) in headers) request.header(k, v)
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }
}

/**
 * The one call this server makes to the control plane (docs/HOSTING.md §7, "App Store purchases"):
 * relaying an App Store purchase the iPhone app made, because only the control plane holds plans.
 * Signed like an admin call but the other way round: the message starts `relay1`, and it is signed
 * with each admin key so a key change never breaks it. On only with a control plane and admin keys.
 */
class ControlPlane(private val config: Config, private val clock: () -> Long) {
    /** Tests swap in their own. */
    @Volatile
    var transport: ControlPlaneTransport = HttpControlPlaneTransport()

    val enabled: Boolean get() = config.controlPlaneOrigin != null && config.adminApiKeys.isNotEmpty()

    /** Sends [signedTransaction] (StoreKit's JWS) for team [teamId]; the control plane's status and body. */
    fun relayAppStore(teamId: String, signedTransaction: String): Pair<Int, String> {
        val origin = checkNotNull(config.controlPlaneOrigin) { "no control plane" }
        val body = buildJsonObject {
            put("teamId", teamId)
            put("signedTransaction", signedTransaction)
        }.toString()
        val ts = (clock() / 1000).toString()
        val message = listOf("relay1", ts, "POST", PATH, AdminApi.sha256Hex(body.toByteArray(Charsets.UTF_8))).joinToString("\n")
        val signature = config.adminApiKeys.joinToString(",") { AdminApi.sign(it, message) }
        return transport.post(
            origin + PATH,
            listOf("Feed-Relay-Timestamp" to ts, "Feed-Relay-Signature" to signature, "Content-Type" to "application/json"),
            body,
        )
    }

    companion object {
        const val PATH = "/api/server/app-store/transactions"
    }
}
