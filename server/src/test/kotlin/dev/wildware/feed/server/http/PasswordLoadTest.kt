package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.People
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Many password checks at once: each holds 64 MiB, so they take turns, and every guess counts. */
class PasswordLoadTest {
    private companion object {
        val HASH: String = Passwords.hash("owner password")
    }

    private val config = Config(
        dataDir = "unused", adminPasswordHash = HASH, sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
    )
    private val app = App(config, Database.memory())

    private val peak = AtomicInteger()
    private val hashes = AtomicInteger()

    init {
        Passwords.onDerive = { running ->
            hashes.incrementAndGet()
            peak.accumulateAndGet(running) { a, b -> maxOf(a, b) }
        }
    }

    @AfterTest
    fun done() {
        Passwords.onDerive = {}
        app.close()
    }

    private fun guesses(count: Int, address: (Int) -> String): List<PasswordCheck> = runBlocking(Dispatchers.Default) {
        List(count) { i -> async { app.checkPassword("wrong guess", address(i)) } }.awaitAll()
    }

    @Test
    fun `sign-ins at once never hash more than the gate allows`() {
        val results = guesses(8) { "203.0.113.$it" }
        assertTrue(peak.get() in 1..Passwords.gate.permits, "at most ${Passwords.gate.permits} at once, saw ${peak.get()}")
        for (r in results) {
            assertIs<PasswordCheck.Refused>(r)
            assertTrue(r.status == 400 || r.status == 503, "a wrong password or busy, not ${r.status}")
        }
    }

    @Test
    fun `guesses sent at once still stop at five`() {
        val results = guesses(12) { "198.51.100.7" }.map { (it as PasswordCheck.Refused).status }
        assertTrue(hashes.get() <= 5, "at most five passwords checked, saw ${hashes.get()}")
        assertTrue(results.count { it == 400 } <= 5)
        assertTrue(results.all { it == 400 || it == 429 || it == 503 }, "the rest are refused unchecked: $results")
        // Once five are counted, the next is refused too, right password or not.
        if (results.count { it == 400 } == 5) assertEquals(429, (runBlocking { app.checkPassword("owner password", "198.51.100.7") } as PasswordCheck.Refused).status)
    }

    @Test
    fun `the right password still gets in and clears the address`() {
        guesses(3) { "192.0.2.1" }
        assertIs<PasswordCheck.Ok>(runBlocking { app.checkPassword("owner password", "192.0.2.1") })
        assertEquals(5, guesses(5) { "192.0.2.1" }.count { (it as PasswordCheck.Refused).status != 429 })
    }

    @Test
    fun `an invite with a bad code hashes nothing`() {
        val people = app.people
        val founder = people.find(People.FOUNDER)!!
        people.invite(founder, People.Role.MEMBER, null)
        hashes.set(0)
        val e = assertFailsWith<DomainError> { people.accept(Tokens.random(), "Eve", "eve@example.com", "a long password") }
        assertEquals("invalid_invite", e.code)
        assertEquals(0, hashes.get())
    }
}
