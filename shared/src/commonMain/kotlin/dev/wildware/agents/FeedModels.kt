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

/**
 * A channel, as the sidebar lists it (the dashboard still calls it a project). The server orders
 * them: pinned first, then newest.
 */
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
    /** The project (a named group of channels) this channel is in, or null for none. */
    val groupId: String? = null,
    /** Only the people and agents listed can see it (server migration 068). */
    @kotlinx.serialization.SerialName("private") val isPrivate: Boolean = false,
    /** Its place among its project's channels, once someone has arranged them (migration 069). */
    val position: Long? = null,
) {
    val isArchived: Boolean get() = status == "archived"
}

/** A project: a named group of channels (`/api/groups`; the dashboard calls it a group). */
@Serializable
data class GroupView(
    val id: String,
    val seq: Long = 0,
    val slug: String,
    val name: String,
    /** "active" or "archived". */
    val status: String = "active",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val theme: ProjectTheme? = null,
    /** Only the people and agents listed can see it, or any channel in it (server migration 068). */
    @kotlinx.serialization.SerialName("private") val isPrivate: Boolean = false,
    /** Its place among the projects, once someone has arranged them (migration 069). */
    val position: Long? = null,
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
    /** Set once the owner's media retention took the bytes away. Nothing is left to load: [variants] is empty. */
    val removed: Removed? = null,
) {
    val isReady: Boolean get() = status == "ready"

    /** Gone for good: show "Media removed after N days" in its place and never ask for a variant. */
    val isRemoved: Boolean get() = removed != null

    /** When the nightly job removed it ([at], an ISO time) and the setting it followed, in days. */
    @Serializable
    data class Removed(val at: String = "", val afterDays: Int)

    /** The path to one variant, to put after the server address. */
    fun path(variant: String = "original"): String = "media/$id/$variant"
}

/**
 * Another card this one points at (an agent's `refs`): enough of it to draw it small and open its
 * thread. [deleted] when that card has since been removed; then only [kind], [id] and [projectId]
 * are left. For a message, [updateId], [replyTo] or [requestId] says which thread it is in; for a
 * request, [state] where it has got to.
 */
@Serializable
data class CardRefView(
    /** "update", "message" or "request". */
    val kind: String,
    val id: String,
    val projectId: String? = null,
    val deleted: Boolean = false,
    val author: String? = null,
    val authorName: String? = null,
    /** An update's headline or a request's question. */
    val title: String? = null,
    /** The first line of its body (a request's detail), as plain text. */
    val text: String? = null,
    /** Its first picture, video or file. */
    val media: MediaView? = null,
    val mediaCount: Int = 0,
    val updateId: String? = null,
    val replyTo: String? = null,
    val requestId: String? = null,
    val state: String? = null,
) {
    val card: CardRef get() = CardRef(kind, id)
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
    /** Which person, on a team (migration 055); left out for the founder. */
    val userId: String? = null,
) {
    val isOwner: Boolean get() = actor == HumanAuthor

    /** The person who reacted, or null for an agent. */
    val personId: String? get() = if (isOwner) userId ?: FounderId else null
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
    /** Other cards it points at, shown inside it. */
    val refs: List<CardRefView> = emptyList(),
    /** Blocks drawn on it (docs/BLOCKS.md). */
    val blocks: List<BlockView> = emptyList(),
    /** Secrets the agent attached, by name: opened one at a time with [DashboardClient.revealSharedSecret]. */
    val secrets: List<SharedSecretView> = emptyList(),
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
    /** Which person wrote it, on a team (migration 055); left out for the founder. */
    val userId: String? = null,
    /** How it was written: [VoiceNote.Source] for a voice note, null when typed. */
    val source: String? = null,
) {
    /** Said aloud and transcribed, not typed: drawn with a small mic. */
    val isVoice: Boolean get() = source == VoiceNote.Source

    val isPost: Boolean get() = updateId == null && taskId == null && replyTo == null && requestId == null
    val isOwner: Boolean get() = author == HumanAuthor

    /** The person who wrote it, or null for an agent. */
    val personId: String? get() = if (isOwner) userId ?: FounderId else null
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
    /** A secret question (request_secret): the name its value will be kept under. */
    val secretName: String? = null,
    /**
     * A secret question that asks for several (request_secret with `fields`): one box each, saved
     * together. Null for a question that asks for the one [secretName].
     */
    val secretFields: List<SecretFieldView>? = null,
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
    /** How much it needs the owner now; "critical" pages their phones. */
    val priority: String = "medium",
    val reactions: List<ReactionView> = emptyList(),
    /** Who answered, on a team (migration 055); left out for the founder. */
    val answeredBy: String? = null,
    /** Other cards the question points at, shown inside it: the reply with the video it asks about. */
    val refs: List<CardRefView> = emptyList(),
    /** Blocks drawn on the question (docs/BLOCKS.md). */
    val blocks: List<BlockView> = emptyList(),
) {
    /** The person who answered it, or null while it isn't answered. */
    val answeredByPerson: String? get() = if (state == "answered") answeredBy ?: FounderId else null

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
    val groups: List<GroupView>? = null,
    val tags: List<TagView>? = null,
    val cardTags: List<CardTagView>? = null,
    val cardFields: List<CardFieldView>? = null,
    val cardApps: List<AppView>? = null,
    val updates: UpdatesPage = UpdatesPage(),
    val agentNames: Map<String, String>? = null,
    /** Everyone who has been in the team, only once there is more than one person (migration 055). */
    val people: List<Person>? = null,
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
    /** The cards each message points at, by message id; only messages that point at some. */
    val refs: Map<String, List<CardRefView>> = emptyMap(),
    /** The blocks on each message, by message id; only messages that have some. */
    val blocks: Map<String, List<BlockView>> = emptyMap(),
    /** The secrets an agent attached to each message, by message id; only messages that have some. */
    val secrets: Map<String, List<SharedSecretView>> = emptyMap(),
)

/**
 * A secret an agent attached to a post (migration 066): its name, and whether it can still be
 * opened. False once the secret is deleted or paused, or the agent that shared it lost it. The value
 * is never here: it is fetched on demand, for the person's eyes only.
 */
@Serializable
data class SharedSecretView(val name: String, val available: Boolean = true)

/** `GET /api/snapshot/requests`: every question waiting on the owner, never paged or scoped. */
@Serializable
data class RequestsSnapshot(
    val seq: Long,
    val at: String = "",
    val requests: List<RequestView> = emptyList(),
    /** On a team (migration 055): questions answered lately, newest answer first, each saying who answered. */
    val answered: List<RequestView> = emptyList(),
)

/** The agent that asked for a pairing over MCP, with a token it already holds. */
@Serializable
data class PairingAsker(val id: String, val name: String? = null)

/**
 * An agent asking to join the dashboard under [name] (`GET /api/pairings`). The owner checks
 * [code] against what the agent says, may change the name, and allows or denies it.
 */
@Serializable
data class PairingView(
    val id: String,
    val name: String,
    val host: String? = null,
    val cwd: String? = null,
    val address: String? = null,
    val code: String = "",
    val state: String = "pending",
    val askedBy: PairingAsker? = null,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
)

/** `GET /api/pairings`: every agent waiting to be allowed in. */
@Serializable
data class PairingsSnapshot(
    val seq: Long,
    val at: String = "",
    val pairings: List<PairingView> = emptyList(),
)

/** `GET /api/subscriptions`: what the owner chose to follow, per project and per thread. */
@Serializable
data class FollowsSnapshot(
    val seq: Long = 0,
    val at: String = "",
    val projects: List<ProjectNotifyView> = emptyList(),
    val threads: List<ThreadFollowView> = emptyList(),
    val groups: List<GroupNotifyView> = emptyList(),
)

/** A project's (group's) notification level the owner chose, for its channels without their own. */
@Serializable
data class GroupNotifyView(val groupId: String, val level: String)

/** A project's notification level the owner chose: `all`, `replies` or `muted`. */
@Serializable
data class ProjectNotifyView(val projectId: String, val level: String)

/** A thread (`update`, `message` for a post, or `request`) the owner followed or unfollowed. */
@Serializable
data class ThreadFollowView(val kind: String, val id: String, val following: Boolean)

/** How much of a project raises an alert for the owner: on this device's bell and pushes, and on every phone. */
enum class NotifyLevel(val wire: String) {
    /** Everything: the default. */
    All("all"),

    /** Questions, replies to the owner, and messages that name them. */
    Replies("replies"),

    /** Nothing but critical alerts and threads the owner follows. */
    Muted("muted");

    companion object {
        fun of(wire: String?): NotifyLevel = entries.firstOrNull { it.wire == wire } ?: All
    }
}

/**
 * What the owner follows, as the dashboard keeps it. A thread the owner never chose about follows its
 * project's level: followed when the project raises everything.
 */
data class Follows(
    /** The level the owner set on a channel itself, by channel id. */
    val levels: Map<String, NotifyLevel> = emptyMap(),
    /** By "kind:id": true followed, false unfollowed. */
    val threads: Map<String, Boolean> = emptyMap(),
    val loaded: Boolean = false,
    /** The level the owner set on a project (a group of channels), by group id. */
    val groupLevels: Map<String, NotifyLevel> = emptyMap(),
    /** Which group each channel is in, so a channel without its own level takes its group's. */
    val groupOf: Map<String, String> = emptyMap(),
) {
    /** The most specific setting wins: the channel's own, else its project's, else everything. */
    fun level(projectId: String?): NotifyLevel =
        projectId?.let { levels[it] ?: groupOf[it]?.let(groupLevels::get) } ?: NotifyLevel.All

    /** The level set on the channel itself, or null when it follows its project. */
    fun ownLevel(projectId: String): NotifyLevel? = levels[projectId]

    fun groupLevel(groupId: String): NotifyLevel? = groupLevels[groupId]

    /** The owner's own choice about a thread, or null when they never made one. */
    fun chosen(kind: String, id: String): Boolean? = threads[key(kind, id)]

    fun following(kind: String, id: String, projectId: String?): Boolean = chosen(kind, id) ?: (level(projectId) == NotifyLevel.All)

    fun withThread(kind: String, id: String, following: Boolean) = copy(threads = threads + (key(kind, id) to following))

    /** Null clears the channel's own level, so it follows its project again. */
    fun withLevel(projectId: String, level: NotifyLevel?) =
        copy(levels = if (level == null) levels - projectId else levels + (projectId to level))

    fun withGroupLevel(groupId: String, level: NotifyLevel?) =
        copy(groupLevels = if (level == null) groupLevels - groupId else groupLevels + (groupId to level))

    companion object {
        fun key(kind: String, id: String) = "$kind:$id"

        fun of(snapshot: FollowsSnapshot) = Follows(
            levels = snapshot.projects.associate { it.projectId to NotifyLevel.of(it.level) },
            threads = snapshot.threads.associate { key(it.kind, it.id) to it.following },
            loaded = true,
            groupLevels = snapshot.groups.associate { it.groupId to NotifyLevel.of(it.level) },
        )
    }
}

/** `GET /api/snapshot/agents`: who has beaten lately. */
@Serializable
data class AgentsSnapshot(
    val seq: Long,
    val at: String = "",
    val agents: List<LiveAgentView> = emptyList(),
)

/**
 * One thing the owner was told about (an update, a reply, a question), as the bell lists it.
 * [path] is where it leads: a dashboard route such as `/projects/mega?focus=u5`.
 */
@Serializable
data class NotificationView(
    val id: String,
    val seq: Long = 0,
    /** "update", "reply" or "request". */
    val kind: String = "update",
    val projectId: String? = null,
    val projectSlug: String? = null,
    val projectName: String? = null,
    val updateId: String? = null,
    val messageId: String? = null,
    val requestId: String? = null,
    val agentId: String? = null,
    val title: String = "",
    val body: String = "",
    val createdAt: Long = 0,
    /** When the owner looked at it, or null while it is new. */
    val seenAt: Long? = null,
    val path: String = "/",
)

/** `POST /api/notifications/handled`: which of the asked tags are done with. */
@Serializable
data class HandledNotifications(val handled: List<String> = emptyList())

/** The most notification tags one `handled` call asks about; the dashboard takes no more. */
const val MaxClearTags = 500

/** `GET /api/notifications`: newest first, with how many are still new. */
@Serializable
data class NotificationsSnapshot(
    val seq: Long,
    val at: String = "",
    val notifications: List<NotificationView> = emptyList(),
    val unseen: Int = 0,
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

/** The founding owner's membership id (migration 055): a person's row with no userId is theirs. */
const val FounderId = "founder"
internal const val AgentPrefix = "agent:"

@Serializable
internal data class MediaCreatedBody(val media: MediaView)

@Serializable
internal data class MessageCreatedBody(val message: MessageView)

@Serializable
internal data class ReactionsBody(val reactions: List<ReactionView> = emptyList(), val on: Boolean? = null)

@Serializable
internal data class StreamEnvelope(val type: String, val seq: Long? = null, val payload: JsonObject? = null)
