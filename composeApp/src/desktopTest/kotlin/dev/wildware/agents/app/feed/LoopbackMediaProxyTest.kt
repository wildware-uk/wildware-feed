package dev.wildware.agents.app.feed

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop player can't send the device key, so it plays from a proxy on this computer that adds
 * it. Only for media the app asked for, only on the loopback address, never with the key in a URL,
 * and streamed with byte ranges so scrubbing works without downloading the whole video.
 */
class LoopbackMediaProxyTest {
    private val video = ByteArray(1000) { (it % 251).toByte() }
    private val key = "Bearer secret-device-key"
    private val dashboardCalls = AtomicInteger()

    /** Stands in for the dashboard: the media only for the right key, a slice for a Range. */
    private val dashboard = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/media/v1/video") { ex ->
            dashboardCalls.incrementAndGet()
            if (ex.requestHeaders.getFirst("Authorization") != key) {
                ex.sendResponseHeaders(401, -1)
                ex.close()
                return@createContext
            }
            ex.responseHeaders.add("Content-Type", "video/mp4")
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
                val (from, to) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/${video.size}")
                ex.sendResponseHeaders(206, (to - from + 1).toLong())
                ex.responseBody.use { it.write(video, from, to - from + 1) }
            } else {
                ex.sendResponseHeaders(200, video.size.toLong())
                ex.responseBody.use { it.write(video) }
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${dashboard.address.port}"
    private val proxy = LoopbackMediaProxy()

    @AfterTest
    fun stop() {
        proxy.close()
        dashboard.stop(0)
    }

    private fun get(url: String, range: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        // Header names are case-blind ("Content-type" from Java's own server).
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            c.headerFields.forEach { (k, v) -> if (k != null) put(k, v.first()) }
        }
        return Triple(code, headers, body)
    }

    @Test
    fun thePlayerGetsTheVideoAndTheDashboardGetsTheKey() {
        val local = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        val (code, headers, body) = get(local)
        assertEquals(200, code)
        assertEquals("video/mp4", headers["Content-Type"])
        assertContentEquals(video, body)
    }

    @Test
    fun theKeyIsNeverInTheAddressAndItIsOnlyOnThisComputer() {
        val local = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        assertTrue(local.startsWith("http://127.0.0.1:"), local)
        assertFalse("secret" in local, local)
        assertFalse("/media/v1" in local, "the dashboard's address isn't in it either: $local")
    }

    @Test
    fun aRangeIsPassedOnSoScrubbingStreams() {
        val local = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        val (code, headers, body) = get(local, range = "bytes=100-199")
        assertEquals(206, code)
        assertEquals("bytes 100-199/1000", headers["Content-Range"])
        assertContentEquals(video.copyOfRange(100, 200), body)
    }

    @Test
    fun anythingNotAskedForIsTurnedAwayWithoutTouchingTheDashboard() {
        val local = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        val port = URI(local).port
        assertEquals(404, get("http://127.0.0.1:$port/").first)
        assertEquals(404, get(local.replaceAfterLast('/', "").dropLast(1).replaceAfterLast('/', "not-a-media-id") + "/clip.mp4").first)
        val wrongToken = local.replace(Regex("/m/[^/]+/"), "/m/not-the-token/")
        assertEquals(404, get(wrongToken).first)
        assertEquals(0, dashboardCalls.get())
    }

    @Test
    fun theDashboardsAnswerIsPassedOnWhenItRefuses() {
        val local = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to "Bearer revoked"), "clip.mp4")
        assertEquals(401, get(local).first)
    }

    @Test
    fun theSameVideoGetsTheSameAddress() {
        val a = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        val b = proxy.urlFor("$base/media/v1/video", mapOf("Authorization" to key), "clip.mp4")
        assertEquals(a, b)
    }
}
