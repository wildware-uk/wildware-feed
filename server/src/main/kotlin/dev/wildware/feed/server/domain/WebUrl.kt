package dev.wildware.feed.server.domain

import java.net.URI

/**
 * An absolute URL, read the way the TS server's `new URL(raw)` reads one, closely enough for the
 * links agents send. The JDK's parser stands in for WHATWG URL: it agrees on ordinary links (scheme
 * and host lowercased, a default port dropped, an empty path becoming `/`).
 */
internal class WebUrl private constructor(
    val scheme: String,
    val userInfo: String?,
    val host: String?,
    private val port: Int,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    private val portPart: String
        get() = if (port == -1 || (scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"

    /** `url.username || url.password`. */
    val hasCredentials: Boolean get() = !userInfo.isNullOrEmpty()

    /** `url.href`. */
    val href: String
        get() = "$scheme://" + (userInfo?.let { "$it@" } ?: "") + (host ?: "") + portPart + path +
            (query?.let { "?$it" } ?: "") + (fragment?.let { "#$it" } ?: "")

    /** `url.origin`. */
    val origin: String get() = "$scheme://${host ?: ""}$portPart"

    companion object {
        /** Null where `new URL` would throw: not a URL, or a relative one. */
        fun parse(text: String): WebUrl? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            return WebUrl(
                scheme = scheme,
                userInfo = uri.rawUserInfo,
                host = uri.host?.lowercase(),
                port = uri.port,
                path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/",
                query = uri.rawQuery,
                fragment = uri.rawFragment,
            )
        }
    }
}
