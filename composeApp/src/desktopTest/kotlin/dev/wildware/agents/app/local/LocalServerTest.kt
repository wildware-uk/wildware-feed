package dev.wildware.agents.app.local

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalServerTest {
    private val dir: File = Files.createTempDirectory("local-server").toFile()
    private val port = LocalServer.freePort(43000 + (Math.random() * 1000).toInt())
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun get(url: String, key: String?) = http.send(
        HttpRequest.newBuilder(URI.create(url)).apply { if (key != null) header("Authorization", "Bearer $key") }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun signIn(base: String, password: String) = http.send(
        HttpRequest.newBuilder(URI.create("$base/api/devices/sign-in")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"phone","password":"$password"}""")).build(),
        HttpResponse.BodyHandlers.ofString(),
    ).statusCode()

    @Test
    fun `this computer signs itself in, without a password, on loopback`() = runBlocking {
        val host = LocalServer(dir, port)
        val signed = host.signInHere("Laptop")
        assertEquals("http://127.0.0.1:$port", signed.server)
        assertEquals("Laptop", signed.deviceName)
        assertTrue(host.state.value.running)
        assertEquals(200, get("${signed.server}/api/summary", signed.token).statusCode())
        assertEquals(401, get("${signed.server}/api/summary", null).statusCode())
        val agent = host.connectAgent("claude")
        assertEquals(signed.server, agent.dashboardUrl)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(agent.agentToken))
        host.stop()
        assertTrue(!host.state.value.running)
    }

    @Test
    fun `first run writes settings once, and later runs keep the same port and secrets`() = runBlocking {
        val first = LocalServer(dir, port)
        val settings = first.settings()
        assertEquals(port, settings.port)
        val again = LocalServer(dir, port + 1).settings()
        assertEquals(settings, again)
    }

    @Test
    fun `this computer keeps secrets with a key made once, and adds one to settings from before secrets`() = runBlocking {
        val host = LocalServer(dir, port)
        val key = host.settings().secretsKey
        assertTrue(key != null && key.length >= 32)
        val signed = host.signInHere("Laptop")
        assertTrue(get("${signed.server}/api/secrets", signed.token).body().contains("\"enabled\":true"))
        host.stop()

        val old = File(dir, "settings.json")
        old.writeText(old.readText().replace(Regex(",\\s*\"secretsKey\":\\s*\"[^\"]+\""), ""))
        val upgraded = LocalServer(dir, port).settings()
        assertTrue(upgraded.secretsKey != null && upgraded.secretsKey != key)
        assertEquals(upgraded, LocalServer(dir, port).settings())
    }

    @Test
    fun `a second copy of the app cannot host the same data`() = runBlocking {
        val one = LocalServer(dir, port)
        one.ensureRunning()
        val two = LocalServer(dir, port)
        val error = assertFailsWith<IllegalStateException> { two.ensureRunning() }
        assertEquals("Another copy of Wildware Feed is already running the server on this computer.", error.message)
        one.stop()
    }

    @Test
    fun `with phone access on, wrong passwords from this computer never lock it out`() = runBlocking {
        val host = LocalServer(dir, port)
        host.setPhoneAccess(true, "a good password")
        host.ensureRunning()
        val base = "http://127.0.0.1:$port"
        repeat(7) { assertEquals(400, signIn(base, "wrong")) }
        assertEquals(201, signIn(base, "a good password"))
        host.setPhoneAccess(false, null)
        assertEquals(null, host.state.value.phoneUrl)
        host.stop()
    }
}
