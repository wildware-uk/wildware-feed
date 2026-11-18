package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable

/*
 * What the feed screen draws. These are view shapes only, shaped for the screen. The engine's
 * models are mapped onto them in `engine/Mapping.kt`; [FakeFeed] fills them for screenshots.
 */

enum class Level(val label: String) { Info("Info"), Success("Success"), Warning("Warning"), Error("Error") }

enum class WorkStatus(val label: String) {
    Todo("To do"), InProgress("In progress"), Blocked("Blocked"), Done("Done"), Cancelled("Cancelled"),
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
)

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

@Immutable
data class Attachment(
    val title: String,
    val altText: String,
    val aspect: Float,
    /** The stand-in picture when there is no [url]: previews and screenshots. */
    val scene: Scene = Scene.Dusk,
    /** Where the real image is. Loaded with the device key. */
    val url: String? = null,
)

/** Pictures to look through full size, starting at [index]. */
@Immutable
data class Gallery(val images: List<Attachment>, val index: Int = 0)

/** Which stand-in picture to paint until real images load. */
enum class Scene { Dusk, Dawn, Night }

@Immutable
data class Reply(
    val id: String,
    val author: Author,
    val time: String,
    val body: String,
    val reactions: List<Reaction> = emptyList(),
    val unread: Boolean = false,
)

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

    /** A kind this app can't answer yet (a form): it says to answer on the website. */
    Unsupported,
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
        val reactions: List<Reaction> = emptyList(),
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val tags: List<String> = emptyList(),
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
    ) : FeedItem {
        val hasPictures: Boolean get() = choices.any { it.image != null }
    }

    /** Something written straight into the feed, by the owner or by an agent. */
    @Immutable
    data class Post(
        override val id: String,
        override val time: String,
        val author: Author,
        val project: Project,
        val body: String,
        val acks: Acks = Acks(),
        override val replies: List<Reply> = emptyList(),
        override val unreadReplies: Int = 0,
        val reactions: List<Reaction> = emptyList(),
    ) : Threaded {
        override val title: String get() = body.lineSequence().first().take(120)
        override val starter: Author get() = author
    }
}

@Immutable
data class FeedSection(val label: String, val items: List<FeedItem>)

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
) {
    fun item(id: String): FeedItem? = sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.id == id } }
}

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
