package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedServerTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    @Test
    fun `stopping with a live stream open finishes within two seconds and closes the database`() {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        val server = FeedServer(app).start()
        val base = "http://127.0.0.1:${server.port}"
        val client = HttpClient.newHttpClient()
        val summary = client.send(
            HttpRequest.newBuilder(URI.create("$base/api/summary")).header("Authorization", "Bearer $key").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, summary.statusCode())

        val stream = client.sendAsync(
            HttpRequest.newBuilder(URI.create("$base/api/stream")).header("Authorization", "Bearer $key").build(),
            HttpResponse.BodyHandlers.ofLines(),
        ).get(5, TimeUnit.SECONDS)
        assertEquals(200, stream.statusCode())
        val lines = stream.body().iterator()
        assertTrue(lines.next().startsWith("retry:"), "the stream is open")

        val started = System.nanoTime()
        server.stop()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 2_000, "stopped in $tookMs ms")
        val ended = runCatching { while (lines.hasNext()) lines.next(); true }.getOrDefault(true)
        assertTrue(ended, "the stream ended")
        assertTrue(runCatching { app.db.one("SELECT 1") { 1 } }.isFailure, "the database is closed")
        server.stop() // a second stop is harmless
    }
}
