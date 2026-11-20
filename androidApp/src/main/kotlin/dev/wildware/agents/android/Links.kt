package dev.wildware.agents.android

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The links the app opens, `agents://open?path=/projects/x?focus=y` (the same form the iPhone app
 * uses), read into a dashboard page path for the feed's navigator, and `agents://invite` links. The app is for anyone's own
 * dashboard, so it claims no website's links. Plain Java parsing, so the tests run on the computer.
 */
object Links {
    const val Scheme = "agents"

    /** The page path a link points at, or null when it isn't one of ours. */
    fun pathOf(link: String): String? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (!uri.scheme.equals(Scheme, ignoreCase = true) || uri.host != "open") return null
        val path = uri.rawQuery?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0] == "path" }
            ?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
        return path?.takeIf { it.startsWith("/") }
    }

    /** `agents://invite?server=…&token=…`, the link an invite's page opens the app with. */
    fun isInvite(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        return uri.scheme.equals(Scheme, ignoreCase = true) && uri.host == "invite"
    }

    /** The `agents://` link that opens [path]. */
    fun open(path: String): String = "$Scheme://open?path=" + URLEncoder.encode(path, "UTF-8")

    /** What a share carries as words: the subject (a page title, say) above the text, unless the text has it already. */
    fun sharedText(subject: String?, text: String?): String {
        val body = text?.trim().orEmpty()
        val title = subject?.trim().orEmpty()
        return when {
            title.isEmpty() || title in body -> body
            body.isEmpty() -> title
            else -> "$title\n$body"
        }
    }
}
