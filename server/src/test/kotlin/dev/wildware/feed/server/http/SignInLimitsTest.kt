package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.AlertPrefs
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.People
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Sign-in lockouts and rate limits hold however the requests are sent. */
class SignInLimitsTest {
    private companion object {
        val HASH: String = Passwords.hash("owner password")
    }

    private val config = Config(
        dataDir = "unused", adminPasswordHash = HASH, sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private val hashes = AtomicInteger()

    init {
        Passwords.onDerive = { hashes.incrementAndGet() }
    }

    @AfterTest
    fun done() {
        Passwords.onDerive = {}
    }

    private fun status(result: PasswordCheck) = when (result) {
        is PasswordCheck.Refused -> result.status
        is PasswordCheck.Ok -> 200
    }

    /** A member with an email and a password of their own. */
    private fun member(app: App, email: String, password: String) {
        val founder = app.people.find(People.FOUNDER)!!
        val (_, token) = app.people.invite(founder, People.Role.MEMBER, null)
        app.people.accept(token, "Ann", email, password)
    }

    @Test
    fun `signing in to your own account does not clear guesses made at the founder's password`() = App(config, Database.memory()).use { app ->
        member(app, "ann@example.com", "ann's own password")
        val address = "198.51.100.4"
        fun guess() = status(runBlocking { app.checkPassword("not it", address) })
        repeat(4) { assertEquals(400, guess()) }
        assertEquals(200, status(runBlocking { app.checkPassword("ann's own password", address, "ann@example.com") }))
        assertEquals(400, guess(), "the fifth wrong guess is still checked")
        assertEquals(429, guess(), "and then the address is locked out")
        // The founder's own password, from another address, clears the founder's lockout there.
        assertEquals(200, status(runBlocking { app.checkPassword("owner password", "198.51.100.5") }))
    }

    @Test
    fun `an account with no password yet costs one hash check, as a wrong password does`() = App(config, Database.memory()).use { app ->
        member(app, "ann@example.com", "ann's own password")
        app.db.update("UPDATE users SET password_hash = NULL WHERE email = ?", "ann@example.com")
        // The stand-in hash an unknown email is checked against is made on first use.
        runBlocking { app.checkPassword("anything", "198.51.100.9", "first@example.com") }
        hashes.set(0)
        assertEquals(400, status(runBlocking { app.checkPassword("anything", "198.51.100.6", "ann@example.com") }))
        assertEquals(1, hashes.get())
        hashes.set(0)
        assertEquals(400, status(runBlocking { app.checkPassword("anything", "198.51.100.6", "nobody@example.com") }))
        assertEquals(1, hashes.get())
    }

    @Test
    fun `IPv6 addresses in one 64 share one sign-in lockout`() {
        val app = App(config.copy(addressHeader = "x-forwarded-for", xffDepth = 1), Database.memory())
        testApplication {
            application { feed(app) }
            val statuses = (1..6).map { i ->
                client.post("/api/devices/sign-in") {
                    header("X-Forwarded-For", "2001:db8:1:2::$i")
                    header("Content-Type", "application/json")
                    setBody("""{"name":"Phone","password":"not it"}""")
                }.status.value
            }
            assertEquals(listOf(400, 400, 400, 400, 400, 429), statuses)
        }
        app.close()
    }

    @Test
    fun `an unexpected error answers a fixed 500 without its own text`() {
        // ADDRESS_HEADER names a header the request lacks: the address lookup throws.
        val app = App(config.copy(addressHeader = "x-real-ip"), Database.memory())
        testApplication {
            application { feed(app) }
            val answer = client.post("/api/devices/sign-in") {
                header("Content-Type", "application/json")
                setBody("""{"name":"Phone","password":"not it"}""")
            }
            assertEquals(500, answer.status.value)
            val body = answer.bodyAsText()
            assertFalse(body.contains("x-real-ip") || body.contains("ADDRESS_HEADER"), body)
            assertTrue(body.contains("\"error\":\"internal\""), body)
        }
        app.close()
    }

    @Test
    fun `every call in an MCP batch counts against the rate limit`() {
        val app = App(config, Database.memory())
        val (_, token) = app.agents.mint("alpha")
        testApplication {
            application { feed(app) }
            suspend fun batch(n: Int) = client.post("/mcp") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/json, text/event-stream")
                header("Content-Type", "application/json")
                setBody((1..n).joinToString(",", "[", "]") { """{"jsonrpc":"2.0","id":$it,"method":"tools/list"}""" })
            }
            assertEquals(200, batch(3).status.value)
            val tooMany = batch(118)
            assertEquals(429, tooMany.status.value, "3 + 118 calls is past the 120 a minute")
            assertTrue(tooMany.headers["Retry-After"] != null)
            assertEquals(200, batch(116).status.value, "a refused batch took nothing")
        }
        app.close()
    }

    @Test
    fun `a flood of new keys does not push out a lockout still in force`() {
        val limiter = SlidingLimiter(limit = 3, windowMs = 60_000, maxKeys = 100)
        repeat(3) { limiter.record("email:victim@example.com") }
        assertFalse(limiter.check("email:victim@example.com").allowed)
        repeat(1_000) { limiter.take("email:made-up-$it@example.com") }
        assertFalse(limiter.check("email:victim@example.com").allowed, "still locked out")
    }

    @Test
    fun `taking many at once takes all or none`() {
        val limiter = SlidingLimiter(limit = 5, windowMs = 60_000)
        assertTrue(limiter.take("k", 3).allowed)
        assertFalse(limiter.take("k", 3).allowed)
        assertTrue(limiter.take("k", 2).allowed)
        assertFalse(limiter.take("k").allowed)
        limiter.giveBack("k", 2)
        assertTrue(limiter.take("k", 2).allowed)
    }

    @Test
    fun `alert settings name a bounded number of agents and channels`() {
        fun prefs(names: List<String>) = buildJsonObject { put("criticalAgents", JsonArray(names.map(::JsonPrimitive))) }
        AlertPrefs.check(prefs(List(200) { "agent-$it" }))
        assertFailsWith<DomainError> { AlertPrefs.check(prefs(List(201) { "agent-$it" })) }
        assertFailsWith<DomainError> { AlertPrefs.check(prefs(listOf("x".repeat(101)))) }
    }

    @Test
    fun `an https server must say where the client address comes from`() {
        val base = mapOf(
            "ADMIN_PASSWORD_HASH" to HASH, "SESSION_SECRET" to "s".repeat(32), "TOKEN_SECRET" to "t".repeat(32),
            "PUBLIC_BASE_URL" to "https://feed.example.com",
        )
        val refused = assertFailsWith<Config.Invalid> { Config.load(base) }
        assertTrue(refused.message!!.contains("ADDRESS_HEADER"), refused.message)
        assertEquals("x-forwarded-for", Config.load(base + ("ADDRESS_HEADER" to "X-Forwarded-For")).addressHeader)
        Config.load(base + ("TRUST_SOCKET_ADDRESS" to "true"))
        Config.load(base + ("PUBLIC_BASE_URL" to "http://127.0.0.1:8010"))
    }
}
