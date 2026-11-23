package dev.wildware.agents.app.local

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.FakeServer
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The app sends its device key only to the server it started itself, never to whatever else holds the port. */
class LocalServerTrustTest {
    private val dir: File = Files.createTempDirectory("local-trust").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it() } }
        dir.deleteRecursively()
    }

    /** Another program on 127.0.0.1:[port]: it answers every request with `{}` and keeps each request's head. */
    private class LookAlike(port: Int) {
        val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        val heads: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val withKey get() = heads.count { head -> head.any { it.startsWith("authorization:", ignoreCase = true) } }

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        client.use {
                            val reader = it.getInputStream().bufferedReader()
                            heads += generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                            it.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(),
                            )
                        }
                    }
                }
            }
        }

        fun close() = socket.close()
    }

    private fun platform(sessions: MemorySessionStore, host: LocalServerHost, engine: () -> HttpClientEngine) = AppPlatform(
        sessions = sessions,
        readMarks = MemoryTextStore(),
        outbox = MemoryOutboxStorage(),
        httpEngine = engine,
        deviceName = "Laptop",
        imageCacheDir = null,
        localServer = host,
    )

    private fun keyedGet(host: LocalServer, url: String, key: String): Int = runBlocking {
        HttpClient(desktopHttpEngine(host)).use { http ->
            http.get(url) { header(HttpHeaders.Authorization, "Bearer $key") }.status.value
        }
    }

    @Test
    fun `a program holding the saved port never gets this app's key, and the server moves to a free one`() = runBlocking {
        val port = LocalServer.freePort(45000 + (Math.random() * 1000).toInt())
        val first = LocalServer(dir, port)
        val signed = first.signInHere("Laptop")
        first.stop()

        val lookAlike = LookAlike(port).also { closers += it::close }
        val host = LocalServer(dir, port).also { closers += { runBlocking { it.stop() } } }
        val sessions = MemorySessionStore(SavedSession(signed.server, signed.token, "Laptop", signed.deviceId, local = true))
        val model = AppModel(platform(sessions, host) { desktopHttpEngine(host) }, scope, accountService = "")

        val screen = withTimeout(15_000) { model.screen.first { it is AppScreen.Signed } } as AppScreen.Signed
        val moved = host.state.value.localUrl
        assertTrue(moved != null && moved != signed.server, "the server stayed on the taken port: $moved")
        assertEquals(moved, screen.session.saved.server)
        assertEquals(moved, sessions.load()?.server, "the saved session follows the server to its new port")
        assertEquals(200, keyedGet(host, "$moved/api/summary", signed.token), "the key still works on the app's own server")

        // Anything still pointed at the old address finds the look-alike there, which can't prove itself.
        val refused = assertFailsWith<IOException> { keyedGet(host, "${signed.server}/api/summary", signed.token) }
        assertEquals(LocalServerCheck.NOT_OURS, refused.message)
        delay(1_000)
        assertTrue(lookAlike.heads.any { it.first().startsWith("GET /api/server?proof=") }, "it was asked to prove itself")
        assertEquals(0, lookAlike.withKey, "requests carrying the key reached the other program: ${lookAlike.heads.map { it.first() }}")
    }

    @Test
    fun `while the server on this computer is stopped no key is sent to its address`() = runBlocking {
        val port = LocalServer.freePort(46000 + (Math.random() * 1000).toInt())
        val host = LocalServer(dir, port)
        val signed = host.signInHere("Laptop")
        assertEquals(200, keyedGet(host, "${signed.server}/api/summary", signed.token))
        host.stop()
        val lookAlike = LookAlike(port).also { closers += it::close }
        val refused = assertFailsWith<IOException> { keyedGet(host, "${signed.server}/api/summary", signed.token) }
        assertEquals(LocalServerCheck.NOT_RUNNING, refused.message)
        assertEquals(0, lookAlike.heads.size)
    }

    @Test
    fun `the video proxy and the sound downloader don't send the key to a look-alike either`() = runBlocking {
        val port = LocalServer.freePort(47000 + (Math.random() * 1000).toInt())
        val host = LocalServer(dir, port)
        val signed = host.signInHere("Laptop")
        host.stop()
        val lookAlike = LookAlike(port).also { closers += it::close }
        LocalServerGuard.host = host
        closers += { LocalServerGuard.host = null }
        val headers = mapOf(HttpHeaders.Authorization to "Bearer ${signed.token}")
        val media = "${signed.server}/media/01ARZ3NDEKTSV4RRFFQ69G5FAV/original"

        val proxy = dev.wildware.agents.app.feed.LoopbackMediaProxy().also { closers += it::close }
        val played = java.net.URI(proxy.urlFor(media, headers, "clip.mp4")).toURL().openConnection() as java.net.HttpURLConnection
        assertEquals(502, played.responseCode)
        assertFailsWith<IOException> { dev.wildware.agents.app.feed.SoundDownloads.fetch(media, headers) }
        assertEquals(0, lookAlike.withKey, "a request carrying the key reached the other program: ${lookAlike.heads.map { it.first() }}")
    }

    @Test
    fun `when the server on this computer can't start the feed stays shut until it can`() = runBlocking {
        val server = FakeServer().also { closers += it::close }
        val problem = "The server on this computer couldn't start: its port is in use."
        val host = FakeLocalServerHost(failure = IllegalStateException(problem))
        val sessions = MemorySessionStore(SavedSession("http://127.0.0.1:4317", "device-key", "Laptop", "d1", local = true))
        val model = AppModel(platform(sessions, host) { server.engine() }, scope, accountService = "")

        assertEquals(AppScreen.LocalServerDown(problem), withTimeout(5_000) { model.screen.first { it is AppScreen.LocalServerDown } })
        assertEquals(0, server.authorizations.size, "the key was sent anyway")
        assertTrue(sessions.load() != null, "still signed in, ready to try again")

        host.failure = null
        model.retryLocal()
        withTimeout(5_000) { model.screen.first { it is AppScreen.Signed } }
        Unit
    }
}
