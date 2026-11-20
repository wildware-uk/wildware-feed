package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web app served at the root, and its cookie session. */
class WebAppTest {
    private val web: File = Files.createTempDirectory("feed-web").toFile().apply {
        File(this, "index.html").writeText("<!doctype html><title>Wildware Feed</title>")
        File(this, "feed.js").writeText("console.log('app')")
        File(this, "0123456789abcdef0123.wasm").writeBytes(ByteArray(2048) { 1 })
        GZIPOutputStream(File(this, "0123456789abcdef0123.wasm.gz").outputStream()).use { it.write(ByteArray(2048) { 1 }) }
        File(this, "composeResources/font").mkdirs()
        File(this, "composeResources/font/geist.ttf").writeBytes(byteArrayOf(0, 1, 0, 0))
    }
    private val outside: File = File(web.parentFile, web.name + "-secret.txt").apply { writeText("secret") }

    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("right"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1", webDir = web.path,
    )
    private val app = App(config, Database.memory())
    private val server = FeedServer(app).start()
    private val base = "http://127.0.0.1:${server.port}"
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        server.stop()
        web.deleteRecursively()
        outside.delete()
    }

    private fun send(path: String, method: String = "GET", body: String? = null, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("$base$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)

    @Test
    fun `an invite's page offers to join in this browser, with the code kept out of the request`() {
        val token = "A".repeat(43)
        val page = send("/join/$token")
        assertEquals(200, page.statusCode())
        // In the fragment: the browser keeps it, and the app reads it from the address.
        assertTrue(page.body().contains("href=\"http://127.0.0.1/#invite=$token\""), page.body())
        assertTrue(page.body().contains("agents://invite?"))
    }

    @Test
    fun `the page and its files are public, with the right types and caching`() {
        val page = send("/")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Wildware Feed"))
        assertEquals("text/html; charset=UTF-8", page.header("content-type"))
        assertEquals("no-cache", page.header("cache-control"))
        assertEquals("DENY", page.header("x-frame-options"))

        val wasm = send("/0123456789abcdef0123.wasm")
        assertEquals(200, wasm.statusCode())
        assertEquals("application/wasm", wasm.header("content-type"))
        assertEquals("public, max-age=31536000, immutable", wasm.header("cache-control"))

        assertEquals("font/ttf", send("/composeResources/font/geist.ttf").header("content-type"))
        assertTrue(send("/feed.js").header("content-type")!!.startsWith("text/javascript"))
    }

    @Test
    fun `a browser that takes gzip gets the smaller copy`() {
        val zipped = send("/0123456789abcdef0123.wasm", headers = arrayOf("Accept-Encoding" to "gzip, br"))
        assertEquals("gzip", zipped.header("content-encoding"))
        assertEquals("application/wasm", zipped.header("content-type"))
        assertNull(send("/0123456789abcdef0123.wasm").header("content-encoding"))
    }

    @Test
    fun `an unchanged file answers 304`() {
        val tag = send("/feed.js").header("etag")!!
        assertEquals(304, send("/feed.js", headers = arrayOf("If-None-Match" to tag)).statusCode())
    }

    @Test
    fun `any other page path opens the app, but a missing file is a 404`() {
        assertTrue(send("/projects/garden-game").body().contains("Wildware Feed"))
        assertEquals(404, send("/missing.js").statusCode())
        // A way out of the bundle is never served: the guard takes it as an owner path.
        for (path in listOf("/../${outside.name}", "/%2e%2e/${outside.name}")) {
            val answer = send(path)
            assertTrue(answer.statusCode() == 401 || answer.statusCode() == 404, "$path answered ${answer.statusCode()}")
            assertFalse("secret" in answer.body())
        }
    }

    @Test
    fun `the API and media still want the owner`() {
        assertEquals(401, send("/api/summary").statusCode())
        assertEquals(404, send("/api/nope", headers = arrayOf("Authorization" to "Bearer ${app.devices.mint("Phone").second}")).statusCode())
        assertEquals(401, send("/media/x/original").statusCode())
        assertEquals(401, send("/mcp", "POST", "{}").statusCode())
        assertEquals(401, send("/apps/01ABC").statusCode())
        // OAuth's own paths are the server's, never the app's page.
        for (path in listOf("/oauth/nope", "/.well-known/nope")) assertFalse("Wildware Feed" in send(path).body(), path)
    }

    @Test
    fun `an owner path written another way still wants the owner`() {
        for (path in listOf("/%61pi/summary", "/%61%70%69/devices", "//api/summary", "/./api/summary", "/API/summary", "/%6Dedia/x/original", "/x/../api/summary")) {
            val code = send(path).statusCode()
            assertTrue(code == 401 || code == 400 || code == 404, "$path answered $code")
            assertFalse(send(path).body().contains("Wildware Feed") && code == 200, "$path served the app")
        }
        assertEquals(401, send("/%61pi/summary").statusCode())
        assertEquals(401, send("//api/summary").statusCode())
    }

    @Test
    fun `signing in with a cookie hides the key and the cookie then works, from the app's own page only`() {
        val signedIn = send("/api/devices/sign-in", "POST", """{"password":"right","name":"Chrome on Mac","cookie":true}""")
        assertEquals(201, signedIn.statusCode())
        assertTrue(signedIn.body().contains(""""token":"""""), "no key in the body: ${signedIn.body()}")
        val cookie = signedIn.header("set-cookie")!!
        assertTrue(cookie.startsWith("feed_device="))
        assertTrue("HttpOnly" in cookie && "SameSite=Strict" in cookie && "Path=/" in cookie)
        val pair = cookie.substringBefore(';')
        val key = pair.substringAfter('=')
        assertEquals(43, key.length)
        assertEquals("Chrome on Mac", app.devices.authenticate(key)?.name)

        assertEquals(200, send("/api/summary", headers = arrayOf("Cookie" to pair)).statusCode())
        assertEquals(200, send("/api/summary", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "same-origin")).statusCode())
        assertEquals(200, send("/api/summary", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "none")).statusCode(), "typed into the address bar")
        // A read from anywhere but the app's own page isn't the owner's, cookie or not: another site, a
        // sibling subdomain, or a page with no origin (an agent's sandboxed app page, opened in its own tab).
        assertEquals(403, send("/api/summary", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "cross-site")).statusCode())
        assertEquals(403, send("/api/summary", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "same-site")).statusCode())
        assertEquals(403, send("/api/summary", headers = arrayOf("Cookie" to pair, "Origin" to "null")).statusCode())
        assertEquals(403, send("/api/summary", headers = arrayOf("Cookie" to pair, "Origin" to "https://evil.example")).statusCode())
        assertEquals(403, send("/api/summary", "HEAD", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "cross-site")).statusCode())
        assertEquals(403, send("/media/01ABC/original", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "cross-site")).statusCode())
        assertEquals(403, send("/apps/01ABC", headers = arrayOf("Cookie" to pair, "Origin" to "null")).statusCode(), "an app page can't fetch another")
        assertEquals(404, send("/apps/01ABC", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Dest" to "iframe")).statusCode())
        // Another site can't sign the owner out either: no cookie cleared.
        val forced = send("/api/devices/sign-out", "POST", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "cross-site"))
        assertEquals(null, forced.header("set-cookie"))
        assertTrue(forced.body().contains(""""signedOut":false"""))
        // A change from the app's own page goes through; from anywhere else, or saying nothing, it doesn't.
        val body = """{"project":"none","body":"hi"}"""
        assertEquals(403, send("/api/messages", "POST", body, "Cookie" to pair, "Sec-Fetch-Site" to "cross-site").statusCode())
        assertEquals(403, send("/api/messages", "POST", body, "Cookie" to pair, "Origin" to "https://evil.example").statusCode())
        assertEquals(403, send("/api/messages", "POST", body, "Cookie" to pair).statusCode())
        assertTrue(send("/api/messages", "POST", body, "Cookie" to pair, "Sec-Fetch-Site" to "same-origin").statusCode() != 403)
        assertTrue(send("/api/messages", "POST", body, "Cookie" to pair, "Origin" to "http://127.0.0.1").statusCode() != 403)
        // The device's own settings take the cookie as they take a device key.
        assertEquals(200, send("/api/devices/current/alert-prefs", headers = arrayOf("Cookie" to pair)).statusCode())
        // A bad bearer is not rescued by a good cookie.
        assertEquals(401, send("/api/summary", headers = arrayOf("Cookie" to pair, "Authorization" to "Bearer nope")).statusCode())

        val out = send("/api/devices/sign-out", "POST", headers = arrayOf("Cookie" to pair, "Sec-Fetch-Site" to "same-origin"))
        assertEquals(200, out.statusCode())
        assertTrue(out.body().contains(""""signedOut":true"""))
        assertTrue(out.header("set-cookie")!!.startsWith("feed_device=; Max-Age=0"))
        assertNull(app.devices.authenticate(key), "the browser's key is revoked")
        assertEquals(401, send("/api/summary", headers = arrayOf("Cookie" to pair)).statusCode())
    }

    @Test
    fun `an ordinary sign-in still hands over the key and sets no cookie`() {
        val signedIn = send("/api/devices/sign-in", "POST", """{"password":"right","name":"Phone"}""")
        assertEquals(201, signedIn.statusCode())
        assertNull(signedIn.header("set-cookie"))
        assertFalse(signedIn.body().contains(""""token":"""""))
    }
}
