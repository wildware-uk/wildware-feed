package dev.wildware.agents

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The hosted "Log in" from start to finish, for one screen: email, code, team, signed in.
 *
 * It holds the account service's sign-in in memory only, between the code and the team, and
 * ends it as soon as a team's device key is in hand (or the person starts over, or signs in some
 * other way), so the app never keeps a second, longer-lived key. One team per sign-in: signing in
 * to another means signing out and back in.
 */
class HostedLoginFlow(
    private val account: HostedAccount,
    /** Makes the client for the team's server; the same engine as every other request is fine. */
    private val engine: () -> HttpClientEngine,
    /**
     * Where the account sign-in is ended. It outlives the screen, so leaving the screen can't
     * cancel the sign-out, and nobody waits on it.
     */
    private val background: CoroutineScope,
) {
    private var session: HostedSession? = null
    private var loginId: String? = null

    /** The account service's website: where a person without a team signs up. */
    val signUpPage: String get() = account.signUpPage

    /** The email the code went to, or the one signed in. Null before a code is asked for. */
    var email: String? = null
        private set

    /**
     * Step 1: emails a 6-digit code. Asking again sends a fresh one; the code before it still
     * works here until the new one is on its way, so a refused request leaves the person able to use it.
     */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun sendCode(email: String) {
        val started = account.requestCode(email)
        startOver()
        loginId = started
        this.email = email.trim()
    }

    /** Step 2: the code from the email. Answers the teams that email is in. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun verify(code: String, deviceName: String): HostedTeams {
        val login = loginId ?: throw HostedLoginException(HostedFailure.CodeExpired, "Ask for a new code.")
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
    suspend fun teams(): HostedTeams = guarded { account.teams(current()) }

    /**
     * Step 3: signs this device in to [team]. A fresh ticket every time, sent once, to that team's
     * own address; the device key that comes back is an ordinary one, as "Connect to a host" gets.
     * It is handed back at once, and the account sign-in ends in the background. If the team turns
     * the ticket down, the account stays signed in so the person can pick again without a new code.
     */
    @Throws(HostedLoginException::class, SignInException::class, CancellationException::class)
    suspend fun join(team: HostedTeam, deviceName: String): SignedIn {
        val signed = current()
        val ticket = guarded { account.ticket(signed, team.slug) }
        // The key is saved for the address it came from, so it must be the team that was picked.
        if (SignIn.normalise(ticket.address) != SignIn.normalise(team.address)) {
            throw HostedLoginException(HostedFailure.Refused, "The sign-in service sent this device to a different address than the team's.")
        }
        val device = SignIn.redeemTicket(ticket, deviceName, engine())
        startOver()
        return device
    }

    /** Back to the start: forgets the code and ends any account sign-in, in the background. */
    fun startOver() {
        val signed = session
        session = null
        loginId = null
        email = null
        if (signed != null) background.launch { account.signOut(signed) }
    }

    /** Whether a code has been sent and not yet used, so step 2 makes sense. */
    val awaitingCode: Boolean get() = loginId != null

    private fun current(): HostedSession =
        session ?: throw HostedLoginException(HostedFailure.SignedOut, "Your sign-in ended. Start again.")

    /** An account sign-in the service says has ended is forgotten here too. */
    private inline fun <T> guarded(call: () -> T): T = try {
        call()
    } catch (failed: HostedLoginException) {
        if (failed.failure == HostedFailure.SignedOut) {
            session = null
            email = null
        }
        throw failed
    }
}
