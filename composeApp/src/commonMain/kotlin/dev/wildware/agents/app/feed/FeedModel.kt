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
    /** What it is for, in the owner's words. */
    val description: String? = null,
    /** Its own page colour and accent, #rrggbb; null for the app's own. */
    val background: String? = null,
    val accent: String? = null,
    /** Pinned to the top of the project list. */
    val pinned: Boolean = false,
    /** Its logo, from a picture posted into it; shown by its name above the feed. */
    val logo: Attachment? = null,
    /** Which picture [logo] is, to mark it in the picker. */
    val logoMediaId: String? = null,
    /** The logo stands in for the name above the feed: a wordmark. */
    val logoInsteadOfName: Boolean = false,
    /** The project ([ProjectGroup]) this channel is in, or null for none. */
    val groupId: String? = null,
    /** Its place among its project's channels once someone has dragged them into an order; null before. */
    val position: Long? = null,
)

/**
 * A project: a named group of channels. ([Project] is a channel; the dashboard still calls channels
 * projects, and calls these groups.)
 */
@Immutable
data class ProjectGroup(
    val id: String,
    val slug: String,
    val name: String,
    /** Its own colour, #rrggbb, or null. */
    val accent: String? = null,
    /** Finished with: it and its channels list under Archived. */
    val archived: Boolean = false,
    /** Its place among the projects once someone has dragged them into an order; null before. */
    val position: Long? = null,
)

/**
 * One box on a question that asks for several secrets: the [name] its value is kept under, the
 * agent's [label] and [hint], whether it takes several lines, and whether it may be left empty.
 */
@Immutable
data class SecretAsk(
    val name: String,
    val label: String? = null,
    val hint: String? = null,
    val multiline: Boolean = false,
    val optional: Boolean = false,
)

/** A picture already posted into a project, offered as its logo. */
@Immutable
data class LogoChoice(val mediaId: String, val image: Attachment)

sealed interface Author {
    val name: String

    data class AgentAuthor(val agent: Agent) : Author {
        override val name: String get() = agent.name
    }

    data class Owner(override val name: String) : Author

    /** Another person on the team (migration 055): [handle] is how an @mention names them. */
    data class Person(override val name: String, val handle: String, val hue: Int) : Author
}

@Immutable
data class Reaction(val emoji: String, val count: Int, val mine: Boolean, val spoken: String)

/**
 * What agents have said about a line: who is working on a reply, who finished with it, who has read
 * it, and who has it but has said nothing yet. An agent is in one list only, the furthest along.
 */
@Immutable
data class Acks(
    val read: List<Agent> = emptyList(),
    val thinking: List<Agent> = emptyList(),
    /** Agents that marked it done. */
    val done: List<Agent> = emptyList(),
    /** Agents the dashboard handed it to that have said nothing about it yet. */
    val delivered: List<Agent> = emptyList(),
) {
    val isEmpty: Boolean get() = read.isEmpty() && thinking.isEmpty() && done.isEmpty() && delivered.isEmpty()
}

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
    /** Not ready to show: the dashboard is still making it, or never could. A placeholder says which. */
    val notReady: NotReady? = null,
    /** The picture as it was uploaded, to save (never a thumbnail); a video's is on [video]. */
    val original: FileAttachment? = null,
) {
    val isRemoved: Boolean get() = removedAfterDays != null

    /** What Save keeps: the original upload. Nothing for a placeholder, which has nothing to keep. */
    val saveable: FileAttachment? get() = if (isPlaceholder) null else video?.original ?: original

    /** Holds a placeholder rather than a picture or video: nothing to load, open or play. */
    val isPlaceholder: Boolean get() = isRemoved || notReady != null
}

/** Why a picture or video has nothing to show yet, in the words its placeholder uses. */
enum class NotReady(val words: String) {
    /** The dashboard is still making its thumbnails; it shows itself when ready. */
    Processing("Processing…"),

    /** The dashboard couldn't read it, so it never will. */
    Failed("Media unavailable"),

    /** Still on this device, going up with the owner's message. */
    Uploading("Uploading…"),
}

/** Pictures to look through full size, starting at [index]. */
@Immutable
data class Gallery(val images: List<Attachment>, val index: Int = 0)

/**
 * An app an agent put on a card (the website ran it in a frame). [url] is set when it is a page of
 * its own, which a browser can open; one the dashboard hosts needs the dashboard's sign-in, so it has none.
 */
@Immutable
data class CardApp(val name: String, val title: String? = null, val description: String? = null, val url: String? = null)

/** Which stand-in picture to paint until real images load. */
enum class Scene { Dusk, Dawn, Night }

/** What a line or card still in the outbox says instead of its time. */
const val SendingLabel = "Sending…"

/** What it says while its files go up: "Uploading 45%". */
fun sendingLabel(uploaded: Float?): String = if (uploaded == null) SendingLabel else "Uploading ${(uploaded * 100).toInt()}%"

/** How faded a line still in the outbox is drawn. */
const val SendingAlpha = 0.6f

/** One of a project's words for its cards, such as "bug". [colour] is a palette name: rose, amber, green, blue, violet or grey. */
@Immutable
data class CardTag(val id: String, val name: String, val colour: String? = null, val projectId: String? = null)

/**
 * A block on a card (docs/BLOCKS.md), as the server last had it, with what the screen needs to send
 * a tick or a vote back: the card it is on, and which person you are.
 */
@Immutable
data class CardBlock(
    val cardKind: String,
    val cardId: String,
    val block: dev.wildware.agents.BlockView,
    /** Your person id on the team: which tick and which vote is yours. */
    val me: String = dev.wildware.agents.FounderId,
    /** Whether the team has more than one person, so names are worth showing. */
    val team: Boolean = false,
)

/**
 * Another card this one points at, drawn small inside it: an agent asks about the reply it just
 * posted with a video, and the video sits in the question. A tap opens that card's thread, at it.
 * [removed] when that card has gone since; it then says so and opens nothing.
 */
@Immutable
data class LinkedCard(
    /** "update", "message" or "request". */
    val kind: String,
    val id: String,
    val removed: Boolean = false,
    /** Who wrote it: an agent's name, or "You". */
    val who: String = "",
    /** What it is, in a word: "reply", "post", "update" or "question". */
    val what: String = "",
    /** Its project's name, when it is in another project than the card pointing at it. */
    val where: String? = null,
    /** An update's headline or a question's wording. */
    val title: String? = null,
    /** The first line of what it says. */
    val text: String? = null,
    /** Its first picture or video, which opens or plays as it does on its own card. */
    val media: Attachment? = null,
    /** About the rest of its files: "+2 more", or "1 file" when there is no picture to show. */
    val mediaNote: String? = null,
    /** Its project's slug, to show that project when the one on screen is another. */
    val projectSlug: String? = null,
) {
    /** What it is read out as: "forge's reply: Here it is". */
    val spoken: String
        get() = if (removed) "A $what that was removed" else listOfNotNull(
            "${if (who == "You") "Your" else "$who's"} $what".trim(),
            where?.let { "in $it" },
        ).joinToString(" ") + (title ?: text)?.let { ": $it" }.orEmpty()
}

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
    /** While [sending]: how much of its files have gone up, 0 to 1; null when none are going up. */
    val uploaded: Float? = null,
    /** Said aloud and transcribed, not typed: drawn with a small mic. */
    val voice: Boolean = false,
    /** While [sending]: a voice note that can still be taken back, until then (epoch millis). */
    val undoUntil: Long? = null,
    /** Questions an agent asked under this reply. They are answered right here, as well as at the top. */
    val questions: List<FeedItem.Question> = emptyList(),
    /** The owner's line, sent a while ago, and no agent has it: nobody is listening. */
    val undelivered: Boolean = false,
    /** Pictures and videos on the reply, as tiles; sounds and files in their own rows. */
    val attachments: List<Attachment> = emptyList(),
    val sounds: List<Sound> = emptyList(),
    val files: List<FileAttachment> = emptyList(),
    val embeds: List<Embed> = emptyList(),
    /** Agents reading it or thinking about it: "scout is thinking…". */
    val acks: Acks = Acks(),
    /** When it was last changed, as "Edited Monday 3 August 2026 at 14:05"; null when never. Drawn as "edited". */
    val edited: String? = null,
    /** The whole instant behind [time], as "Posted Monday 3 August 2026 at 14:05 · updated …". Shown on hover, read aloud. */
    val timeDetail: String = "",
    /** Who wrote the line in this thread that this one answers: an agent's name, or "you". Drawn as "answering forge". */
    val answering: String? = null,
    /** Other cards it points at, drawn small under what it says. */
    val refs: List<LinkedCard> = emptyList(),
    /** Blocks drawn on it: a checklist, steps, a diff, a chart, a table, a log or a poll. */
    val blocks: List<CardBlock> = emptyList(),
    /** Secrets the agent attached, drawn as locked chips the owner opens in place. */
    val secrets: List<SharedSecret> = emptyList(),
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

/**
 * An answer the dashboard refused for good. [problem] says why; [answer] is what the owner said, as
 * they'd say it ("Yes", "Mac, Windows"); [typed] is any words they wrote, to put back in the box.
 */
@Immutable
data class Unsent(val outboxId: String, val problem: String, val answer: String? = null, val typed: String? = null)

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

    /** A secret the agent asks for: typed into a masked box and sent at once, never queued or kept. */
    Secret,

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
    /** Pinned above the rest of the feed, as the dashboard last said. */
    val pinned: Boolean
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
        /** Things shown in the look of where they came from: a Slack message, a Jira ticket. */
        val embeds: List<Embed> = emptyList(),
        /** Apps an agent put on the card. The app can't show them inside a card yet, so each offers a link. */
        val apps: List<CardApp> = emptyList(),
        val reactions: List<Reaction> = emptyList(),
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val tags: List<CardTag> = emptyList(),
        val fields: List<CardField> = emptyList(),
        /** The agent marked it high priority. Shown as a red "High" chip; it doesn't change the order. */
        val high: Boolean = false,
        /** The agent marked it critical: it pages phones that allow it. A solid red "Critical" chip. */
        val critical: Boolean = false,
        /** Pinned above the rest of the feed. */
        override val pinned: Boolean = false,
        /** When it was last changed, as "Edited Monday 3 August 2026 at 14:05"; null when never. Drawn as "edited". */
        val edited: String? = null,
        /** The whole instant behind [time], as "Posted Monday 3 August 2026 at 14:05 · updated …". Shown on hover, read aloud. */
        val timeDetail: String = "",
        /** When something last happened on it (posted, replied to, work state), to mark it when that moves on. */
        val activityAt: Long = 0,
        /** Other cards it points at, drawn small inside it. */
        val refs: List<LinkedCard> = emptyList(),
        /** Blocks drawn on it: a checklist, steps, a diff, a chart, a table, a log or a poll. */
        val blocks: List<CardBlock> = emptyList(),
        /** Secrets the agent attached, drawn as locked chips the owner opens in place. */
        val secrets: List<SharedSecret> = emptyList(),
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
        /** Things shown in the look of where they came from: a Slack message, a Jira ticket. */
        val embeds: List<Embed> = emptyList(),
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
        /** The option a choice question starts picked on: the agent's default. Null picks nothing. */
        val preset: String? = null,
        /** The owner's answer the dashboard refused, to retry or drop. Null when there is none. */
        val unsent: Unsent? = null,
        /** When it was last changed, as "Edited Monday 3 August 2026 at 14:05"; null when never. Drawn as "edited". */
        val edited: String? = null,
        /** The project's words on it, its fields and reactions, and whether it is pinned: as on any card. */
        val tags: List<CardTag> = emptyList(),
        val fields: List<CardField> = emptyList(),
        val reactions: List<Reaction> = emptyList(),
        override val pinned: Boolean = false,
        /** The talk about the question before it is answered: its own thread, oldest first. */
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        /** The agent marked it critical: it pages phones that allow it. A solid red "Critical" chip. */
        val critical: Boolean = false,
        /** For a [QuestionKind.Secret]: the name the value will be stored under. */
        val secretName: String? = null,
        /**
         * For a [QuestionKind.Secret] that asks for several: a box for each, saved together. Empty
         * when it asks for the one [secretName].
         */
        val secretFields: List<SecretAsk> = emptyList(),
        /** Other cards the question points at, drawn small inside it: the reply with the video it asks about. */
        val refs: List<LinkedCard> = emptyList(),
        /**
         * On a team (migration 055): it was answered, by whom and with what. It stays in the feed,
         * settled, among the cards by when it was answered. Null while it waits.
         */
        val answered: Answered? = null,
        /** Blocks drawn on the question: a checklist to tick, a diff to decide on, a poll. */
        val blocks: List<CardBlock> = emptyList(),
    ) : Threaded {
        /** Still waiting on someone: it has controls, and sits under "Waiting on you". */
        val waiting: Boolean get() = answered == null
        val hasPictures: Boolean get() = choices.any { it.image != null }
        override val title: String get() = prompt
        override val starter: Author get() = Author.AgentAuthor(agent)
    }

    /** Who answered a question ("you", or a person's name) and what they said, when it can be said. */
    @Immutable
    data class Answered(val by: String, val said: String? = null, val time: String = "")

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
        /** Things shown in the look of where they came from: a Slack message, a Jira ticket. */
        val embeds: List<Embed> = emptyList(),
        /** Apps an agent put on the card. The app can't show them inside a card yet, so each offers a link. */
        val apps: List<CardApp> = emptyList(),
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val reactions: List<Reaction> = emptyList(),
        /** Written here and still on its way to the dashboard. */
        val sending: Boolean = false,
        /** While [sending]: how much of its files have gone up, 0 to 1; null when none are going up. */
        val uploaded: Float? = null,
        /** Said aloud and transcribed, not typed: drawn with a small mic. */
        val voice: Boolean = false,
        /** While [sending]: a voice note that can still be taken back, until then (epoch millis). */
        val undoUntil: Long? = null,
        /** Questions an agent asked under the post itself, shown at the top of its thread. */
        val questions: List<Question> = emptyList(),
        /** When it was last changed, as "Edited Monday 3 August 2026 at 14:05"; null when never. Drawn as "edited". */
        val edited: String? = null,
        /** The whole instant behind [time], as "Posted Monday 3 August 2026 at 14:05 · updated …". Shown on hover, read aloud. */
        val timeDetail: String = "",
        /** When something last happened on it (posted, replied to, work state), to mark it when that moves on. */
        val activityAt: Long = 0,
        val tags: List<CardTag> = emptyList(),
        val fields: List<CardField> = emptyList(),
        /** Its work state, when someone marked it as work. */
        val status: WorkStatus? = null,
        /** Pinned above the rest of the feed. */
        override val pinned: Boolean = false,
        /** The owner wrote it, sent it a while ago, and no agent has it: nobody is listening. */
        val undelivered: Boolean = false,
        /** Other cards it points at, drawn small inside it. */
        val refs: List<LinkedCard> = emptyList(),
        /** Blocks drawn on it: a checklist, steps, a diff, a chart, a table, a log or a poll. */
        val blocks: List<CardBlock> = emptyList(),
        /** Secrets the agent attached, drawn as locked chips the owner opens in place. */
        val secrets: List<SharedSecret> = emptyList(),
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
    /** New updates waiting while the owner is away from the top: "3 new updates". */
    val newCount: Int = 0,
    /** What search looks through: every project's loaded cards, not only the one on screen. Empty means "what's on screen". */
    val searchIndex: List<SearchEntry> = emptyList(),
    /** Every word the projects on screen use for their cards, for the tag picker. */
    val tags: List<CardTag> = emptyList(),
    val bell: Bell = Bell(),
    /** Who a message can @mention, in the order the @ list shows them. */
    val mentionable: List<Mentionable> = emptyList(),
    /** What an agent marked critical and still wants the owner: waiting questions, and updates not yet read. */
    val critical: List<CriticalNotice> = emptyList(),
    /** Which alerts this device gets. */
    val alerts: AlertSettings = AlertSettings(),
    /** What the owner follows: each project's notification level, and threads followed or unfollowed. */
    val follows: dev.wildware.agents.Follows = dev.wildware.agents.Follows(),
    /** How long the dashboard keeps media. */
    val retention: RetentionSettings = RetentionSettings(),
    /** Pictures posted into the open project, newest first, to pick its logo from. */
    val logoChoices: List<LogoChoice> = emptyList(),
    /** On "All projects", the project a new post goes to unless another is picked: last posted to, or last opened. */
    val postTarget: String? = null,
    /** The devices signed in as the owner, read when the Devices sheet opens. */
    val devices: DeviceList = DeviceList(),
    /** The open agent's OAuth connections, read when its dialog opens. */
    val connections: ConnectionList = ConnectionList(),
    /** Which agents hear each channel or project read so far, by [memberTarget]. */
    val members: Map<String, MemberList> = emptyMap(),
    /** Permissions panels and reach summaries read so far (server spec/15). */
    val access: AccessState = AccessState(),
    /** The dashboard's secrets, without values, read when the Secrets sheet opens. */
    val secrets: SecretList = SecretList(),
    /** The team's feedback and feature requests, read when the Feedback sheet opens. */
    val feedback: FeedbackState = FeedbackState(),
    /** Agents asking to join, oldest first: each waits on the owner to allow or deny it. */
    val joining: List<JoinRequest> = emptyList(),
    /** Projects: named groups of channels, by name. */
    val groups: List<ProjectGroup> = emptyList(),
    /** The people of the team, and you among them. */
    val team: TeamState = TeamState(),
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

/**
 * The team's feedback, newest first, as the Feedback sheet shows it. [recipient] is who it goes to,
 * in the dashboard's words; [sends] is false where the dashboard only saves it. [available] is false
 * on a dashboard from before feedback.
 */
@Immutable
data class FeedbackState(
    val items: List<FeedbackRow> = emptyList(),
    val recipient: String = "",
    val sends: Boolean = false,
    val areas: List<String> = emptyList(),
    val loaded: Boolean = false,
    val available: Boolean = true,
    val problem: String? = null,
)

/** One item, ready to show: [status] as the dashboard has it, [statusLabel] in words. */
@Immutable
data class FeedbackRow(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val area: String?,
    val status: String,
    val statusLabel: String,
    val who: String,
    val time: String,
)

/** The devices signed in as the owner: this one first, then the rest by when they were last used. */
@Immutable
data class DeviceList(
    val devices: List<DeviceRow> = emptyList(),
    val loaded: Boolean = false,
    /** The device being revoked, while the dashboard is asked. */
    val removing: String? = null,
    val problem: String? = null,
)

/** The MCP clients signed in as one agent over OAuth, newest first. */
@Immutable
data class ConnectionList(
    /** Whose these are: the dialog shows them only for that agent. */
    val agentId: String? = null,
    val connections: List<ConnectionRow> = emptyList(),
    val loaded: Boolean = false,
    /** The connection being revoked, while the dashboard is asked. */
    val removing: String? = null,
    val problem: String? = null,
)

/**
 * Which agents hear a channel or a project (a group of channels), as its settings list them. The
 * owner adds or removes each one; a removed agent hears it only when someone @mentions it.
 */
data class MemberList(
    /** Whose these are, as [memberTarget] spells it: the dialog shows them only for that one. */
    val target: String? = null,
    val members: List<MemberRow> = emptyList(),
    val loaded: Boolean = false,
    /** The agent being added or removed, while the dashboard is asked. */
    val busy: String? = null,
    val problem: String? = null,
)

/** One agent in a [MemberList]. [note] says why it hears, or that it was removed. */
data class MemberRow(val agentId: String, val name: String, val hears: Boolean, val note: String)

/** The key a [MemberList] is for: a channel's or a project's slug. */
fun memberTarget(slug: String, group: Boolean): String = (if (group) "group:" else "channel:") + slug

/** One connection, as the agent dialog lists it. */
@Immutable
data class ConnectionRow(
    val id: String,
    /** What the client calls itself: "Cursor". */
    val client: String,
    /** Where it signed in from: "An app on its computer", "claude.ai", "cursor: links". */
    val where: String,
    /** "Last used 5m ago", "Never used". */
    val used: String,
    /** "Added 3 August 2026". */
    val added: String,
)

/**
 * The dashboard's secrets, as the Secrets sheet shows them: never a value. [available] is false on a
 * dashboard from before secrets (the sheet's way in hides); [enabled] false means it has no
 * SECRETS_KEY yet. [busy] names the secret being changed while the dashboard is asked.
 */
@Immutable
data class SecretList(
    val secrets: List<SecretRow> = emptyList(),
    val loaded: Boolean = false,
    val available: Boolean = true,
    val enabled: Boolean = true,
    val busy: String? = null,
    val problem: String? = null,
    /** The audit of the secret open in the sheet, newest first. */
    val audit: List<SecretAuditRow> = emptyList(),
    val auditFor: String? = null,
)

/** One secret: its name, who may use it, and when it was last used. */
@Immutable
data class SecretRow(
    val id: String,
    val name: String,
    val note: String?,
    /** "All agents", "forge, atlas", "No agent yet". */
    val scope: String,
    val allAgents: Boolean,
    val agentIds: List<String>,
    /** "Used 2h ago", "Never used". */
    val used: String,
    /** "Added by you", "From forge". */
    val origin: String,
    val paused: Boolean = false,
)

/** One line of a secret's audit: "Downloaded by forge", "2h ago". */
@Immutable
data class SecretAuditRow(val id: String, val what: String, val time: String, val warning: Boolean = false)

/** One signed-in device, as the Devices sheet lists it. */
@Immutable
data class DeviceRow(
    val id: String,
    val name: String,
    /** "Last used 5m ago", "Never used". */
    val seen: String,
    /** "Added 3 August 2026". */
    val added: String,
    /** The device this app is running on. */
    val current: Boolean = false,
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

/**
 * What an @ in a message box can name. Agents today; [MentionKind.Group] is for @here and
 * @everyone, and [MentionKind.Person] is the hook for other people once there are team accounts.
 */
enum class MentionKind { Agent, Group, Person }

/** One row of the @ list: what it inserts ([name]), and how it shows. */
@Immutable
data class Mentionable(
    val kind: MentionKind,
    val name: String,
    val id: String? = null,
    /** An agent's: online ones first, with a dot. */
    val presence: Presence? = null,
    val hue: Int = 0,
    /** A short line beside the name, as for a group. */
    val hint: String? = null,
)

/**
 * An agent asking to join under [name], from [host] (what it says) and [address] (what the
 * dashboard saw), with the [code] it was given. [askedBy] names the agent that asked for it, when
 * one that is already in did. [busy] while Allow or Deny is on its way; [error] when it was refused.
 */
@Immutable
data class JoinRequest(
    val id: String,
    val name: String,
    val code: String,
    val host: String? = null,
    val cwd: String? = null,
    val address: String? = null,
    val askedBy: String? = null,
    val expiresAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Something marked critical, for the red banner on desktop: who, and what, in one line. */
@Immutable
data class CriticalNotice(val id: String, val agent: String, val text: String, val question: Boolean)

/**
 * The people of the team (a server with migration 055), read when the session starts and when the
 * People sheet opens. [supported] is false on a server from before teams: there is no one but you.
 */
@Immutable
data class TeamState(
    val me: PersonRow? = null,
    val people: List<PersonRow> = emptyList(),
    val invites: List<InviteRow> = emptyList(),
    /** More than one person has been in the team: names show on what people wrote. */
    val team: Boolean = false,
    val supported: Boolean = true,
    val loaded: Boolean = false,
    /** The person or invite being changed, while the dashboard is asked. */
    val busy: String? = null,
    val problem: String? = null,
    /** The invite just made: its link and code, shown once. */
    val created: CreatedInviteRow? = null,
) {
    /** You can invite and remove people. */
    val canManage: Boolean get() = me?.role == "owner" || me?.role == "admin"

    /** You can change roles. */
    val isOwner: Boolean get() = me?.role == "owner"
}

@Immutable
data class PersonRow(
    val id: String,
    val name: String,
    val handle: String,
    /** "owner", "admin" or "member". */
    val role: String,
    val email: String? = null,
    val you: Boolean = false,
    val removed: Boolean = false,
    val founder: Boolean = false,
    /** Used the app in the last five minutes. */
    val online: Boolean = false,
)

@Immutable
data class InviteRow(val id: String, val role: String, val email: String?, val expires: String)

@Immutable
data class CreatedInviteRow(val link: String, val code: String, val role: String, val email: String?)

/**
 * A secret an agent attached to a post or reply: "🔒 NAME — Reveal · Copy". The value is never
 * held here; it is fetched when the owner asks, after the device has checked it is them.
 * [available] is false once it can no longer be opened (deleted, paused, or taken from the agent).
 */
@Immutable
data class SharedSecret(val cardKind: String, val cardId: String, val name: String, val available: Boolean = true)
