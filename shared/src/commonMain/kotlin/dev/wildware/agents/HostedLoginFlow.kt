package dev.wildware.agents

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException

/**
 * The hosted "Log in" from start to finish, for one screen: email, code, team, signed in.
 *
 * It holds the account service's sign-in in memory only, between the code and the team, and
 * ends it as soon as a team's device key is in hand (or the person starts over), so the app
 * never keeps a second, longer-lived key. One team per sign-in: signing in to another means
 * signing out and back in.
 */
class HostedLoginFlow(
    private val account: HostedAccount,
    /** Makes the client for the team's server; the same engine as every other request is fine. */
    private val engine: () -> HttpClientEngine,
) {
    private var session: HostedSession? = null
    private var loginId: String? = null

    /** The account service's website: where a person without a team signs up. */
    val signUpPage: String get() = account.signUpPage

    /** The email the code went to, or the one signed in. Null before a code is asked for. */
    var email: String? = null
        private set

    /** Step 1: emails a 6-digit code. Asking again sends a fresh one and forgets the old login. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun sendCode(email: String) {
        startOver()
        loginId = account.requestCode(email)
        this.email = email.trim()
    }

    /** Step 2: the code from the email. Answers the teams that email is in. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun verify(code: String, deviceName: String): HostedTeams {
        val login = loginId ?: throw HostedLoginException(HostedFailure.SignedOut, "Ask for a code first.")
        val signed = try {
            account.verify(login, code, deviceName)
        } catch (failed: HostedLoginException) {
            // A dead code can't be tried again: the person needs a new one.
            if (failed.failure == HostedFailure.Locked || failed.failure == HostedFailure.CodeExpired) loginId = null
            throw failed
        }
        loginId = null
        session = signed
        if (signed.email.isNotBlank()) email = signed.email
        return teams()
    }

    /** The teams again, as at the end of [verify]. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun teams(): HostedTeams = account.teams(current())

    /**
     * Step 3: signs this device in to [team]. A fresh ticket every time, sent once, to the team's
     * own address; the device key that comes back is an ordinary one, as "Connect to a host" gets.
     * The account sign-in ends once the team takes the ticket. If it doesn't, the person can pick
     * again without a new code.
     */
    @Throws(HostedLoginException::class, SignInException::class, CancellationException::class)
    suspend fun join(team: HostedTeam, deviceName: String): SignedIn {
        val signed = current()
        val ticket = account.ticket(signed, team.slug)
        val device = SignIn.redeemTicket(ticket, deviceName, engine())
        session = null
        account.signOut(signed)
        return device
    }

    /** Back to the start: ends any account sign-in and forgets the code. */
    suspend fun startOver() {
        val signed = session
        session = null
        loginId = null
        email = null
        if (signed != null) account.signOut(signed)
    }

    /** Whether a code has been sent and not yet used, so step 2 makes sense. */
    val awaitingCode: Boolean get() = loginId != null

    private fun current(): HostedSession =
        session ?: throw HostedLoginException(HostedFailure.SignedOut, "Your sign-in ended. Start again.")
}
