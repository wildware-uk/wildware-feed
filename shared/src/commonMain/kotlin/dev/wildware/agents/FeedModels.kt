package dev.wildware.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The dashboard's JSON, as the website reads it (src/web/types.ts on the server).
 *
 * Field names match the server exactly. Words the server may add to over time (levels, states,
 * kinds) are kept as strings, so a new one on the server never stops the app reading the feed.
 * Times are milliseconds since the epoch. Ids are strings. `seq` is the server's event counter.
 */

/** A project, as the sidebar lists it. The server orders them: pinned first, then newest. */
@Serializable
data class ProjectView(
    val id: String,
    val seq: Long = 0,
    val slug: String,
    val name: String,
    val description: String? = null,
    /** "active" or "archived". */
    val status: String = "active",
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val theme: ProjectTheme? = null,
) {
    val isArchived: Boolean get() = status == "archived"
}

/** A project's own colours (#rrggbb) and logo (a media id). */
@Serializable
data class ProjectTheme(
    val background: String? = null,
    val accent: String? = null,
    val logoMediaId: String? = null,
    val logoReplacesName: Boolean? = null,
)

/** Which card something is on. [kind] is "update", "message", "request" or "task". */
@Serializable
data class CardRef(val kind: String, val id: String)

/** One attachment. Its bytes are at `/media/{id}/{variant}` for each of [variants]. */
@Serializable
data class MediaView(
    val id: String,
    val updateId: String? = null,
    val messageId: String? = null,
    val requestId: String? = null,
    val taskId: String? = null,
    /** "image", "video", "audio" or "file". */
    val kind: String = "file",
    val mime: String = "application/octet-stream",
    /** "pending", "ready" or "failed". Only "ready" has anything to show. */
    val status: String = "pending",
    val bytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val filename: String? = null,
    /** "original", "thumb-640", "thumb-1600", "poster", "video": the addresses that answer right now. */
    val variants: List<String> = emptyList(),
) {
    val isReady: Boolean get() = status == "ready"

    /** The path to one variant, to put after the server address. */
    fun path(variant: String = "original"): String = "media/$id/$variant"
}

/** A card shown in the style of where it came from. [spec] is the look, [data] the values. */
@Serializable
data class EmbedView(
    val id: String,
    val updateId: String? = null,
    val messageId: String? = null,
    val requestId: String? = null,
    val taskId: String? = null,
    val type: String,
    val typeName: String = "",
    val spec: JsonObject = JsonObject(emptyMap()),
    val data: JsonObject = JsonObject(emptyMap()),
    val sourceUrl: String? = null,
    val createdAt: Long = 0,
)

/** One emoji on a card. [actor] is "human" for the owner or "agent:<id>". */
@Serializable
data class ReactionView(
    val id: String,
    val seq: Long = 0,
    val card: CardRef,
    val messageId: String? = null,
    val actor: String,
    val emoji: String,
    val createdAt: Long = 0,
) {
    val isOwner: Boolean get() = actor == HumanAuthor
}

/** An agent saying "thinking", "read" or "done" about a message without words. */
@Serializable
data class AckView(
    val id: String,
    val seq: Long = 0,
    val agentId: String,
    val card: CardRef,
    val messageId: String? = null,
    val taskId: String? = null,
    val state: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One message handed to one agent: "delivered to scout". */
@Serializable
data class DeliveryView(
    val id: String,
    val seq: Long = 0,
    val messageId: String,
    val agentId: String,
    val deliveredAt: Long = 0,
)

/** A word a project uses to label cards ("bug", "feature"). */
@Serializable
data class TagView(
    val id: String,
    val projectId: String,
    val name: String,
    val slug: String,
    val colour: String? = null,
    val agentId: String? = null,
    val createdAt: Long = 0,
)

/** One of those words on one card. */
@Serializable
data class CardTagView(
    val id: String,
    val tagId: String,
    val card: CardRef,
    val author: String,
    val createdAt: Long = 0,
)

/** A note on a card, hidden by default: a branch name, a PR link. */
@Serializable
data class CardFieldView(
    val id: String,
    val card: CardRef,
    val name: String,
    val slug: String,
    val value: String,
    val author: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** An app showing on a card. */
@Serializable
data class AppView(
    val id: String,
    val card: CardRef,
    val uri: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val title: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    val state: JsonObject? = null,
    val author: String,
    val agentId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** One update an agent posted. [body] is markdown. */
@Serializable
data class UpdateView(
    val id: String,
    val seq: Long,
    val projectId: String,
    val agentId: String,
    val sessionId: String? = null,
    val title: String? = null,
    val body: String,
    /** "info", "success", "warn" or "error". */
    val level: String = "info",
    /** "low", "medium" or "high". */
    val priority: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val editedAt: Long? = null,
    val taskId: String? = null,
    val share: Share? = null,
    val reactions: List<ReactionView> = emptyList(),
    /** Where the work on this card has got to, or null for an ordinary card. */
    val status: String? = null,
    val statusAt: Long? = null,
    val media: List<MediaView> = emptyList(),
    val embeds: List<EmbedView> = emptyList(),
) {
    @Serializable
    data class Share(val views: Int = 0, val sharedAt: Long = 0)
}

/**
 * One message. [author] is "human" for the owner or "agent:<id>".
 *
 * Where it sits: [updateId] is a reply on an update, [replyTo] a reply on a post, [requestId] talk
 * about a question, [taskId] a task's thread. With none of those it is a post: a card of its own.
 */
@Serializable
data class MessageView(
    val id: String,
    val seq: Long = 0,
    val projectId: String? = null,
    val updateId: String? = null,
    val taskId: String? = null,
    val replyTo: String? = null,
    /** The message in the same thread this one answers. A label, not nesting. */
    val answers: String? = null,
    val requestId: String? = null,
    val author: String,
    val body: String,
    val createdAt: Long,
    val pinned: Boolean = false,
    val status: String? = null,
    val statusAt: Long? = null,
    val editedAt: Long? = null,
) {
    val isPost: Boolean get() = updateId == null && taskId == null && replyTo == null && requestId == null
    val isOwner: Boolean get() = author == HumanAuthor
    val agentId: String? get() = author.takeIf { it.startsWith(AgentPrefix) }?.removePrefix(AgentPrefix)
}

/** The knobs a question's control reads. [default] is the starting text or pre-picked option. */
@Serializable
data class RequestConfig(
    val placeholder: String? = null,
    val multiline: Boolean? = null,
    /** `default` on the wire, a keyword in Swift. */
    @SerialName("default") val defaultValue: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val label: String? = null,
    /** Picture questions (buttons, choice, multi_choice): the media id of each option's picture, by option. */
    val optionMedia: Map<String, String>? = null,
)

/** A question an agent is stopped on. [seq] is queue order: the longest blocked first. */
@Serializable
data class RequestView(
    val id: String,
    val seq: Long = 0,
    val agentId: String,
    val projectId: String? = null,
    val updateId: String? = null,
    /** The message it was asked under. Such a question shows in that thread, not at the top. */
    val messageId: String? = null,
    /** "text", "confirm", "buttons", "choice", "multi_choice" or "form". */
    val kind: String,
    val question: String,
    val detail: String? = null,
    val options: List<String>? = null,
    val config: RequestConfig? = null,
    /** "pending", "answered", "timeout" or "cancelled". */
    val state: String = "pending",
    val expiresAt: Long = 0,
    val answeredAt: Long? = null,
    /** `{kind, value}` once answered. */
    val answer: JsonElement? = null,
    val media: List<MediaView> = emptyList(),
    val embeds: List<EmbedView> = emptyList(),
    val editedAt: Long? = null,
    val pinned: Boolean = false,
    val reactions: List<ReactionView> = emptyList(),
) {
    /** Null for a kind this app doesn't know yet: show it, and send the owner to the website to answer it. */
    val requestKind: RequestKind? get() = RequestKind.fromWire(kind)

    /** The picture on [option], for a picture question. Null when it has none, or its media isn't on this request. */
    fun pictureFor(option: String): MediaView? {
        val mediaId = config?.optionMedia?.get(option) ?: return null
        return media.firstOrNull { it.id == mediaId }
    }

    /** Whether [media] belongs to an option rather than to the question itself. */
    fun isOnOption(media: MediaView): Boolean = config?.optionMedia?.containsValue(media.id) == true

    /** The pictures for the card's own grid: everything not shown on an option. The answer is still the option's text. */
    val cardMedia: List<MediaView> get() = media.filter { !isOnOption(it) }
}

/** An agent beating right now. */
@Serializable
data class LiveAgentView(
    val agentId: String,
    val name: String,
    val sessionId: String = "",
    val startedAt: Long = 0,
    val lastHeartbeatAt: Long = 0,
    val sessions: Int = 1,
    val host: String? = null,
    val cwd: String? = null,
    val model: String? = null,
)

/** A page of updates. Pass [nextCursor] back to read further into the past. */
@Serializable
data class UpdatesPage(
    val items: List<UpdateView> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/** `GET /api/snapshot` and `GET /api/snapshot/updates`. The second carries only [updates]. */
@Serializable
data class FeedSnapshot(
    val seq: Long,
    val at: String = "",
    val projects: List<ProjectView>? = null,
    val tags: List<TagView>? = null,
    val cardTags: List<CardTagView>? = null,
    val cardFields: List<CardFieldView>? = null,
    val cardApps: List<AppView>? = null,
    val updates: UpdatesPage = UpdatesPage(),
    val agentNames: Map<String, String>? = null,
)

/** `GET /api/messages`: every thread in scope, oldest first. */
@Serializable
data class MessagesSnapshot(
    val seq: Long,
    val at: String = "",
    val messages: List<MessageView> = emptyList(),
    val acks: List<AckView> = emptyList(),
    val media: Map<String, List<MediaView>> = emptyMap(),
    val embeds: Map<String, List<EmbedView>> = emptyMap(),
    val deliveries: List<DeliveryView> = emptyList(),
    val reactions: List<ReactionView> = emptyList(),
)

/** `GET /api/snapshot/requests`: every question waiting on the owner, never paged or scoped. */
@Serializable
data class RequestsSnapshot(
    val seq: Long,
    val at: String = "",
    val requests: List<RequestView> = emptyList(),
)

/** `GET /api/snapshot/agents`: who has beaten lately. */
@Serializable
data class AgentsSnapshot(
    val seq: Long,
    val at: String = "",
    val agents: List<LiveAgentView> = emptyList(),
)

/** What `POST /api/messages` sends. Exactly one of [project], [update], [replyTo], [request] or [task] says where. */
@Serializable
data class NewMessage(
    val body: String,
    val project: String? = null,
    val update: String? = null,
    val task: String? = null,
    val replyTo: String? = null,
    val answers: String? = null,
    val request: String? = null,
    val mediaIds: List<String>? = null,
)

/** The card kinds the server knows, as its URLs spell them. */
object CardKinds {
    const val Update = "update"
    const val Message = "message"
    const val Request = "request"
    const val Task = "task"
}

/** The owner's author string on messages and reactions. */
const val HumanAuthor = "human"
internal const val AgentPrefix = "agent:"

@Serializable
internal data class MediaCreatedBody(val media: MediaView)

@Serializable
internal data class MessageCreatedBody(val message: MessageView)

@Serializable
internal data class ReactionsBody(val reactions: List<ReactionView> = emptyList(), val on: Boolean? = null)

@Serializable
internal data class StreamEnvelope(val type: String, val seq: Long? = null, val payload: JsonObject? = null)
