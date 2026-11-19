package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every event published once a stream's headers are in reaches that stream, even in a burst. */
class StreamDeliveryTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun <T> withServer(block: (App, String, String) -> T): T {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        val server = FeedServer(app).start()
        try {
            return block(app, "http://127.0.0.1:${server.port}", key)
        } finally {
            server.stop()
        }
    }

    /** The `seq`s of the `test.burst` events on the stream, read until [count] have come or 10 s pass. */
    private fun readBurst(lines: Iterator<String>, count: Int): List<Long> {
        val seen = mutableListOf<Long>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            reader.submit {
                while (seen.size < count && lines.hasNext()) {
                    val line = lines.next()
                    if (line.startsWith("data: ") && "\"test.burst\"" in line) {
                        synchronized(seen) { seen += Regex("\"seq\":(\\d+)").find(line)!!.groupValues[1].toLong() }
                    }
                }
            }.get(10, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // Report what did arrive.
        } finally {
            reader.shutdownNow()
        }
        return synchronized(seen) { seen.toList() }
    }

    @Test
    fun `an event published the moment the headers arrive is on the stream`() = withServer { app, base, key ->
        val client = HttpClient.newHttpClient()
        repeat(500) { round ->
            val response = client.sendAsync(
                HttpRequest.newBuilder(URI.create("$base/api/stream")).header("Authorization", "Bearer $key").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).get(5, TimeUnit.SECONDS)
            assertEquals(200, response.statusCode())
            val first = app.bus.publish("test.burst", buildJsonObject { put("round", JsonPrimitive(round)) })
            val lines = response.body()
            try {
                assertEquals(listOf(first.seq), readBurst(lines.iterator(), 1), "round $round: the first event after connect")
            } finally {
                lines.close()
            }
        }
    }

    @Test
    fun `a burst of hundreds of events arrives whole and in order`() = withServer { app, base, key ->
        val client = HttpClient.newHttpClient()
        repeat(20) { round ->
            val response = client.sendAsync(
                HttpRequest.newBuilder(URI.create("$base/api/stream")).header("Authorization", "Bearer $key").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).get(5, TimeUnit.SECONDS)
            val sent = (1..300).map { app.bus.publish("test.burst", buildJsonObject { put("i", JsonPrimitive(it)) }).seq }
            val lines = response.body()
            try {
                assertEquals(sent, readBurst(lines.iterator(), sent.size), "round $round")
            } finally {
                lines.close()
            }
        }
    }
}
