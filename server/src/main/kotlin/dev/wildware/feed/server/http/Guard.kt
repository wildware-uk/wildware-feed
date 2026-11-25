package dev.wildware.feed.server.http

/**
 * Which paths need the owner (spec/01-config-and-auth.md §3.3). The TS server's `/login` and
 * `/logout` pages are not ported: the website is deprecated.
 */
object Guard {
    private val TOKEN_AUTHED = listOf("/mcp", "/api/upload", "/api/agent")
    /**
     * `/api/server` says what this is and how to sign in, for the app's "Connect to a host" step.
     * `/api/pair` is where an agent with no token asks to join (the owner's side is `/api/pairings`).
     * `/.well-known` and `/oauth` are OAuth for MCP clients: the authorize page signs the owner in itself.
     * `/api/devices/sign-out` clears the web app's cookie even when its key is already revoked.
     * `/api/invite` and `/join` are for someone with an invite, who has no key yet (migration 055).
     * `/api/devices/ticket`, `/auth/ticket` and `/setup` take the admin API's one-time secrets, and
     * `/admin` checks its own signatures (migration 059, docs/HOSTING.md §7).
     */
    private val PUBLIC = listOf(
        "/api/devices/sign-in", "/api/devices/sign-out", "/api/server", "/api/pair", "/.well-known", "/oauth",
        "/api/invite", "/join", "/api/devices/ticket", "/auth/ticket", "/setup", "/admin",
        // GitHub's webhook (signed) and the install's way back (a signed state): docs/GITHUB.md.
        "/github",
    )

    fun normalise(path: String): String = if (path == "/") path else path.trimEnd('/').ifEmpty { "/" }

    private fun under(path: String, prefix: String) = path == prefix || path.startsWith("$prefix/")

    fun requiresSession(path: String): Boolean {
        val p = normalise(path)
        return (TOKEN_AUTHED + PUBLIC).none { under(p, it) }
    }

    fun isApi(path: String) = under(normalise(path), "/api")

    /**
     * The web app's paths, where the server has it: all but the server's own routes. Only a plain
     * path counts. Routing decodes `%61pi` to `api` and may fold `//`, so a path written any other
     * way is left to the guard as an owner path, or `/%61pi/summary` would pass as the app's.
     */
    fun isWebApp(path: String): Boolean {
        if (!Regex("^(/[A-Za-z0-9_.~-]+)*/?$").matches(path)) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        val p = normalise(path)
        return SERVER_ROUTES.none { under(p.lowercase(), it) }
    }

    /** What the server answers itself, under the site's root. */
    private val SERVER_ROUTES = listOf("/api", "/mcp", "/media", "/apps", "/oauth", "/.well-known", "/join", "/admin", "/auth", "/setup", "/github")

    /** `Authorization: Bearer <one token>` (case-insensitive), else null. */
    fun bearer(header: String?): String? =
        header?.trim()?.let { Regex("^bearer\\s+(\\S+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
}
