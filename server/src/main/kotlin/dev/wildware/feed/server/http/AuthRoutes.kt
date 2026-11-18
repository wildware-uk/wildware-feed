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
    val verdict = loginLimiter.check(clientAddress)
    if (!verdict.allowed) return PasswordCheck.Refused(429, "Too many attempts. Try again later.", (verdict.retryAfterMs + 999) / 1000)
    if (!Passwords.verify(config.adminPasswordHash, password ?: "")) {
        loginLimiter.record(clientAddress)
        return PasswordCheck.Refused(400, "That password is not right.")
    }
    loginLimiter.reset(clientAddress)
    return PasswordCheck.Ok
}
