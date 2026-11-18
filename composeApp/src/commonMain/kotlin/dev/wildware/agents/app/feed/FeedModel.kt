@file:OptIn(ExperimentalObjCName::class)

package dev.wildware.agents.app.feed

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.MediaRetention
import androidx.compose.runtime.Immutable
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/*
 * What the feed screen draws. These are view shapes only, shaped for the screen. The engine's
 * models are mapped onto them in `engine/Mapping.kt`; [FakeFeed] fills them for screenshots.
 */

enum class Level(val label: String) { Info("Info"), Success("Success"), Warning("Warning"), Error("Error") }

/** A card's work state. [wire] is the dashboard's name for it. */
enum class WorkStatus(val label: String, val wire: String) {
    Todo("To do", "todo"),
    InProgress("In progress", "in_progress"),
    Blocked("Blocked", "blocked"),
    Done("Done", "done"),
    Cancelled("Cancelled", "cancelled"),
    ;

    /** Still going: what "Open work" keeps. */
    val isOpen: Boolean get() = this == Todo || this == InProgress || this == Blocked
}

enum class Presence(val label: String) { Online("Online"), Idle("Idle"), Offline("Offline") }

@Immutable
data class Agent(
    val id: String,
    val name: String,
    val role: String,
    val presence: Presence,
    /** Which identity colour the agent wears. */
    val hue: Int,
    /** What it says it is doing, if it is online. */
    val doing: String? = null,
    /** What its live session says about it; null when it is offline. */
    val details: AgentDetails? = null,
)

/** An online agent's session, as the website's rail lists it. */
@Immutable
data class AgentDetails(
    val model: String?,
    val host: String?,
    /** The folder it is working in. */
    val cwd: String?,
    /** How many sessions it has open; more than one is worth saying. */
    val sessions: Int,
    /** "just now", "45s ago", "3m ago": its last heartbeat. */
    val lastSeen: String,
) {
    /** All of it in one line for a screen reader. */
    val spoken: String
        get() = listOfNotNull(
            model,
            host?.let { "on $it" },
            cwd?.let { "in $it" },
            "$sessions sessions".takeIf { sessions > 1 },
            "last seen $lastSeen",
        ).joinToString(", ")
}

@Immutable
data class Project(
    val id: String,
    val name: String,
    val unread: Int,
    val waiting: Int = 0,
    val hue: Int,
    /** The project's address on the dashboard; null for "All projects". */
    val slug: String? = null,
    /** Finished with: listed apart, under "Archived". */
    val archived: Boolean = false,
)

sealed interface Author {
    val name: String

    data class AgentAuthor(val agent: Agent) : Author {
        override val name: String get() = agent.name
    }

    data class Owner(override val name: String) : Author
}

@Immutable
data class Reaction(val emoji: String, val count: Int, val mine: Boolean, val spoken: String)

/** What agents have said about a line: who has read it, and who is working on a reply. */
@Immutable
data class Acks(val read: List<Agent> = emptyList(), val thinking: List<Agent> = emptyList())

// The iPhone framework also exports the engine's own Attachment, so this one goes by another name there.
@ObjCName("FeedAttachment")
@Immutable
data class Attachment(
    val title: String,
    val altText: String,
    val aspect: Float,
    /** The stand-in picture when there is no [url]: previews and screenshots. */
    val scene: Scene = Scene.Dusk,
    /** Where the real image is (a video's poster frame). Loaded with the device key. */
    val url: String? = null,
    /** Set when this tile is a video: [url] is then its poster, and a tap plays it. */
    val video: VideoSource? = null,
    /** The owner's media retention removed it this many days on: a quiet placeholder shows, and nothing loads. */
    val removedAfterDays: Int? = null,
) {
    val isRemoved: Boolean get() = removedAfterDays != null
}

/** Pictures to look through full size, starting at [index]. */
@Immutable
data class Gallery(val images: List<Attachment>, val index: Int = 0)

/** Which stand-in picture to paint until real images load. */
enum class Scene { Dusk, Dawn, Night }

/** What a line or card still in the outbox says instead of its time. */
const val SendingLabel = "Sending…"

/** How faded a line still in the outbox is drawn. */
const val SendingAlpha = 0.6f

/** One of a project's words for its cards, such as "bug". [colour] is a palette name: rose, amber, green, blue, violet or grey. */
@Immutable
data class CardTag(val id: String, val name: String, val colour: String? = null, val projectId: String? = null)

/** A named note on a card, such as "branch: fix/unread". Agents and the owner both set them. */
@Immutable
data class CardField(val name: String, val value: String)

@Immutable
data class Reply(
    val id: String,
    val author: Author,
    val time: String,
    val body: String,
    val reactions: List<Reaction> = emptyList(),
    val unread: Boolean = false,
    /** Written here and still on its way to the dashboard. */
    val sending: Boolean = false,
    /** Questions an agent asked under this reply. They are answered right here, as well as at the top. */
    val questions: List<FeedItem.Question> = emptyList(),
    /** The owner's line, sent a while ago, and no agent has it: nobody is listening. */
    val undelivered: Boolean = false,
    /** Pictures and videos on the reply, as tiles; sounds and files in their own rows. */
    val attachments: List<Attachment> = emptyList(),
    val sounds: List<Sound> = emptyList(),
    val files: List<FileAttachment> = emptyList(),
    /** Agents reading it or thinking about it: "scout is thinking…". */
    val acks: Acks = Acks(),
)

/**
 * How many options a question that gathers picks takes, both ends included. [bounded] when the agent
 * set a range worth telling the owner about, as "Choose between 2 and 3."
 */
@Immutable
data class PickBounds(val min: Int = 1, val max: Int = Int.MAX_VALUE, val bounded: Boolean = false) {
    fun allows(count: Int): Boolean = count in min..max

    /** What to tell the owner, in plain words, for a question with [options] options; null when there are no bounds. */
    fun hint(options: Int): String? = when {
        !bounded -> null
        min == max -> "Choose $min."
        min == 0 && max >= options -> "Choose any, or none."
        max >= options -> "Choose at least $min."
        min <= 1 -> "Choose up to $max."
        else -> "Choose between $min and $max."
    }
}

@Immutable
data class Choice(
    val label: String,
    val primary: Boolean = false,
    /**
     * A picture to choose by. When the options have pictures the card shows them as a grid of
     * tiles, and only there: they are never repeated in a media grid.
     */
    val image: Attachment? = null,
)

/** How a question is answered, matching the dashboard's request kinds. */
enum class QuestionKind {
    /** Each option answers at once. */
    Buttons,

    /** Pick one, then send. */
    Choice,

    /** Pick any number, then send. */
    MultiChoice,

    /** Answer in words. */
    Text,

    /** Edit the agent's draft in [FeedItem.Question.form], then pick one of its own actions. */
    Form,

    /** A kind newer than this app: it says to update the app. */
    Unsupported,
}

/**
 * The field on a form question: the agent's draft (or an empty box) for the owner to change, and
 * the agent's own actions, each of which sends the field with it as one answer.
 */
@Immutable
data class FormField(
    /** What the field is, e.g. "Commit message". Null when the agent didn't say. */
    val label: String? = null,
    val placeholder: String = "",
    /** The agent's draft, already in the box. */
    val initial: String = "",
    val multiline: Boolean = false,
    val min: Int = 0,
    val max: Int? = null,
    val actions: List<String> = emptyList(),
) {
    /**
     * Why [text] can't be sent yet, or null when it can. Counted trimmed, as the server counts it;
     * the server still checks every answer itself.
     */
    fun problem(text: String): String? {
        val length = text.trim().length
        val most = max ?: ServerMost
        return when {
            length < min && min == 1 -> "Write something first."
            length < min -> "At least $min characters ($length so far)."
            length > most -> (length - most).let { over -> "$over character${if (over == 1) "" else "s"} too long (at most $most)." }
            else -> null
        }
    }

    private companion object {
        /** The longest text answer the dashboard takes when the agent set no maximum. */
        const val ServerMost = 10_000
    }
}

/** A card that can have a thread under it. */
sealed interface Threaded : FeedItem {
    val title: String
    val starter: Author
    val replies: List<Reply>
    val unreadReplies: Int
}

sealed interface FeedItem {
    val id: String
    val time: String

    @Immutable
    data class Update(
        override val id: String,
        override val time: String,
        val agent: Agent,
        val project: Project,
        val level: Level,
        override val title: String,
        val body: String,
        val status: WorkStatus? = null,
        val attachments: List<Attachment> = emptyList(),
        /** Sounds, played where they sit. */
        val sounds: List<Sound> = emptyList(),
        /** Files, to download. */
        val files: List<FileAttachment> = emptyList(),
        val reactions: List<Reaction> = emptyList(),
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val tags: List<CardTag> = emptyList(),
        val fields: List<CardField> = emptyList(),
        /** The agent marked it high priority. Shown as a red "High" chip; it doesn't change the order. */
        val high: Boolean = false,
        /** Pinned above the rest of the feed. */
        val pinned: Boolean = false,
    ) : Threaded {
        override val starter: Author get() = Author.AgentAuthor(agent)
    }

    @Immutable
    data class Question(
        override val id: String,
        override val time: String,
        val agent: Agent,
        val project: Project,
        val prompt: String,
        val body: String,
        val choices: List<Choice>,
        val allowsText: Boolean,
        val expiresIn: String,
        val kind: QuestionKind = QuestionKind.Buttons,
        /** An answer is in the outbox, waiting for signal. */
        val sending: Boolean = false,
        /** Pictures on the question itself, not on its options. */
        val attachments: List<Attachment> = emptyList(),
        val sounds: List<Sound> = emptyList(),
        val files: List<FileAttachment> = emptyList(),
        /** A form question's field and actions; null for every other kind. */
        val form: FormField? = null,
        /** A text question's box: the agent's placeholder, draft, lines and length. Null for every other kind. */
        val text: FormField? = null,
        /** When it times out, in epoch milliseconds, for a live countdown. Null shows [expiresIn] as it is. */
        val expiresAt: Long? = null,
        /** For a question asked inside a thread: what it answers, such as "forge: The watch reads…". */
        val inReplyTo: String? = null,
        /** The card whose thread it was asked in, to jump there. */
        val threadId: String? = null,
        /** How many picks a multi-choice question takes. */
        val picks: PickBounds = PickBounds(),
        /** The talk about the question before it is answered: its own thread, oldest first. */
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
    ) : Threaded {
        val hasPictures: Boolean get() = choices.any { it.image != null }
        override val title: String get() = prompt
        override val starter: Author get() = Author.AgentAuthor(agent)
    }

    /** Something written straight into the feed, by the owner or by an agent. */
    @Immutable
    data class Post(
        override val id: String,
        override val time: String,
        val author: Author,
        val project: Project,
        val body: String,
        /** Pictures and videos on the post, as tiles; sounds and files in their own rows. */
        val attachments: List<Attachment> = emptyList(),
        val sounds: List<Sound> = emptyList(),
        val files: List<FileAttachment> = emptyList(),
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val reactions: List<Reaction> = emptyList(),
        /** Written here and still on its way to the dashboard. */
        val sending: Boolean = false,
        /** Questions an agent asked under the post itself, shown at the top of its thread. */
        val questions: List<Question> = emptyList(),
        val tags: List<CardTag> = emptyList(),
        val fields: List<CardField> = emptyList(),
        /** Its work state, when someone marked it as work. */
        val status: WorkStatus? = null,
        /** Pinned above the rest of the feed. */
        val pinned: Boolean = false,
        /** The owner wrote it, sent it a while ago, and no agent has it: nobody is listening. */
        val undelivered: Boolean = false,
    ) : Threaded {
        override val title: String get() = body.lineSequence().first().take(120)
        override val starter: Author get() = author
    }
}

@Immutable
data class FeedSection(val label: String, val items: List<FeedItem>)

// The engine has a FeedState too; on iPhone this one is FeedScreenState.
@ObjCName("FeedScreenState")
@Immutable
data class FeedState(
    val owner: Author.Owner,
    val projects: List<Project>,
    val agents: List<Agent>,
    val current: Project,
    val sections: List<FeedSection>,
    /** The card whose thread is open in the side pane. */
    val openThread: Threaded?,
    val totalUnread: Int,
    val link: LinkStatus = LinkStatus.Live,
    val outbox: OutboxSummary = OutboxSummary(),
    /** The first read has landed: an empty feed is really empty, not still loading. */
    val loaded: Boolean = true,
    val loading: Boolean = false,
    /** There are older cards to read further down. */
    val hasMore: Boolean = false,
    /** What search looks through: every project's loaded cards, not only the one on screen. Empty means "what's on screen". */
    val searchIndex: List<SearchEntry> = emptyList(),
    /** Every word the projects on screen use for their cards, for the tag picker. */
    val tags: List<CardTag> = emptyList(),
    val bell: Bell = Bell(),
    /** Which alerts this device gets. */
    val alerts: AlertSettings = AlertSettings(),
    /** How long the dashboard keeps media. */
    val retention: RetentionSettings = RetentionSettings(),
) {
    fun item(id: String): FeedItem? = sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.id == id } }
}

/** The bell: everything the owner was told about, newest first, and how many are new. */
@Immutable
data class Bell(val unseen: Int = 0, val items: List<BellItem> = emptyList())

/** One row under the bell. [path] is where it leads, e.g. `/projects/mega?focus=u1`. */
@Immutable
data class BellItem(
    val id: String,
    val title: String,
    val body: String,
    val project: String?,
    val time: String,
    val unread: Boolean,
    val path: String,
)

/**
 * Which alerts this device gets, as the dashboard keeps it. [loaded] is false until it has been
 * read; [problem] says why it couldn't be read or saved.
 */
@Immutable
data class AlertSettings(val prefs: AlertPrefs? = null, val loaded: Boolean = false, val problem: String? = null)

/**
 * How long the dashboard keeps media, as it has it. [loaded] is false until it has been read;
 * [saving] while a change is on its way; [problem] says why it couldn't be read or saved, in the
 * dashboard's own words when it gave some. [saves] counts the saves that landed.
 *
 * [allowed] is the one gate: whether this person may see and change it. Today that is the owner,
 * which every signed-in device is, and the dashboard refusing the read turns it off. With teams it
 * becomes admins only: decide it here and every way in (the account sheet, the command bar) follows.
 */
@Immutable
data class RetentionSettings(
    val setting: MediaRetention = MediaRetention(),
    val loaded: Boolean = false,
    val allowed: Boolean = false,
    val saving: Boolean = false,
    val problem: String? = null,
    val saves: Int = 0,
)

/** The live connection, as the header says it. */
enum class LinkStatus(val label: String) {
    Live("Live"),
    Connecting("Connecting…"),
    Reconnecting("Reconnecting…"),
    Offline("Offline"),
}

/** What the owner did that hasn't reached the dashboard yet. */
@Immutable
data class OutboxSummary(
    val waiting: Int = 0,
    val failed: Int = 0,
    /** The first item the dashboard refused, to retry or drop. */
    val failedId: String? = null,
    val failedMessage: String? = null,
)
