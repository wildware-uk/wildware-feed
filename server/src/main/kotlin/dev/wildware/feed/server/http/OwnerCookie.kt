package dev.wildware.feed.server.http

import dev.wildware.feed.server.Config
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod

/**
 * The web app's session: the browser's device key, in a cookie the page's own code can't read
 * (HttpOnly), sent only by this site's own pages (SameSite=Strict). It is an ordinary device key,
 * so the browser shows in the device list and is revoked there like a phone.
 *
 * A request that changes something on the strength of the cookie must also come from this site's
 * own page ([cookieRequestAllowed]): a second guard against another site making the browser send it.
 */
object OwnerCookie {
    const val NAME = "feed_device"

    /** About 400 days, the longest a browser keeps a cookie. The key itself lasts until revoked. */
    const val MAX_AGE_S = 400L * 24 * 60 * 60

    fun set(key: String, config: Config, call: ApplicationCall): String =
        "$NAME=$key; Max-Age=$MAX_AGE_S; Path=/; HttpOnly; SameSite=Strict" + secure(config, call)

    fun clear(config: Config, call: ApplicationCall): String =
        "$NAME=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict" + secure(config, call)

    /**
     * Secure wherever the site is https, by its configured address or by how this request came in
     * (a TLS proxy says so in X-Forwarded-Proto). Only a plain-http site (on a laptop, in a test)
     * goes without, as the browser could not send it back otherwise.
     */
    private fun secure(config: Config, call: ApplicationCall): String {
        val https = config.publicBaseUrl.startsWith("https://") ||
            call.request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim().equals("https", ignoreCase = true) ||
            call.request.origin.scheme == "https"
        return if (https) "; Secure" else ""
    }
}

/**
 * The owner's key on this request: the bearer token when there is an Authorization header (and
 * nothing else then, so a bad bearer is never rescued by a good cookie), else the web app's cookie.
 */
fun ApplicationCall.ownerKey(): String? {
    val header = request.headers["Authorization"]
    if (header != null) return Guard.bearer(header)
    return request.cookies[OwnerCookie.NAME]?.takeIf { it.isNotEmpty() }
}

/** Whether [ownerKey] came from the cookie. */
fun ApplicationCall.keyIsCookie(): Boolean =
    request.headers["Authorization"] == null && !request.cookies[OwnerCookie.NAME].isNullOrEmpty()

/**
 * A cookie request is the web app's own when it reads, or when the browser says it came from this
 * site's page ([pageOrigin]). A change the browser says nothing about is refused.
 */
fun ApplicationCall.cookieRequestAllowed(config: Config): Boolean {
    val method = request.httpMethod
    if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return true
    return pageOrigin(config) ?: false
}

/**
 * The one same-origin check, for the web app's cookie and the OAuth page's forms: true when the
 * browser says this request came from this site's own page, false when it came from another site,
 * null when it says nothing (not a browser, or an old one). Browsers say so in Sec-Fetch-Site, or
 * at least in Origin, which must then be this site: its configured address, or the host the
 * request was sent to (a site reached by a second name, such as its host's own domain).
 */
fun ApplicationCall.pageOrigin(config: Config): Boolean? {
    request.headers["Sec-Fetch-Site"]?.let { return it == "same-origin" }
    val raw = request.headers["Origin"] ?: return null
    // "null" (a sandboxed frame, a file) or anything else that is not a site is not this one.
    val origin = OAuthUrls.canonical(raw) ?: return false
    if (origin == OAuthUrls.canonical(config.origin) || origin == OAuthUrls.canonical(config.publicBaseUrl)) return true
    val host = request.headers["Host"] ?: return false
    return origin == OAuthUrls.canonical("https://$host") || origin == OAuthUrls.canonical("http://$host")
}
