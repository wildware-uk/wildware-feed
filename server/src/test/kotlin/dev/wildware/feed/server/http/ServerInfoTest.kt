package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /api/server`: public, and enough for the app to know what it reached. */
class ServerInfoTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1:0", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1:0",
    )

    @Test
    fun `a stranger learns it is a Wildware Feed server and how to sign in, and nothing else`() {
        val server = FeedServer(App(config, Database.memory())).start()
        try {
            val http = HttpClient.newHttpClient()
            fun get(path: String) = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path")).build(), HttpResponse.BodyHandlers.ofString())
            val info = get("/api/server")
            assertEquals(200, info.statusCode())
            assertTrue(info.headers().firstValue("content-type").get().startsWith("application/json"))
            val version = ServerInfo.version
            assertTrue(version.isNotEmpty())
            assertEquals("""{"product":"wildware-feed","version":"$version","signIn":["password"],"team":false}""", info.body())
            assertEquals(401, get("/api/summary").statusCode(), "everything else still wants a key")
        } finally {
            server.stop()
        }
    }
}
