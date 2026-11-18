package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.auth.Passwords

/**
 * The owner password check behind device sign-in (spec/01 §5.1). The TS server shares it with its
 * login form; the Kotlin server has no web pages (the website is deprecated), so device sign-in is
 * its only user.
 */
sealed interface PasswordCheck {
    data object Ok : PasswordCheck
    data class Refused(val status: Int, val message: String, val retryAfterS: Long? = null) : PasswordCheck
}

fun App.checkPassword(password: String?, clientAddress: String): PasswordCheck {
    if (password == "") return PasswordCheck.Refused(400, "Enter your password.")
    if (config.trustLoopback && isLoopback(clientAddress)) {
        return if (Passwords.verify(config.adminPasswordHash, password ?: "")) PasswordCheck.Ok
        else PasswordCheck.Refused(400, "That password is not right.")
    }
    val verdict = loginLimiter.check(clientAddress)
    if (!verdict.allowed) return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
    if (!Passwords.verify(config.adminPasswordHash, password ?: "")) {
        loginLimiter.record(clientAddress)
        return PasswordCheck.Refused(400, "That password is not right.")
    }
    loginLimiter.reset(clientAddress)
    return PasswordCheck.Ok
}

/** 127.0.0.0/8 or ::1, as the socket reports it. */
internal fun isLoopback(address: String): Boolean {
    val a = address.removePrefix("[").removeSuffix("]").removePrefix("::ffff:")
    return a == "::1" || a == "0:0:0:0:0:0:0:1" || Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(a)
}
