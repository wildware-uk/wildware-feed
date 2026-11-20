package dev.wildware.agents.app.local

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Keeps this app's keys for the server it runs itself. Before a request that carries a key goes to
 * that server's address, the server must answer a fresh nonce with the secret it was started with
 * ([LocalServer.proofFor]). Anything else listening there can't, so it is never sent the key.
 * Requests to any other server pass straight through.
 */
class LocalServerCheck(private val host: LocalServer) : Interceptor {
    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") == null || !host.guards(request.url.host, request.url.port)) return chain.proceed(request)
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val expected = host.proofFor(nonce) ?: throw IOException(NOT_RUNNING)
        val probe = Request.Builder()
            .url(request.url.newBuilder().encodedPath("/api/server").query(null).addQueryParameter("proof", nonce).build())
            .build()
        val body = chain.proceed(probe).use { it.body?.string().orEmpty() }
        val answer = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("proof")?.jsonPrimitive?.content }.getOrNull()
        if (answer == null || !MessageDigest.isEqual(answer.toByteArray(), expected.toByteArray())) throw IOException(NOT_OURS)
        return chain.proceed(request)
    }

    companion object {
        const val NOT_RUNNING = "The server on this computer isn't running."
        const val NOT_OURS = "Something else is answering at this computer's server address, so the app didn't send it your key."
    }
}

/** The desktop app's network engine: OkHttp, with [LocalServerCheck] in front of its own server when it has one. */
fun desktopHttpEngine(host: LocalServer?): HttpClientEngine = OkHttp.create {
    if (host != null) addInterceptor(LocalServerCheck(host))
}
