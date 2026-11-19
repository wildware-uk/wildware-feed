package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingsTest {
    private var now = 1_000_000L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val secret = "t".repeat(32)
    private val pairings = Pairings(db, bus, secret) { now }
    private val agents = Agents(db, secret) { now }

    @Test
    fun `an agent asks, the owner allows it, and the token is handed over once`() = runBlocking<Unit> {
        val started = pairings.start("laptop-personal", "laptop", "/work", "203.0.113.5", askedBy = null)
        val p = started.pairing
        assertEquals("pending", p.state)
        assertTrue(Regex("^[2-9A-HJKMNP-TV-Z]{3}-[2-9A-HJKMNP-TV-Z]{3}$").matches(p.code), p.code)
        assertEquals(now + Pairings.TTL_MS, p.expiresAt)
        assertEquals(listOf(p.id), pairings.pending().map { it.id })
        // Only the secret's hash is kept.
        val stored = db.one("SELECT secret_hash FROM agent_pairings WHERE id = ?", p.id) { it.getString(1) }!!
        assertTrue(stored != started.secret && !stored.contains(started.secret))

        assertEquals("pending", pairings.await(p.id, started.secret, holdMs = 0).pairing.state)

        val approved = pairings.approve(p.id, null)
        assertEquals("approved", approved.state)
        val agentId = assertNotNull(approved.agentId)
        assertEquals("laptop-personal", agents.find(agentId)!!.name)
        assertEquals(emptyList(), pairings.pending())

        val first = pairings.await(p.id, started.secret, holdMs = 0)
        val token = assertNotNull(first.token)
        assertEquals("collected", first.pairing.state, "collected in the database; the HTTP answer says approved")
        assertEquals("laptop-personal", first.agentName)
        assertEquals(agentId, agents.authenticate(token).first?.id)

        val again = pairings.await(p.id, started.secret, holdMs = 0)
        assertNull(again.token)
        assertEquals("collected", again.pairing.state)
        // Nothing in the database holds the token itself.
        val everything = db.query("SELECT * FROM agent_pairings") { rs -> (1..rs.metaData.columnCount).joinToString { rs.getString(it).orEmpty() } } +
            db.query("SELECT * FROM agents") { rs -> (1..rs.metaData.columnCount).joinToString { rs.getString(it).orEmpty() } }
        assertTrue(everything.none { token in it })
    }

    @Test
    fun `a poll cut off just as the owner allows it does not lose the token`() = runBlocking<Unit> {
        val started = pairings.start("laptop", null, null, null, null)
        val id = started.pairing.id
        // The agent's poll is holding when the owner allows it; its answer never reaches the agent.
        val holding = async { pairings.await(id, started.secret, holdMs = 5_000) }
        delay(50)
        pairings.approve(id, null)
        val lost = assertNotNull(holding.await().token)

        val again = pairings.await(id, started.secret, holdMs = 0)
        assertEquals(lost, again.token, "the same token, not a new one")
        assertEquals("laptop", again.agentName)
        assertEquals(lost, pairings.await(id, started.secret, holdMs = 0).token)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(lost))

        // Its first use ends it: never shown again.
        assertNotNull(agents.authenticate(lost).first)
        val after = pairings.await(id, started.secret, holdMs = 0)
        assertNull(after.token)
        assertEquals("collected", after.pairing.state)
        assertNotNull(agents.authenticate(lost).first, "and the token keeps working")
    }

    @Test
    fun `an unused token is shown again only for ten minutes, and not once revoked`() = runBlocking<Unit> {
        val late = pairings.start("late", null, null, null, null)
        pairings.approve(late.pairing.id, null)
        val token = assertNotNull(pairings.await(late.pairing.id, late.secret, 0).token)
        now += Pairings.COLLECT_MS - 1
        assertEquals(token, pairings.await(late.pairing.id, late.secret, 0).token)
        now += 1
        assertNull(pairings.await(late.pairing.id, late.secret, 0).token)
        assertNotNull(agents.authenticate(token).first, "the token itself still works")

        val revoked = pairings.start("revoked", null, null, null, null)
        val agentId = pairings.approve(revoked.pairing.id, null).agentId!!
        assertNotNull(pairings.await(revoked.pairing.id, revoked.secret, 0).token)
        agents.revoke(agentId)
        assertNull(pairings.await(revoked.pairing.id, revoked.secret, 0).token)
    }

    @Test
    fun `a pairing's token cannot be made from what the database holds`() = runBlocking<Unit> {
        val started = pairings.start("vault", null, null, null, null)
        pairings.approve(started.pairing.id, null)
        val token = assertNotNull(pairings.await(started.pairing.id, started.secret, 0).token)
        // Every column of every table: neither the token nor the poll secret it is made from.
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }
        val everything = tables.flatMap { t -> db.query("SELECT * FROM \"$t\"") { rs -> (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() } } }
        assertTrue(everything.none { token in it || started.secret in it })
    }

    @Test
    fun `a wrong secret and an unknown id are the same not-found`() = runBlocking<Unit> {
        val started = pairings.start("scout", null, null, null, null)
        val wrong = assertFailsWith<DomainError> { pairings.await(started.pairing.id, "x".repeat(43), 0) }
        val unknown = assertFailsWith<DomainError> { pairings.await("nope", started.secret, 0) }
        assertEquals("not_found", wrong.code)
        assertEquals(wrong.message, unknown.message)
        assertFailsWith<DomainError> { pairings.await(started.pairing.id, "short", 0) }
    }

    @Test
    fun `a taken name is the owner's to change, and a deny is final`() = runBlocking<Unit> {
        agents.mint("Scout")
        val first = pairings.start("scout", null, null, null, null)
        val clash = assertFailsWith<DomainError> { pairings.approve(first.pairing.id, null) }
        assertEquals("name_taken", clash.code)
        assertEquals(409, clash.status)
        assertEquals(1, agents.list(includeRevoked = true).size, "a refused approval makes no agent")
        assertEquals("scout-2", pairings.approve(first.pairing.id, "  scout-2 ").name)
        assertEquals("conflict", assertFailsWith<DomainError> { pairings.deny(first.pairing.id) }.code)

        val second = pairings.start("other", null, null, null, null)
        assertEquals("denied", pairings.deny(second.pairing.id).state)
        val poll = pairings.await(second.pairing.id, second.secret, 0)
        assertEquals("denied", poll.pairing.state)
        assertNull(poll.token)
        assertEquals("conflict", assertFailsWith<DomainError> { pairings.approve(second.pairing.id, null) }.code)
    }

    @Test
    fun `nobody deciding expires it, and an allowed one never collected loses its agent`() = runBlocking<Unit> {
        val waiting = pairings.start("a", null, null, null, null)
        val allowed = pairings.start("b", null, null, null, null)
        now += 60_000
        val agentId = pairings.approve(allowed.pairing.id, null).agentId!!
        now += Pairings.TTL_MS - 60_000
        val expired = pairings.expireDue()
        assertEquals(listOf(waiting.pairing.id), expired.map { it.id })
        assertEquals("conflict", assertFailsWith<DomainError> { pairings.approve(waiting.pairing.id, null) }.code)
        now += 60_000
        val poll = pairings.await(allowed.pairing.id, allowed.secret, 0)
        assertEquals("expired", poll.pairing.state)
        assertNull(poll.token)
        assertNotNull(agents.find(agentId)!!.revokedAt)
        // A week on, decided pairings are gone.
        now += Pairings.KEEP_MS + 1
        pairings.expireDue()
        assertEquals(0, db.one("SELECT count(*) FROM agent_pairings") { it.getInt(1) })
    }

    @Test
    fun `waiting pairings are capped per address, per token and in all`() {
        val (asker, _) = agents.mint("asker")
        repeat(Pairings.MAX_PENDING_PER_ASKER) { pairings.start("n$it", null, null, "198.51.100.1", null) }
        assertEquals("too_many_pairings", assertFailsWith<DomainError> { pairings.start("x", null, null, "198.51.100.1", null) }.code)
        repeat(Pairings.MAX_PENDING_PER_ASKER) { pairings.start("m$it", null, null, null, asker.id) }
        val perToken = assertFailsWith<DomainError> { pairings.start("x", null, null, null, asker.id) }
        assertEquals(429, perToken.status)
        var i = 0
        while (pairings.pending().size < Pairings.MAX_PENDING) pairings.start("k$i", null, null, "192.0.2.${i++}", null)
        assertEquals("too_many_pairings", assertFailsWith<DomainError> { pairings.start("x", null, null, "192.0.2.250", null) }.code)
        assertEquals("invalid_argument", assertFailsWith<DomainError> { pairings.start(" ", null, null, null, null) }.code)
        assertEquals("invalid_argument", assertFailsWith<DomainError> { pairings.start("x".repeat(101), null, null, null, null) }.code)
    }

    @Test
    fun `a poll that is waiting hears the owner at once`() = runBlocking<Unit> {
        val started = pairings.start("waiter", null, null, null, null)
        val poll = async(kotlinx.coroutines.Dispatchers.Default) { pairings.await(started.pairing.id, started.secret, holdMs = 10_000) }
        delay(200)
        val at = System.currentTimeMillis()
        pairings.approve(started.pairing.id, null)
        val result = poll.await()
        assertNotNull(result.token)
        assertTrue(System.currentTimeMillis() - at < 5_000)
    }
}
