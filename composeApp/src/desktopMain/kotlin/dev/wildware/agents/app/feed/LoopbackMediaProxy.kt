package dev.wildware.agents.app.feed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lets the desktop video player stream media that needs the device key. The player can only be given
 * an address, with no way to add a header, and the key must never go into an address (it would end up
 * in logs and histories). So this listens on the loopback address only, on a port the system picks,
 * and serves `/m/<token>/<id>/<name>`: the token is new each run, and each id stands for one piece of
 * media the app asked for with [urlFor]. Anything else gets 404 without the dashboard being asked.
 *
 * Requests are passed on with the key and the player's Range, and the answer is streamed back as it
 * comes, so scrubbing seeks instead of downloading, and nothing is written to disk.
 */
class LoopbackMediaProxy(
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
) : AutoCloseable {
    private class Target(val url: String, val headers: Map<String, String>)

    private val random = SecureRandom()
    private val token = randomId()
    private val targets = ConcurrentHashMap<String, Target>()
    private val ids = ConcurrentHashMap<Pair<String, Map<String, String>>, String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "media-proxy").apply { isDaemon = true } }
        createContext("/") { exchange -> serve(exchange) }
        start()
    }

    /**
     * A loopback address the player can open for [remote], fetched with [headers]. [name] ends the
     * path (e.g. "clip.mp4"), as some players go by the extension. The same media gets the same address.
     */
    fun urlFor(remote: String, headers: Map<String, String>, name: String): String {
        val id = ids.computeIfAbsent(remote to headers) { randomId().also { targets[it] = Target(remote, headers) } }
        val safeName = URLEncoder.encode(name.ifBlank { "media" }, Charsets.UTF_8).replace("+", "%20")
        return "http://127.0.0.1:${server.address.port}/m/$token/$id/$safeName"
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun serve(exchange: HttpExchange) = exchange.use { ex ->
        val parts = ex.requestURI.rawPath.split('/')
        // "", "m", token, id, name
        val target = if (parts.size >= 4 && parts[1] == "m" && parts[2] == token) targets[parts[3]] else null
        val method = ex.requestMethod
        if (target == null || (method != "GET" && method != "HEAD")) {
            ex.sendResponseHeaders(if (target == null) 404 else 405, -1)
            return@use
        }
        val request = Request.Builder().url(target.url).apply {
            target.headers.forEach { (name, value) -> header(name, value) }
            ex.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (method == "HEAD") head() else get()
        }.build()
        try {
            http.newCall(request).execute().use { response ->
                for (name in PassedOn) response.header(name)?.let { ex.responseHeaders.add(name, it) }
                val body = response.body
                val length = response.header("Content-Length")?.toLongOrNull()
                if (method == "HEAD" || body == null) {
                    ex.sendResponseHeaders(response.code, -1)
                } else {
                    // A known length is sent as is; otherwise the answer goes out in chunks.
                    ex.sendResponseHeaders(response.code, if (length != null && length > 0) length else 0)
                    body.byteStream().use { input -> ex.responseBody.use { input.copyTo(it, BufferSize) } }
                }
            }
        } catch (_: IOException) {
            // The player stopped reading (a seek, a close) or the network dropped: nothing to tell.
            runCatching { ex.sendResponseHeaders(502, -1) }
        }
    }

    private fun randomId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PassedOn = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")
        const val BufferSize = 64 * 1024
    }
}
