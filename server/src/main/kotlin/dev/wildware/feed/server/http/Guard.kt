package dev.wildware.feed.server.http

/**
 * Which paths need the owner, and where a refused visitor is sent (spec/01-config-and-auth.md §3.3,
 * with decided fix 12b for unknown `/api` paths).
 */
object Guard {
    private val TOKEN_AUTHED = listOf("/mcp", "/api/upload", "/s", "/api/agent")
    private val PUBLIC = listOf("/login", "/logout", "/api/devices/sign-in")

    fun normalise(path: String): String = if (path == "/") path else path.trimEnd('/').ifEmpty { "/" }

    private fun under(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")

    fun requiresSession(path: String): Boolean {
        val p = normalise(path)
        return (TOKEN_AUTHED + PUBLIC).none { under(p, it) }
    }

    fun isApi(path: String) = under(normalise(path), "/api")

    /** Where a page sends a visitor without a session: `/login`, with the way back when there is one. */
    fun loginRedirect(pathAndQuery: String): String {
        if (safeRedirectTarget(pathAndQuery) == "/") return "/login"
        return "/login?redirectTo=" + encodeURIComponent(pathAndQuery)
    }

    /** A local path that needs a session, or `/` for anything else. */
    fun safeRedirectTarget(raw: String?): String {
        if (raw.isNullOrEmpty() || !raw.startsWith("/")) return "/"
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) return "/"
        if (raw.startsWith("//") || raw.startsWith("/\\")) return "/"
        if (raw == "/") return "/"
        val path = raw.substringBefore('?').substringBefore('#').let { normalise(it) }
        if (PUBLIC.any { under(path, it) }) return "/"
        if (!requiresSession(path)) return "/"
        return raw
    }

    /** JavaScript's encodeURIComponent: leaves A-Z a-z 0-9 - _ . ! ~ * ' ( ) alone. */
    fun encodeURIComponent(s: String): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") out.append(ch)
            else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    /** `Authorization: Bearer <one token>` (case-insensitive), else null. */
    fun bearer(header: String?): String? =
        header?.trim()?.let { Regex("^bearer\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
}
