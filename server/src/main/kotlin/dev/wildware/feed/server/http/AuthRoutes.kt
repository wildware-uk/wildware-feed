package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
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

fun App.checkPassword(password: String?, clientAddress: String, email: String? = null): PasswordCheck {
    if (password == "") return PasswordCheck.Refused(400, "Enter your password.")
    if (email != null && email.isBlank()) return PasswordCheck.Refused(400, "Enter your email.")
    val check = { pw: String -> if (email == null) people.signInFounder(pw) else people.signIn(email, pw) }
    if (config.trustLoopback && isLoopback(clientAddress)) {
        val verdict = loopbackLimiter.check(LOOPBACK)
        if (!verdict.allowed) return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
        val person = check(password ?: "")
        if (person == null) {
            loopbackLimiter.record(LOOPBACK)
            return PasswordCheck.Refused(400, if (email == null) WRONG else WRONG_EMAIL)
        }
        loopbackLimiter.reset(LOOPBACK)
        return PasswordCheck.Ok(person)
    }
    // Cleaned as People.signIn cleans it, so one account is one budget however the email is spelled.
    val emailKey = email?.let { "email:" + it.jsTrim().lowercase() }
    for (verdict in listOfNotNull(loginLimiter.check(clientAddress), emailKey?.let { emailLimiter.check(it) })) {
        if (!verdict.allowed) return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
    }
    val person = check(password ?: "")
    if (person == null) {
        loginLimiter.record(clientAddress)
        emailKey?.let { emailLimiter.record(it) }
        return PasswordCheck.Refused(400, if (email == null) WRONG else WRONG_EMAIL)
    }
    loginLimiter.reset(clientAddress)
    emailKey?.let { emailLimiter.reset(it) }
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
