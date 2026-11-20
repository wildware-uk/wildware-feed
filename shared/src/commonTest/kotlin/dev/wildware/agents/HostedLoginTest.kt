package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hosted "Log in": email, code, team, then an ordinary device key from the team's own server. */
class HostedLoginTest {
    private val fake = FakeHosted()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flow = HostedLoginFlow(HostedAccount("https://account.test/", fake::engine), fake::engine, background)

    /** Waits for the account sign-outs sent in the background. */
    private suspend fun settled() = background.coroutineContext.job.children.toList().forEach { it.join() }

    private suspend fun signedInToAccount(): HostedTeams {
        flow.sendCode("ada@example.com")
        return flow.verify("123456", "Ada's iPhone")
    }

    @Test
    fun `email then code then team gives a device key from the team server`() = runTest {
        flow.sendCode(" ada@example.com ")
        assertTrue(flow.awaitingCode)
        val teams = flow.verify("123 456", "Ada's iPhone")

        assertEquals(listOf("acme", "globex"), teams.teams.map { it.slug })
        assertEquals("Acme", teams.teams[0].name)
        assertFalse(teams.teams[0].readOnly)
        assertTrue(teams.teams[1].readOnly, "a lapsed team says so")
        assertFalse(teams.partial)

        val signed = flow.join(teams.teams[0], "Ada's iPhone")
        settled()
        assertEquals("https://acme.feed.test", signed.server)
        assertEquals("device-key-acme.feed.test", signed.token)
        assertEquals("Ada's iPhone", signed.device.name)

        val paths = fake.seen.map { "${it.method} ${it.host}${it.path}" }
        assertEquals(
            listOf(
                "POST account.test/api/app/sign-in",
                "POST account.test/api/app/sign-in/verify",
                "GET account.test/api/app/teams",
                "POST account.test/api/app/teams/acme/ticket",
                "POST acme.feed.test/api/devices/ticket",
                "POST account.test/api/app/sign-out",
            ),
            paths,
        )
        assertEquals("""{"email":"ada@example.com"}""", fake.seen[0].body)
        assertEquals("""{"loginId":"login-1","code":"123456","deviceName":"Ada's iPhone"}""", fake.seen[1].body)
        // The ticket goes to the team, once, and the account's own key never leaves the account service.
        val redeem = fake.seen[4]
        assertEquals("""{"ticket":"ticket-1","deviceName":"Ada's iPhone"}""", redeem.body)
        assertEquals(null, redeem.authorization)
        assertTrue(fake.seen.filter { it.host != "account.test" }.none { "account-token" in it.body || it.authorization != null })
        // The account sign-in ends once the device key is in hand.
        assertTrue(fake.openSessions.isEmpty())
    }

    @Test
    fun `a wrong code can be typed again`() = runTest {
        flow.sendCode("ada@example.com")
        val wrong = assertFailsWith<HostedLoginException> { flow.verify("000000", "Phone") }
        assertEquals(HostedFailure.WrongCode, wrong.failure)
        assertTrue(flow.awaitingCode)
        assertEquals(2, flow.verify("123456", "Phone").teams.size)
    }

    @Test
    fun `five wrong codes lock that code and need a new one`() = runTest {
        flow.sendCode("ada@example.com")
        repeat(4) { assertEquals(HostedFailure.WrongCode, assertFailsWith<HostedLoginException> { flow.verify("000000", "Phone") }.failure) }
        assertEquals(HostedFailure.Locked, assertFailsWith<HostedLoginException> { flow.verify("000000", "Phone") }.failure)
        assertFalse(flow.awaitingCode, "a locked code is dead")
        assertEquals(HostedFailure.CodeExpired, assertFailsWith<HostedLoginException> { flow.verify("123456", "Phone") }.failure)
        // A new code works.
        assertEquals(2, signedInToAccount().teams.size)
    }

    @Test
    fun `a used or old code says so`() = runTest {
        signedInToAccount()
        // That login is used now; trying its code again is "expired".
        val account = HostedAccount("https://account.test", fake::engine)
        assertEquals(HostedFailure.CodeExpired, assertFailsWith<HostedLoginException> { account.verify("login-1", "123456", "Phone") }.failure)
    }

    @Test
    fun `a refused request for a new code keeps the one already sent`() = runTest {
        flow.sendCode("ada@example.com")
        fake.refuseSignIn = HttpStatusCode.TooManyRequests to "rate_limited"
        assertEquals(HostedFailure.TooManyTries, assertFailsWith<HostedLoginException> { flow.sendCode("ada@example.com") }.failure)
        assertTrue(flow.awaitingCode)
        assertEquals(2, flow.verify("123456", "Phone").teams.size)
    }

    @Test
    fun `too many emails says how long to wait`() = runTest {
        fake.refuseSignIn = HttpStatusCode.TooManyRequests to "rate_limited"
        val failed = assertFailsWith<HostedLoginException> { flow.sendCode("ada@example.com") }
        assertEquals(HostedFailure.TooManyTries, failed.failure)
        assertEquals(3600L, failed.retryAfterSeconds)
    }

    @Test
    fun `an email the service will not take says so`() = runTest {
        assertEquals(HostedFailure.BadEmail, assertFailsWith<HostedLoginException> { flow.sendCode("not an email") }.failure)
    }

    @Test
    fun `no teams yet is an empty list`() = runTest {
        fake.teams = emptyList()
        fake.partial = true
        val teams = signedInToAccount()
        assertTrue(teams.teams.isEmpty())
        assertTrue(teams.partial)
    }

    @Test
    fun `a ticket works once and picking again asks for a fresh one`() = runTest {
        val teams = signedInToAccount()
        val first = flow.join(teams.teams[0], "Phone")
        assertEquals("device-key-acme.feed.test", first.token)

        // Sending the same ticket again, as a replay would, is refused by the team.
        val replay = assertFailsWith<SignInException> {
            SignIn.redeemTicket(HostedTicket("https://acme.feed.test", fake.issued.single()), "Phone", fake.engine())
        }
        assertEquals(SignInFailure.BadTicket, replay.failure)
    }

    @Test
    fun `a ticket the team turns down leaves the account signed in to pick again`() = runTest {
        val teams = signedInToAccount()
        // The team has already forgotten the ticket (it ran out on the way, say).
        fake.spoilNextTicket = true
        val refused = assertFailsWith<SignInException> { flow.join(teams.teams[0], "Phone") }
        assertEquals(SignInFailure.BadTicket, refused.failure)
        assertEquals(1, fake.openSessions.size)

        val signed = flow.join(teams.teams[0], "Phone")
        settled()
        assertEquals("https://acme.feed.test", signed.server)
        assertEquals(listOf("ticket-1", "ticket-2"), fake.issued, "a new ticket, not the old one again")
        assertTrue(fake.openSessions.isEmpty())
    }

    @Test
    fun `a ticket for another address than the team picked is never sent`() = runTest {
        val teams = signedInToAccount()
        fake.ticketAddress = "https://elsewhere.test"
        assertEquals(HostedFailure.Refused, assertFailsWith<HostedLoginException> { flow.join(teams.teams[0], "Phone") }.failure)
        assertTrue(fake.seen.none { it.host == "elsewhere.test" || it.host == "acme.feed.test" })
    }

    @Test
    fun `a bare 401 on a signed-in call is signed out`() = runTest {
        val teams = signedInToAccount()
        fake.bare401 = true
        assertEquals(HostedFailure.SignedOut, assertFailsWith<HostedLoginException> { flow.join(teams.teams[0], "Phone") }.failure)
    }

    @Test
    fun `a ticket is never sent over plain http`() = runTest {
        val teams = signedInToAccount()
        fake.ticketAddress = "http://acme.feed.test"
        assertEquals(HostedFailure.Refused, assertFailsWith<HostedLoginException> { flow.join(teams.teams[0], "Phone") }.failure)
        assertTrue(fake.seen.none { it.host == "acme.feed.test" })
    }

    @Test
    fun `an ended account sign-in starts again`() = runTest {
        val teams = signedInToAccount()
        fake.expired = true
        assertEquals(HostedFailure.SignedOut, assertFailsWith<HostedLoginException> { flow.join(teams.teams[0], "Phone") }.failure)
        assertEquals(HostedFailure.SignedOut, assertFailsWith<HostedLoginException> { flow.teams() }.failure)
    }

    @Test
    fun `a team that has gone is no such team`() = runTest {
        val teams = signedInToAccount()
        fake.teams = fake.teams.drop(1)
        assertEquals(HostedFailure.NoSuchTeam, assertFailsWith<HostedLoginException> { flow.join(teams.teams[0], "Phone") }.failure)
    }

    @Test
    fun `starting over ends the account sign-in`() = runTest {
        signedInToAccount()
        assertEquals(1, fake.openSessions.size)
        flow.startOver()
        settled()
        assertTrue(fake.openSessions.isEmpty())
        assertEquals(null, flow.email)
    }

    @Test
    fun `no answer at all is unreachable`() = runTest {
        val down = HostedAccount("https://account.test", { MockEngine { throw IllegalStateException("no route") } })
        assertEquals(HostedFailure.Unreachable, assertFailsWith<HostedLoginException> { down.requestCode("ada@example.com") }.failure)
    }

    @Test
    fun `secrets stay out of what prints`() = runTest {
        signedInToAccount()
        val session = HostedSession("account-token-9", "ada@example.com")
        val ticket = HostedTicket("https://acme.feed.test", "ticket-9")
        assertFalse("account-token-9" in session.toString())
        assertFalse("ticket-9" in ticket.toString())
    }
}
