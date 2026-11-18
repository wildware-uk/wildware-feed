package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable

/*
 * What the feed screen draws. These are view shapes only, shaped for the screen; the data layer in
 * :shared will map its own models onto them.
 */

enum class Level(val label: String) { Info("Info"), Success("Success"), Warning("Warning"), Error("Error") }

enum class WorkStatus(val label: String) { Todo("To do"), InProgress("In progress"), Blocked("Blocked"), Done("Done") }

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
data class Attachment(val title: String, val altText: String, val aspect: Float)

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
data class Choice(val label: String, val primary: Boolean = false)

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
        val title: String,
        val body: String,
        val status: WorkStatus? = null,
        val attachment: Attachment? = null,
        val reactions: List<Reaction> = emptyList(),
        val acks: Acks = Acks(),
        val replies: List<Reply> = emptyList(),
        val unreadReplies: Int = 0,
        val tags: List<String> = emptyList(),
    ) : FeedItem

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
    ) : FeedItem

    @Immutable
    data class OwnerPost(
        override val id: String,
        override val time: String,
        val author: Author.Owner,
        val project: Project,
        val body: String,
        val acks: Acks = Acks(),
        val replies: List<Reply> = emptyList(),
    ) : FeedItem
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
    val openThread: FeedItem.Update?,
    val totalUnread: Int,
)
