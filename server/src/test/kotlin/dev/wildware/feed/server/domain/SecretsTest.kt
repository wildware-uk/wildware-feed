package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.SecretBox
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsTest {
    private var now = 1_000_000L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val tokenSecret = "t".repeat(32)
    private val keyA = "k".repeat(40)
    private val keyB = "n".repeat(40)
    private fun secrets(box: SecretBox? = SecretBox(keyA)) = Secrets(db, bus, box, tokenSecret, { "https://feed.example.com" }) { now }
    private val secrets = secrets()
    private val agents = Agents(db, tokenSecret) { now }
    private val requests = Requests(db, bus, Reads(db) { now }, { now })
    private val owner = Secrets.Actor("owner", "D1", "Phone")

    private val value = "sk-test-NOT-REAL-6b1f0c".toByteArray()
    private val events = mutableListOf<AppEvent>().also { list -> bus.subscribe { synchronized(list) { list += it } } }

    private fun tokenOf(url: String) = url.substringAfterLast('/')
    private fun actions(secretId: String? = null) = secrets.audit(secretId).map { it.action }.reversed()

    @Test
    fun `nothing is stored or handed out without SECRETS_KEY`() {
        val off = secrets(box = null)
        val (agent, _) = agents.mint("alpha")
        assertFalse(off.enabled)
        assertEquals("secrets_disabled", assertFailsWith<DomainError> { off.create("K", value, null, true, emptyList(), owner) }.code)
        assertEquals("secrets_disabled", assertFailsWith<DomainError> { off.issueRead(agent, "K") }.code)
        assertEquals("secrets_disabled", assertFailsWith<DomainError> { off.issueWrite(agent, "K", null) }.code)
        assertFailsWith<Secrets.Rejected> { off.redeemRead("x".repeat(43)) }
        assertTrue(off.list().isEmpty())
    }

    @Test
    fun `the database holds no plain value, and a read URL works once for its own agent`() {
        val (alpha, _) = agents.mint("alpha")
        val (beta, _) = agents.mint("beta")
        val s = secrets.create("OPENAI_API_KEY", value, "for the tests", allAgents = false, agentIds = listOf(alpha.id), actor = owner)
        assertEquals(value.size, s.bytes)
        assertNoPlainValue()

        assertEquals("not_found", assertFailsWith<DomainError> { secrets.issueRead(beta, "OPENAI_API_KEY") }.code)
        assertEquals(listOf("OPENAI_API_KEY"), secrets.visibleTo(alpha.id).map { it.name })
        assertTrue(secrets.visibleTo(beta.id).isEmpty())

        val grant = secrets.issueRead(alpha, "OPENAI_API_KEY")
        assertTrue(grant.url.startsWith("https://feed.example.com/api/agent/secret/"))
        assertEquals(now + Secrets.GRANT_TTL_MS, grant.expiresAt)
        // Only the token's hash is kept.
        assertNull(db.one("SELECT 1 FROM secret_grants WHERE token_hash = ?", tokenOf(grant.url)) { 1 })

        assertContentEquals(value, secrets.redeemRead(tokenOf(grant.url)))
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(grant.url)) }
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead("not-a-token") }
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead("x".repeat(43)) }

        assertEquals(listOf("created", "url_issued", "downloaded", "refused"), actions(s.id))
        val refused = secrets.audit(s.id).first()
        assertEquals("a URL that was already used was tried again", refused.detail)
        assertEquals(alpha.id, refused.actor.id)
        assertEquals(now, secrets.find(s.id)!!.lastUsedAt)
        assertNoValueInEvents()
    }

    @Test
    fun `a URL expires after five minutes`() {
        val (alpha, _) = agents.mint("alpha")
        secrets.create("K", value, null, allAgents = true, agentIds = emptyList(), actor = owner)
        val early = secrets.issueRead(alpha, "K")
        val late = secrets.issueRead(alpha, "K")
        now += Secrets.GRANT_TTL_MS - 1
        assertContentEquals(value, secrets.redeemRead(tokenOf(early.url)))
        now += 1
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(late.url)) }
        assertEquals("an expired URL was tried", secrets.audit().first().detail)
    }

    @Test
    fun `pausing, narrowing the scope, revoking the agent or deleting the secret kills URLs already out`() {
        val (alpha, _) = agents.mint("alpha")
        val (beta, _) = agents.mint("beta")
        val s = secrets.create("K", value, null, allAgents = true, agentIds = emptyList(), actor = owner)

        val beforePause = secrets.issueRead(alpha, "K")
        secrets.change(s.id, owner, paused = true)
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(beforePause.url)) }
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueRead(alpha, "K") }.code)
        secrets.change(s.id, owner, paused = false)

        val beforeNarrowing = secrets.issueRead(beta, "K")
        secrets.change(s.id, owner, allAgents = false, agentIds = listOf(alpha.id))
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(beforeNarrowing.url)) }
        assertEquals("not_found", assertFailsWith<DomainError> { secrets.issueRead(beta, "K") }.code)

        val beforeRevoke = secrets.issueRead(alpha, "K")
        agents.revoke(alpha.id)
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(beforeRevoke.url)) }
        assertEquals("the agent's token was revoked", secrets.audit(s.id).first().detail)

        val (gamma, _) = agents.mint("gamma")
        secrets.change(s.id, owner, allAgents = true)
        val beforeDelete = secrets.issueRead(gamma, "K")
        secrets.delete(s.id, owner)
        assertFailsWith<Secrets.Rejected> { secrets.redeemRead(tokenOf(beforeDelete.url)) }
        assertNull(secrets.find(s.id))
        assertEquals(0, db.one("SELECT count(*) FROM secret_grants") { it.getInt(1) })
        // The audit outlives the secret.
        assertEquals("deleted", secrets.audit(s.id).first().action)
        assertTrue(actions(s.id).containsAll(listOf("paused", "resumed", "scope_changed", "refused", "deleted")))
    }

    @Test
    fun `an agent uploads a secret only it may read, and may replace only its own`() {
        val (alpha, _) = agents.mint("alpha")
        val (beta, _) = agents.mint("beta")
        val grant = secrets.issueWrite(alpha, "DEPLOY_TOKEN", "made by the deploy script")

        // A bad body leaves the URL unspent.
        assertFailsWith<DomainError> { secrets.redeemWrite(tokenOf(grant.url), ByteArray(0)) }
        assertEquals("too_large", assertFailsWith<DomainError> { secrets.redeemWrite(tokenOf(grant.url), ByteArray(Secrets.VALUE_MAX_BYTES + 1)) }.code)
        val stored = secrets.redeemWrite(tokenOf(grant.url), value)
        assertFailsWith<Secrets.Rejected> { secrets.redeemWrite(tokenOf(grant.url), value) }

        assertEquals("agent:${alpha.id}", stored.createdBy)
        assertEquals(listOf(alpha.id), stored.agentIds)
        assertFalse(stored.allAgents)
        assertEquals("made by the deploy script", stored.note)
        assertContentEquals(value, secrets.reveal(stored.id, "reveal", owner))
        assertEquals("not_found", assertFailsWith<DomainError> { secrets.issueRead(beta, "DEPLOY_TOKEN") }.code)

        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(beta, "DEPLOY_TOKEN", null) }.code)
        val replace = secrets.issueWrite(alpha, "DEPLOY_TOKEN", null)
        secrets.redeemWrite(tokenOf(replace.url), "second".toByteArray())
        assertContentEquals("second".toByteArray(), secrets.redeemRead(tokenOf(secrets.issueRead(alpha, "DEPLOY_TOKEN").url)))

        // The owner's own secret is not an agent's to replace.
        secrets.create("OWNER_KEY", value, null, allAgents = true, agentIds = emptyList(), actor = owner)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(alpha, "OWNER_KEY", null) }.code)
        assertNoPlainValue()
    }

    @Test
    fun `the owner reveals and copies, and each one is in the audit with the device`() {
        val s = secrets.create("K", value, null, allAgents = false, agentIds = emptyList(), actor = owner)
        assertContentEquals(value, secrets.reveal(s.id, "reveal", owner))
        assertContentEquals(value, secrets.reveal(s.id, "copy", owner))
        assertFailsWith<DomainError> { secrets.reveal(s.id, "print", owner) }
        val last = secrets.audit(s.id).first()
        assertEquals(listOf("copied", "owner", "D1", "Phone"), listOf(last.action, last.actor.kind, last.actor.id, last.actor.name))
        assertNoValueInEvents()
    }

    @Test
    fun `names are checked, and values have bounds`() {
        for (bad in listOf("", "1KEY", "has space", "x".repeat(101), "a/b")) {
            assertFailsWith<DomainError>(bad) { secrets.create(bad, value, null, true, emptyList(), owner) }
        }
        secrets.create("a.b-C_9", value, null, true, emptyList(), owner)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.create("a.b-C_9", value, null, true, emptyList(), owner) }.code)
        assertFailsWith<DomainError> { secrets.create("EMPTY", ByteArray(0), null, true, emptyList(), owner) }
        assertEquals("not_found", assertFailsWith<DomainError> { secrets.create("X", value, null, false, listOf("NOPE"), owner) }.code)
    }

    @Test
    fun `a new SECRETS_KEY re-seals every row, and the old one can then go`() {
        val s = secrets.create("K", value, null, true, emptyList(), owner)
        assertEquals(0 to 0, secrets.reseal())

        val rotating = secrets(SecretBox(keyB, old = listOf(keyA)))
        assertEquals(1 to 0, rotating.reseal())
        assertEquals(0 to 0, rotating.reseal())
        assertContentEquals(value, secrets(SecretBox(keyB)).reveal(s.id, "reveal", owner))
        assertFailsWith<SecretBox.CannotOpen> { secrets(SecretBox(keyA)).reveal(s.id, "reveal", owner) }
        // A row under a key nobody has is counted, not lost.
        assertEquals(0 to 1, secrets(SecretBox("z".repeat(40))).reseal())
    }

    @Test
    fun `the owner answers request_secret in its own route, and the request carries only the name`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = requests.requestInput(alpha.id, NewRequest(kind = "secret", question = "Stripe test key?", secretName = "STRIPE_TEST_KEY"))
        assertEquals("pending", asked.state)
        val id = asked.request.id
        assertEquals("STRIPE_TEST_KEY", (asked.request.config!!["secretName"] as JsonPrimitive).content)

        // The ordinary answer route refuses it, so a value never lands on the request.
        assertFailsWith<DomainError> { requests.answer(id, JsonPrimitive("sk-oops")) }
        assertFailsWith<DomainError> { requests.edit(id, alpha.id, RequestEdit(defaultGiven = true, default = "x")) }
        assertFailsWith<DomainError> { requests.requestInput(alpha.id, NewRequest(kind = "secret", question = "?", default = "x", secretName = "A")) }

        val stored = secrets.provide(requests, id, value, null, owner)
        assertEquals(listOf(alpha.id), stored.agentIds)
        val settled = requests.status(id, alpha.id)
        assertEquals("answered", settled.state)
        assertEquals("STRIPE_TEST_KEY", (settled.response!!["value"] as JsonPrimitive).content)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provide(requests, id, value, null, owner) }.code)

        val row = db.one("SELECT decided_value, answer, config FROM approvals WHERE id = ?", id) { listOf(it.getString(1), it.getString(2), it.getString(3)) }!!
        assertTrue(row.none { it != null && it.contains(String(value)) }, row.toString())
        assertContentEquals(value, secrets.redeemRead(tokenOf(secrets.issueRead(alpha, "STRIPE_TEST_KEY").url)))

        // Another agent can't get it by asking for the same name: not at the tool, not at the answer.
        val (beta, _) = agents.mint("beta")
        val taken = assertFailsWith<DomainError> { secrets.checkRequestable(beta.id, "STRIPE_TEST_KEY") }
        assertEquals("conflict", taken.code)
        assertTrue("request_secret_access" in taken.message.orEmpty(), "the refusal names the tool that asks for access: ${taken.message}")
        val sneaky = requests.create(beta.id, NewRequest(kind = "secret", question = "Me too?", secretName = "STRIPE_TEST_KEY")).first
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provide(requests, sneaky.id, "second".toByteArray(), null, owner) }.code)
        assertEquals(listOf(alpha.id), secrets.find(stored.id)!!.agentIds)
        assertEquals("pending", requests.find(sneaky.id)!!.state)

        // The agent that may read it can ask for a new value; the readers stay as they were.
        val again = requests.create(alpha.id, NewRequest(kind = "secret", question = "It changed?", secretName = "STRIPE_TEST_KEY")).first
        val replaced = secrets.provide(requests, again.id, "second".toByteArray(), null, owner)
        assertEquals(listOf(alpha.id), replaced.agentIds)
        assertContentEquals("second".toByteArray(), secrets.reveal(stored.id, "reveal", owner))
        assertTrue(actions(stored.id).contains("provided"))
        assertNoValueInEvents()

        // Once it is shared with other agents too, answering would change it for all of them: refused
        // at the tool, and at the answer for a request made before it was shared.
        val pending = requests.create(alpha.id, NewRequest(kind = "secret", question = "Once more?", secretName = "STRIPE_TEST_KEY")).first
        secrets.change(stored.id, owner, allAgents = true)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.checkRequestable(alpha.id, "STRIPE_TEST_KEY") }.code)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provide(requests, pending.id, "third".toByteArray(), null, owner) }.code)
        assertContentEquals("second".toByteArray(), secrets.reveal(stored.id, "reveal", owner))
        assertEquals("pending", requests.find(pending.id)!!.state)
    }

    // ---- several secrets in one request (`fields`) ---------------------------------------------

    private val keyId = "AKIA-NOT-REAL-0000".toByteArray()
    private val keySecret = "not-a-real-secret-key-1111".toByteArray()

    private fun askForPair(agentId: String, vararg extra: Secrets.Field) = requests.create(
        agentId,
        NewRequest(
            kind = "secret", question = "The AWS keys?", secretName = "AWS_ACCESS_KEY_ID",
            secretFields = secrets.checkRequestableFields(
                agentId,
                listOf(Secrets.Field("AWS_ACCESS_KEY_ID", label = "Access key id"), Secrets.Field("AWS_SECRET_ACCESS_KEY", hint = "Shown once in the console", multiline = true)) + extra,
            ),
        ),
    ).first

    @Test
    fun `one request asks for several secrets and saving it stores each one, shared with the asker and audited`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = askForPair(alpha.id, Secrets.Field("AWS_SESSION_TOKEN", optional = true))
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN"), secrets.fieldsOf(asked).map { it.name })
        assertEquals(Secrets.Field("AWS_SECRET_ACCESS_KEY", hint = "Shown once in the console", multiline = true), secrets.fieldsOf(asked)[1])

        val saved = secrets.provideAll(
            requests, asked.id,
            mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret, "AWS_SESSION_TOKEN" to ByteArray(0)),
            null, owner,
        )
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"), saved.map { it.name })
        for (secret in saved) {
            assertEquals(listOf(alpha.id), secret.agentIds)
            assertEquals(listOf("created", "provided"), actions(secret.id))
        }
        assertContentEquals(keyId, secrets.redeemRead(tokenOf(secrets.issueRead(alpha, "AWS_ACCESS_KEY_ID").url)))
        assertContentEquals(keySecret, secrets.redeemRead(tokenOf(secrets.issueRead(alpha, "AWS_SECRET_ACCESS_KEY").url)))

        val settled = requests.status(asked.id, alpha.id)
        assertEquals("answered", settled.state)
        assertEquals("""["AWS_ACCESS_KEY_ID","AWS_SECRET_ACCESS_KEY"]""", settled.response!!["value"].toString())
        assertEquals("""["AWS_SESSION_TOKEN"]""", settled.response!!["skipped"].toString())
        val row = db.one("SELECT decided_value, answer, config FROM approvals WHERE id = ?", asked.id) { listOf(it.getString(1), it.getString(2), it.getString(3)) }!!
        assertTrue(row.none { cell -> cell != null && (cell.contains(String(keyId)) || cell.contains(String(keySecret))) }, "the request row holds no value")
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret), null, owner) }.code)
        assertNoValueInEvents()
    }

    @Test
    fun `a bad value or a taken name in one field saves none of them and the request waits`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val (beta, _) = agents.mint("beta")
        val asked = askForPair(alpha.id)
        fun nothingSaved() {
            assertTrue(secrets.list().none { it.name.startsWith("AWS_") }, "nothing was stored")
            assertEquals("pending", requests.find(asked.id)!!.state)
        }

        val tooBig = ByteArray(Secrets.VALUE_MAX_BYTES + 1) { 'x'.code.toByte() }
        assertEquals("too_large", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to tooBig), null, owner) }.code)
        nothingSaved()
        assertEquals("invalid_argument", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId), null, owner) }.code)
        nothingSaved()
        assertEquals("invalid_argument", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret, "EXTRA" to keyId), null, owner) }.code)
        nothingSaved()
        // The one-value route can't answer half of it.
        assertEquals("invalid_argument", assertFailsWith<DomainError> { secrets.provide(requests, asked.id, keyId, null, owner) }.code)
        nothingSaved()

        // Somebody else's secret takes the second name after the agent asked: the first isn't saved either.
        secrets.create("AWS_SECRET_ACCESS_KEY", "theirs".toByteArray(), null, allAgents = false, agentIds = listOf(beta.id), actor = owner)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret), null, owner) }.code)
        assertTrue(secrets.list().none { it.name == "AWS_ACCESS_KEY_ID" })
        assertEquals("pending", requests.find(asked.id)!!.state)
        assertEquals(listOf(beta.id), secrets.list().single().agentIds)
    }

    @Test
    fun `a write that fails inside the transaction rolls every secret back`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = askForPair(alpha.id)
        // A clock that fails on its sixth reading: inside the transaction, once the first secret is
        // written and audited and the second is being written.
        var readings = 0
        var armed = false
        val failing = Secrets(db, bus, SecretBox(keyA), tokenSecret, { "https://feed.example.com" }) {
            if (armed && ++readings == 6) error("the disk is full")
            now
        }
        armed = true
        assertFails { failing.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret), null, owner) }
        assertTrue(readings >= 6, "it failed inside the transaction")
        assertTrue(secrets.list().isEmpty(), "the first secret was rolled back too")
        assertTrue(secrets.audit().isEmpty())
        assertEquals(0L, db.one("SELECT count(*) FROM secret_agents WHERE team_id = :team") { it.getLong(1) })
        assertEquals("pending", requests.find(asked.id)!!.state)
        armed = false
        assertEquals(2, secrets.provideAll(requests, asked.id, mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret), null, owner).size)
    }

    @Test
    fun `fields are checked when the agent asks`() {
        val (alpha, _) = agents.mint("alpha")
        val (beta, _) = agents.mint("beta")
        fun refused(fields: List<Secrets.Field>) = assertFailsWith<DomainError> { secrets.checkRequestableFields(alpha.id, fields) }.code
        assertEquals("invalid_argument", refused(emptyList()))
        assertEquals("invalid_argument", refused((0..Secrets.FIELDS_MAX).map { Secrets.Field("K$it") }))
        assertEquals("invalid_argument", refused(listOf(Secrets.Field("A"), Secrets.Field("A"))))
        assertEquals("invalid_argument", refused(listOf(Secrets.Field("not a name"))))
        assertEquals("invalid_argument", refused(listOf(Secrets.Field("A", label = "x".repeat(Secrets.LABEL_MAX + 1)))))
        assertEquals("invalid_argument", refused(listOf(Secrets.Field("A", hint = "x".repeat(Secrets.HINT_MAX + 1)))))
        secrets.create("THEIRS", value, null, allAgents = false, agentIds = listOf(beta.id), actor = owner)
        assertEquals("conflict", refused(listOf(Secrets.Field("MINE"), Secrets.Field("THEIRS"))))
        assertEquals(Secrets.FIELDS_MAX, secrets.checkRequestableFields(alpha.id, (1..Secrets.FIELDS_MAX).map { Secrets.Field(" K$it ") }).size)
        assertEquals("K1", secrets.checkRequestableFields(alpha.id, listOf(Secrets.Field(" K1 "))).single().name)
    }

    @Test
    fun `another team can't answer a request for several secrets`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = askForPair(alpha.id)
        db.update("INSERT INTO teams (id, slug, name, created_at) VALUES ('other', 'other', 'Other', 1)")
        val answer = mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret)
        dev.wildware.feed.server.db.Tenant.with("other") {
            assertEquals("not_found", assertFailsWith<DomainError> { secrets.provideAll(requests, asked.id, answer, null, owner) }.code)
            assertTrue(secrets.list().isEmpty())
        }
        assertEquals("pending", requests.find(asked.id)!!.state)
        assertTrue(secrets.list().isEmpty())
        assertEquals(2, secrets.provideAll(requests, asked.id, answer, null, owner).size)
    }

    @Test
    fun `an answer that comes after the request timed out stores nothing`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = requests.create(alpha.id, NewRequest(kind = "secret", question = "Key?", timeoutS = 5.0, secretName = "LATE")).first
        now += 6_000
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.provide(requests, asked.id, value, null, owner) }.code)
        assertTrue(secrets.list().isEmpty())
        assertEquals("timeout", requests.find(asked.id)!!.state)
    }

    @Test
    fun `once the owner changes an uploaded secret, or pauses or narrows it, its agent can no longer overwrite it`() {
        val (alpha, _) = agents.mint("alpha")
        fun upload(name: String) = secrets.redeemWrite(tokenOf(secrets.issueWrite(alpha, name, null).url), value)

        val paused = upload("PAUSED")
        secrets.change(paused.id, Secrets.Actor.SERVER, paused = true)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(alpha, "PAUSED", null) }.code)

        val narrowed = upload("NARROWED")
        val link = secrets.issueWrite(alpha, "NARROWED", null)
        secrets.change(narrowed.id, Secrets.Actor.SERVER, agentIds = emptyList())
        assertFailsWith<Secrets.Rejected> { secrets.redeemWrite(tokenOf(link.url), "poison".toByteArray()) }
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(alpha, "NARROWED", null) }.code)

        val shared = upload("SHARED")
        secrets.change(shared.id, owner, allAgents = true)
        assertEquals("owner", secrets.find(shared.id)!!.createdBy)
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(alpha, "SHARED", null) }.code)
        assertContentEquals(value, secrets.reveal(shared.id, "reveal", owner))
    }

    @Test
    fun `an agent may upload only so many secrets`() {
        val (alpha, _) = agents.mint("alpha")
        repeat(Secrets.AGENT_SECRETS_MAX) { i -> secrets.redeemWrite(tokenOf(secrets.issueWrite(alpha, "K$i", null).url), value) }
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueWrite(alpha, "ONE_MORE", null) }.code)
        // Replacing one it has is still fine.
        secrets.issueWrite(alpha, "K0", null)
    }

    @Test
    fun `links issued before the cap was reached are refused once it is`() {
        val (alpha, _) = agents.mint("alpha")
        repeat(Secrets.AGENT_SECRETS_MAX - 1) { i -> secrets.redeemWrite(tokenOf(secrets.issueWrite(alpha, "K$i", null).url), value) }
        val a = secrets.issueWrite(alpha, "LAST_A", null)
        val b = secrets.issueWrite(alpha, "LAST_B", null)
        secrets.redeemWrite(tokenOf(a.url), value)
        assertFailsWith<Secrets.Rejected> { secrets.redeemWrite(tokenOf(b.url), value) }
        assertEquals(Secrets.AGENT_SECRETS_MAX, secrets.list().size)
    }

    @Test
    fun `a claimed secret request that is released waits again, and nobody was told`() = runBlocking<Unit> {
        val (alpha, _) = agents.mint("alpha")
        val asked = requests.create(alpha.id, NewRequest(kind = "secret", question = "Key?", secretName = "RACE")).first
        val before = synchronized(events) { events.count { it.type == "request.answered" } }
        val claimed = requests.claimSecret(asked.id, "RACE")
        assertEquals("conflict", assertFailsWith<DomainError> { requests.withdraw(asked.id, alpha.id) }.code)
        requests.releaseSecret(claimed)
        assertEquals("pending", requests.find(asked.id)!!.state)
        assertNull(requests.find(asked.id)!!.answer)
        assertEquals(before, synchronized(events) { events.count { it.type == "request.answered" } })
    }

    @Test
    fun `too many unused URLs at once are refused`() {
        val (alpha, _) = agents.mint("alpha")
        secrets.create("K", value, null, true, emptyList(), owner)
        repeat(Secrets.LIVE_GRANTS_MAX) { secrets.issueRead(alpha, "K") }
        assertEquals("conflict", assertFailsWith<DomainError> { secrets.issueRead(alpha, "K") }.code)
        now += Secrets.GRANT_TTL_MS
        secrets.issueRead(alpha, "K")
        // Spent and expired links are kept a day, so a late replay is still recognised, then go.
        secrets.sweep()
        assertEquals(Secrets.LIVE_GRANTS_MAX + 1, db.one("SELECT count(*) FROM secret_grants") { it.getInt(1) })
        now += 24 * 60 * 60_000L + Secrets.GRANT_TTL_MS
        secrets.sweep()
        assertEquals(0, db.one("SELECT count(*) FROM secret_grants") { it.getInt(1) })
    }

    /** No column of any table holds the value, in text or base64. */
    private fun assertNoPlainValue() {
        val plain = String(value)
        val b64 = java.util.Base64.getEncoder().encodeToString(value)
        for (table in listOf("secrets", "secret_grants", "secret_audit", "approvals")) {
            db.query("SELECT * FROM $table") { rs ->
                (1..rs.metaData.columnCount).map { rs.getString(it) }
            }.flatten().filterNotNull().forEach { cell ->
                assertFalse(cell.contains(plain) || cell.contains(b64), "$table holds the value")
            }
        }
    }

    private fun assertNoValueInEvents() {
        val plain = String(value)
        synchronized(events) {
            assertTrue(events.isNotEmpty())
            for (e in events) assertFalse(e.payload.toString().contains(plain), "${e.type} carries the value")
            assertTrue(events.filter { it.type == "secret.changed" }.all { it.payload.keys == setOf("secretId", "change") })
        }
    }
}
