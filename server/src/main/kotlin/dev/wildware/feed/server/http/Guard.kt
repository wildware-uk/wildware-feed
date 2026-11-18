package dev.wildware.feed.server.http

/**
 * Which paths need the owner (spec/01-config-and-auth.md §3.3). The TS server's `/login` and
 * `/logout` pages are not ported: the website is deprecated.
 */
object Guard {
    private val TOKEN_AUTHED = listOf("/mcp", "/api/upload", "/api/agent")
    private val PUBLIC = listOf("/api/devices/sign-in")

    fun normalise(path: String): String = if (path == "/") path else path.trimEnd('/').ifEmpty { "/" }

    private fun under(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")

    fun requiresSession(path: String): Boolean {
        val p = normalise(path)
        return (TOKEN_AUTHED + PUBLIC).none { under(p, it) }
    }

    fun isApi(path: String) = under(normalise(path), "/api")

    /** `Authorization: Bearer <one token>` (case-insensitive), else null. */
    fun bearer(header: String?): String? =
        header?.trim()?.let { Regex("^bearer\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
}
