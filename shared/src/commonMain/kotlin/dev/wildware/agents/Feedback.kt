package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * A team's feedback and feature requests, as `GET /api/feedback` lists them (server/spec/14-feedback.md):
 * people's, sent at once, and agents', which wait for an owner or an admin to send them. [recipient]
 * is who they go to, as the app should name them; [sends] is false on a server that sends them
 * nowhere (no SENTRY_DSN), where they are only saved.
 */
@Serializable
data class FeedbackList(
    val recipient: String = "",
    val sends: Boolean = false,
    val kinds: List<String> = emptyList(),
    val areas: List<String> = emptyList(),
    val feedback: List<FeedbackItem> = emptyList(),
)

/** One item. Times are epoch milliseconds. [status] is pending, discarded, sent or saved. */
@Serializable
data class FeedbackItem(
    val id: String,
    val kind: String,
    val area: String? = null,
    val title: String,
    val body: String,
    val status: String,
    val author: FeedbackAuthor,
    val card: FeedbackCard? = null,
    val requestId: String? = null,
    val approvedBy: FeedbackPerson? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
)

/** Who wrote it: [kind] is "person" or "agent". */
@Serializable
data class FeedbackAuthor(val kind: String, val id: String = "", val name: String = "")

@Serializable
data class FeedbackPerson(val id: String, val name: String? = null)

/** The card it is about: an update, a message or a request. */
@Serializable
data class FeedbackCard(val kind: String, val id: String)

/**
 * What a person sends. [area] and [card] may be null. [includeEmail] lets the people it goes to write
 * back; [client] is the app's release, such as "wildware-feed@1.0.19".
 */
data class FeedbackDraft(
    val kind: String,
    val area: String?,
    val title: String,
    val body: String,
    val card: FeedbackCard?,
    val includeEmail: Boolean,
    val client: String?,
)

@Serializable
internal data class FeedbackBody(val feedback: FeedbackItem)

object FeedbackKinds {
    const val Feedback = "feedback"
    const val FeatureRequest = "feature_request"
}
