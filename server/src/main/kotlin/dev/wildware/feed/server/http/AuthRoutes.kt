package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.PasswordGate
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.WrongPassword
import dev.wildware.feed.server.domain.jsTrim

/**
 * The password check behind device sign-in (spec/01 §5.1) and the OAuth page. Without an email it
 * is today's sign-in: the founding owner's password (ADMIN_PASSWORD_HASH, or one they set). With an
 * email it is that person's (migration 055).
 *
 * Every guess is charged to two budgets: the client's address (five wrong in 15 minutes) and the
 * account it is aimed at ([People.signInKey]: ten wrong an hour, then a wait that doubles with each
 * further wrong one, [App.signInBackoff]). The account is charged however it is reached, with or
 * without an email and at any of its teams. A right password undoes only its own guess and clears
 * only that account's count, never the address's, so signing in to one account can't wipe guesses
 * made at another. Unknown email, wrong password and an account with no password all get the same
 * answer and cost one hash check. An IPv6 client counts as its /64 ([limiterAddress]). On the
 * desktop app's own server (trustLoopback) clients on this computer share a budget of their own instead.
 */
sealed interface PasswordCheck {
    data class Ok(val person: People.Person) : PasswordCheck
    data class Refused(val status: Int, val message: String, val retryAfterS: Long? = null) : PasswordCheck
}

suspend fun App.checkPassword(password: String?, clientAddress: String, email: String? = null): PasswordCheck {
    if (password == "") return PasswordCheck.Refused(400, "Enter your password.")
    if (email != null && email.isBlank()) return PasswordCheck.Refused(400, "Enter your email.")
    val loopback = config.trustLoopback && isLoopback(clientAddress)
    // Cleaned as People.signIn cleans it, so one account is one budget however the email is spelled.
    val accountKey = if (loopback) null else people.signInKey(email)
    val budgets = if (loopback) listOf(loopbackLimiter to LOOPBACK) else listOf(loginLimiter to clientAddress, emailLimiter to accountKey!!)
    // Each guess is counted before the slow check, in the same step as the limit is checked, so
    // guesses sent at once can't all get in under the limit.
    val taken = mutableListOf<Pair<SlidingLimiter, String>>()
    fun giveBack() {
        taken.forEach { (l, k) -> l.giveBack(k) }
        accountKey?.let(signInBackoff::giveBack)
    }
    for ((limiter, key) in budgets) {
        val verdict = limiter.take(key)
        if (!verdict.allowed) {
            taken.forEach { (l, k) -> l.giveBack(k) }
            signInAudit("locked", accountKey, clientAddress)
            return PasswordCheck.Refused(429, TOO_MANY, (verdict.retryAfterMs + 999) / 1000)
        }
        taken += limiter to key
    }
    if (accountKey != null) {
        val verdict = signInBackoff.take(accountKey)
        if (!verdict.allowed) {
            taken.forEach { (l, k) -> l.giveBack(k) }
            signInAudit("locked", accountKey, clientAddress)
            return PasswordCheck.Refused(429, TOO_MANY, (verdict.retryAfterMs + 999) / 1000)
        }
        // One check at a time per account, refused before it waits for the hashing queue.
        if (!passwordsInFlight.add(accountKey)) {
            giveBack()
            return PasswordCheck.Refused(503, ONE_AT_A_TIME, 1)
        }
    }
    val person = try {
        Passwords.gate.run { if (email == null) people.signInFounder(password ?: "") else people.signIn(email, password ?: "") }
    } catch (e: PasswordGate.Busy) {
        // No password was checked, so the guess doesn't count.
        giveBack()
        return PasswordCheck.Refused(503, e.message, 1)
    } finally {
        accountKey?.let(passwordsInFlight::remove)
    }
    if (person == null) {
        signInAudit("wrong", accountKey, clientAddress)
        return PasswordCheck.Refused(400, if (email == null) WRONG else WRONG_EMAIL)
    }
    // A right password doesn't count, and clears only this account's count. The address's other
    // wrong guesses stay: they may have been aimed at other accounts.
    giveBack()
    if (accountKey != null) {
        emailLimiter.reset(accountKey)
        signInBackoff.clear(accountKey)
    }
    return PasswordCheck.Ok(person)
}

/**
 * A password check outside sign-in, charged to the same per-account budget as sign-in
 * ([People.accountKey], [People.signInKey]): accepting an invite as an account that already exists,
 * changing your email or password, deleting your account. [block] throws [WrongPassword] when the
 * password was checked and was wrong, which counts; anything else, or no password check at all,
 * gives the guess back. A right one clears the account's count. One check at a time per account,
 * refused before the hashing queue, so nobody can keep the queue full with one account.
 */
suspend fun <T> App.accountPasswordCheck(accountKey: String, block: suspend () -> T): T {
    fun refuse(): Nothing {
        signInAudit("locked", accountKey, "-")
        throw DomainError("rate_limited", TOO_MANY, 429)
    }
    val budget = emailLimiter.take(accountKey)
    if (!budget.allowed) refuse()
    val wait = signInBackoff.take(accountKey)
    if (!wait.allowed) {
        emailLimiter.giveBack(accountKey)
        refuse()
    }
    fun giveBack() {
        emailLimiter.giveBack(accountKey)
        signInBackoff.giveBack(accountKey)
    }
    if (!passwordsInFlight.add(accountKey)) {
        giveBack()
        throw DomainError("busy", ONE_AT_A_TIME, 503)
    }
    try {
        val result = block()
        giveBack()
        emailLimiter.reset(accountKey)
        signInBackoff.clear(accountKey)
        return result
    } catch (e: WrongPassword) {
        signInAudit("wrong", accountKey, "-")
        throw e
    } catch (e: Throwable) {
        giveBack()
        throw e
    } finally {
        passwordsInFlight.remove(accountKey)
    }
}

/**
 * One log line per wrong or refused sign-in: which team, which account (a short hash, never the
 * email), from where. Never the password.
 */
private fun signInAudit(outcome: String, accountKey: String?, address: String) {
    val account = accountKey?.let { People.emailHash(it).take(12) } ?: "loopback"
    println("sign-in: $outcome team=${Tenant.currentOrNull ?: "-"} account=$account from=$address")
}

private const val TOO_MANY = "Too many attempts. Try again later."
private const val ONE_AT_A_TIME = "A password check for this account is already running. Try again in a moment."
private const val LOOPBACK = "loopback"
private const val WRONG = "That password is not right."
private const val WRONG_EMAIL = "That email and password don't match."

/** 127.0.0.0/8 or ::1, as the socket reports it. */
internal fun isLoopback(address: String): Boolean {
    val a = address.removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
    return a == "::1" || a == "0:0:0:0:0:0:0:1" || Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(a)
}
