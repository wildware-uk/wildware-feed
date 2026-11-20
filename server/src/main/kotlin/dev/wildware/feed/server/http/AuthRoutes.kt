package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.PasswordGate
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.SlidingLimiter
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.jsTrim

/**
 * The password check behind device sign-in (spec/01 §5.1) and the OAuth page. Without an email it
 * is today's sign-in: the founding owner's password (ADMIN_PASSWORD_HASH, or one they set). With an
 * email it is that person's (migration 055). Five wrong guesses in 15 minutes lock an address out,
 * and ten in an hour lock an email out, wherever they come from. On the desktop app's own server
 * (trustLoopback) clients on this computer share a budget of their own instead.
 */
sealed interface PasswordCheck {
    data class Ok(val person: People.Person) : PasswordCheck
    data class Refused(val status: Int, val message: String, val retryAfterS: Long? = null) : PasswordCheck
}

suspend fun App.checkPassword(password: String?, clientAddress: String, email: String? = null): PasswordCheck {
    if (password == "") return PasswordCheck.Refused(400, "Enter your password.")
    if (email != null && email.isBlank()) return PasswordCheck.Refused(400, "Enter your email.")
    // Cleaned as People.signIn cleans it, so one account is one budget however the email is spelled.
    val emailKey = email?.let { "email:" + it.jsTrim().lowercase() }
    val budgets = if (config.trustLoopback && isLoopback(clientAddress)) {
        listOf(loopbackLimiter to LOOPBACK)
    } else {
        listOfNotNull(loginLimiter to clientAddress, emailKey?.let { emailLimiter to it })
    }
    // Each guess is counted before the slow check, in the same step as the limit is checked, so
    // guesses sent at once can't all get in under the limit. A right password clears them again.
    val taken = mutableListOf<Pair<SlidingLimiter, String>>()
    for ((limiter, key) in budgets) {
        val verdict = limiter.take(key)
        if (!verdict.allowed) {
            taken.forEach { (l, k) -> l.giveBack(k) }
            return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
        }
        taken += limiter to key
    }
    val person = try {
        Passwords.gate.run { if (email == null) people.signInFounder(password ?: "") else people.signIn(email, password ?: "") }
    } catch (e: PasswordGate.Busy) {
        // No password was checked, so the guess doesn't count.
        taken.forEach { (l, k) -> l.giveBack(k) }
        return PasswordCheck.Refused(503, e.message, 1)
    }
    if (person == null) return PasswordCheck.Refused(400, if (email == null) WRONG else WRONG_EMAIL)
    budgets.forEach { (limiter, key) -> limiter.reset(key) }
    return PasswordCheck.Ok(person)
}

private const val LOOPBACK = "loopback"
private const val WRONG = "That password is not right."
private const val WRONG_EMAIL = "That email and password don't match."

/** 127.0.0.0/8 or ::1, as the socket reports it. */
internal fun isLoopback(address: String): Boolean {
    val a = address.removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
    return a == "::1" || a == "0:0:0:0:0:0:0:1" || Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(a)
}
