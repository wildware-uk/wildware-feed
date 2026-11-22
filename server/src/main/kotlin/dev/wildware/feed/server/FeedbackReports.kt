package dev.wildware.feed.server

import dev.wildware.feed.server.http.ServerInfo
import io.sentry.Attachment
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.protocol.Feedback

/**
 * Where sent feedback goes (server/spec/14-feedback.md): Sentry's user feedback, on the server's
 * own `SENTRY_DSN`, as crash reports do. Without it, the default, nothing leaves the server: the
 * team's feedback is only saved here.
 *
 * What goes: the words someone chose to send (title and body), the name of who sent it, their email
 * only when they ticked to include it, and tags (kind, area, person or agent, versions, and the team
 * as an opaque hash). Pictures they attached go as attachments. Never the team's name or address, a
 * card's text, or a link to anything on the server.
 */
object FeedbackReports {
    /** One item to send. [team] is a hash, never the team's id, slug or name. */
    data class Outgoing(
        val kind: String,
        val area: String?,
        val title: String,
        val body: String,
        val name: String,
        val email: String?,
        val source: String,
        val team: String,
        val client: String?,
        val attachments: List<File> = emptyList(),
    )

    class File(val name: String, val mime: String, val bytes: ByteArray)

    /** Sends one item and answers its event id. Null when the server has nowhere to send feedback. Tests set their own. */
    @Volatile
    internal var sender: ((Outgoing) -> String?)? = null

    val sends: Boolean get() = sender != null

    /** Sends [item] if feedback goes anywhere: its event id, or null when it stays here. */
    fun send(item: Outgoing): String? = sender?.invoke(item)

    /** Called once Sentry is started (CrashReports.start): feedback goes there from now on. */
    internal fun useSentry() {
        sender = ::toSentry
    }

    /** Sentry's user feedback: the message is the title, a blank line and the body, at most [MESSAGE_MAX]. */
    internal fun toSentry(item: Outgoing): String? {
        val feedback = Feedback(messageOf(item)).apply {
            name = item.name
            contactEmail = item.email
        }
        val hint = Hint()
        for (file in item.attachments) hint.addAttachment(Attachment(file.bytes, file.name, file.mime))
        val id = Sentry.captureFeedback(feedback, hint) { scope ->
            for ((key, value) in tagsOf(item)) scope.setTag(key, value)
        }
        return id.toString().takeIf { it != io.sentry.protocol.SentryId.EMPTY_ID.toString() }
    }

    internal fun messageOf(item: Outgoing): String = "${item.title}\n\n${item.body}".take(MESSAGE_MAX)

    internal fun tagsOf(item: Outgoing): Map<String, String> = buildMap {
        put("feedback.kind", item.kind)
        item.area?.let { put("feedback.area", it) }
        put("feedback.source", item.source)
        put("feedback.team", item.team)
        put("server.version", ServerInfo.version)
        item.client?.let { put("client.release", it) }
    }

    /**
     * A feedback event with nothing but what was chosen to be sent: no request, no breadcrumbs,
     * extras or machine name, and an address that finds no town (Sentry would look one up from the
     * connection otherwise).
     */
    internal fun scrub(event: SentryEvent): SentryEvent {
        val keep = event.user?.let { u -> io.sentry.protocol.User().apply { username = u.username; email = u.email; name = u.name } }
        event.user = (keep ?: io.sentry.protocol.User()).apply { ipAddress = "0.0.0.0" }
        event.request = null
        event.serverName = null
        event.extras = null
        event.breadcrumbs = null
        event.contexts.remove("response")
        return event
    }

    /** Sentry keeps a feedback message of up to 4096 characters. */
    const val MESSAGE_MAX = 4096
    /** Pictures attached: at most this many, each at most [ATTACHMENT_MAX_BYTES]. */
    const val ATTACHMENTS_MAX = 3
    const val ATTACHMENT_MAX_BYTES = 2L * 1024 * 1024
}
