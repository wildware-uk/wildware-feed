package dev.wildware.agents.app.crash

/**
 * What a crash report may carry, decided in one place for every platform (PRIVACY.md, "Crash
 * reports"). A report keeps what went wrong and where in the code: the error's type, the stack, the
 * app version, the operating system. It loses anything that could be the owner's: error messages
 * (they can quote a card, a project name or a server's reply), addresses, keys and tokens, who the
 * user is, and the details of any network request.
 */
object CrashScrub {
    /** The longest text kept from any one message. */
    const val MaxText = 300

    /**
     * The address a report says it came from. With none, Sentry would look up where the report's
     * connection came from and keep the town; with this one it finds nothing, and the project's
     * "don't store IP addresses" setting drops it.
     */
    const val NoAddress = "0.0.0.0"

    private val url = Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>]+""")
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val bearer = Regex("""(?i)\b(bearer|basic|token)\s+[A-Za-z0-9._~+/=\-]+""")
    private val secretPair = Regex("""(?i)\b(token|key|secret|password|passwd|pwd|auth|authorization|apikey|api_key|access_token|refresh_token|session|code|sig|signature|dsn)\s*[=:]\s*[^\s&,;]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\b""")

    /** A run of 20 or more key-like characters that has a digit in it: what keys, tokens and hashes look like. */
    private val longToken = Regex("""[A-Za-z0-9_\-+/=]{20,}""")

    /** [text] with addresses, keys, tokens and emails taken out, and cut to [MaxText]. */
    fun text(text: String): String {
        var s = text
        s = url.replace(s, "[url]")
        s = email.replace(s, "[email]")
        s = bearer.replace(s) { "${it.groupValues[1]} [redacted]" }
        s = secretPair.replace(s) { "${it.groupValues[1]}=[redacted]" }
        s = ipv6.replace(s, "[ip]")
        s = ipv4.replace(s, "[ip]")
        s = longToken.replace(s) { m -> if (m.value.any(Char::isDigit)) "[redacted]" else m.value }
        return if (s.length > MaxText) s.take(MaxText) + "…" else s
    }

    /**
     * What an error's message becomes. A Kotlin or Java error's message is dropped: it is written by
     * code that may have put anything in it (a JSON parse error quotes the whole reply). A crash the
     * system raised (an iPhone's `EXC_BAD_ACCESS`, a `SIGABRT`) is described by the system, never
     * with the owner's data, so its message stays, scrubbed all the same.
     */
    fun exceptionMessage(type: String?, message: String?): String? {
        if (message == null || type == null) return null
        val system = type.startsWith("EXC_") || type.startsWith("SIG")
        return if (system) text(message) else null
    }

    /** Breadcrumb kinds that are about the network or logs: dropped whole, as their details are addresses and text. */
    private val droppedKinds = setOf("http", "xhr", "fetch", "network", "console", "log", "query", "sql")

    /**
     * Whether a breadcrumb (a note of something that happened before the crash) is kept at all.
     * Kept ones lose their details ([data]) and have their message scrubbed by [text].
     */
    fun keepBreadcrumb(type: String?, category: String?): Boolean {
        val t = type?.lowercase()
        val c = category?.lowercase()
        if (t in droppedKinds || c in droppedKinds) return false
        // "http.client", "ui.click" (names what was tapped), "console.log" and the like.
        return c == null || !(c.startsWith("http") || c.startsWith("ui.") || c.startsWith("console") || c.startsWith("network"))
    }
}
