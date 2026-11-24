package dev.wildware.agents.app.feed

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.MediaRetention
import dev.wildware.agents.OwnerActions
import dev.wildware.agents.RetentionForm
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import dev.wildware.agents.app.design.gesture.EdgeProgress

/**
 * Everything the owner can do on the feed, whichever way they did it (a tap, a swipe, a menu, a
 * key or a screen reader action). The data layer handles these; the screen only raises them.
 */
sealed interface FeedEvent {
    data class Answer(val questionId: String, val choices: List<String>) : FeedEvent
    /** An answer in words, for a question that asks for text. */
    data class AnswerText(val questionId: String, val text: String) : FeedEvent
    /** A form's answer: the agent's [action] the owner chose, with the field's [text]. */
    data class AnswerForm(val questionId: String, val action: String, val text: String) : FeedEvent
    /** A form with fields, filled in: `{ action, values }` as [dev.wildware.agents.FormFill.answer] makes it. */
    data class AnswerFields(val questionId: String, val value: kotlinx.serialization.json.JsonObject) : FeedEvent
    data class DismissQuestion(val questionId: String) : FeedEvent
    /** Let an agent that asked to join in, under [name]. */
    data class AllowAgent(val pairingId: String, val name: String) : FeedEvent
    /** Turn an agent that asked to join away. */
    data class DenyAgent(val pairingId: String) : FeedEvent
    data class React(val targetId: String, val emoji: String) : FeedEvent
    /** Tick or untick one line of a checklist block. */
    data class CheckBlockItem(val cardKind: String, val cardId: String, val blockId: String, val itemId: String, val done: Boolean) : FeedEvent
    /** Vote on a poll block: [options] replaces your vote; none takes it back. */
    data class VoteBlock(val cardKind: String, val cardId: String, val blockId: String, val options: List<String>) : FeedEvent
    /** Start replying to a line in a thread: the reply box says who it answers. */
    data class Reply(val targetId: String) : FeedEvent
    /** Send a reply into a card's thread, answering [answers] when set. */
    /** [voice]: a voice note, marked so agents know it was transcribed, and held a moment for an Undo when sent on release. */
    data class SendReply(
        val threadId: String, val text: String, val answers: String?, val files: List<PendingFile> = emptyList(), val voice: OwnerActions.Voice? = null,
    ) : FeedEvent
    /** Write a new post into the project on screen. */
    /** Write a new post: into [project] (a slug) when given, as from "All projects", else the project on screen. */
    /** [voice]: as [SendReply]'s. */
    data class Post(
        val text: String, val files: List<PendingFile> = emptyList(), val project: String? = null, val tags: List<CardTag> = emptyList(),
        val voice: OwnerActions.Voice? = null,
    ) : FeedEvent
    data class CopyText(val targetId: String) : FeedEvent
    /** Phones: show this card's or line's words with all of them selected, to pick some out. */
    data class SelectText(val targetId: String) : FeedEvent
    data class CopyLink(val targetId: String) : FeedEvent
    data class Delete(val targetId: String) : FeedEvent
    /** Opens the owner's own post or reply to change its words; [text] is what it says now. */
    data class EditMessage(val targetId: String, val text: String) : FeedEvent
    /** Saves the owner's changed words for their own post or reply. */
    data class SaveEdit(val targetId: String, val text: String) : FeedEvent
    /** Put a tag on a card, or take it off. [tag] is its id, or with [create] a new word to make on the way. */
    data class Tag(val targetId: String, val tag: String, val on: Boolean, val create: Boolean = false) : FeedEvent
    /** Open the tag picker for a card. The screen handles it; nothing is sent. */
    data class EditTags(val targetId: String) : FeedEvent
    /** Pin a card above the feed, or unpin it. */
    data class Pin(val targetId: String, val pinned: Boolean) : FeedEvent
    /** Set a field on a card, correct it, or take it off with a null [value]. */
    data class SetField(val targetId: String, val name: String, val value: String?) : FeedEvent
    /** Open a card's details, to read or add fields. The screen handles it; nothing is sent. */
    data class OpenDetails(val targetId: String) : FeedEvent
    data class OpenThread(val cardId: String) : FeedEvent
    /** Mark a card unread again, from its newest reply by someone else. */
    data class MarkUnread(val targetId: String) : FeedEvent
    /** Start a reply quoting a line in a thread. */
    data class Quote(val targetId: String) : FeedEvent
    /**
     * Put channels [slugs] in this order, and every one of them in project [groupId] (null: none):
     * the whole list a drag left in that project. [done] hears null once the dashboard has it, or why not.
     */
    data class ArrangeChannels(val slugs: List<String>, val groupId: String?, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Put projects (groups of channels) [slugs] in this order. [done] as for [ArrangeChannels]. */
    data class ArrangeGroups(val slugs: List<String>, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Mark everything in channel [projectId] read. */
    data class MarkChannelRead(val projectId: String) : FeedEvent

    /** Make a channel, in project [groupId] when set. [done] hears null once it is made, or why it wasn't. */
    data class CreateProject(
        val name: String, val description: String?, val done: (problem: String?) -> Unit = {}, val groupId: String? = null,
    ) : FeedEvent

    /** Rename project [slug] and say what it is for. [done] as for [CreateProject]. */
    data class EditProject(val slug: String, val name: String, val description: String?, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Rename agent [agentId] everywhere it shows. [done] hears null once renamed, or why not. */
    data class RenameAgent(val agentId: String, val name: String, val done: (String?) -> Unit = {}) : FeedEvent

    /** Set agent [agentId]'s role line and description; empty takes one off. [done] hears null once saved, or why not. */
    data class SetAgentProfile(val agentId: String, val profileRole: String, val description: String, val done: (String?) -> Unit = {}) : FeedEvent

    /** Give agent [agentId] the picture [picture] (the picked file, made square and small before it goes), or take it off with null. */
    class SetAgentAvatar(val agentId: String, val picture: ByteArray?, val done: (String?) -> Unit = {}) : FeedEvent

    /** Make picture [mediaId] project [slug]'s logo, or take the logo off with null. */
    data class SetProjectLogo(val slug: String, val mediaId: String?) : FeedEvent

    /** Show project [slug]'s logo instead of its name above the feed, or beside it. */
    data class SetLogoInsteadOfName(val slug: String, val instead: Boolean) : FeedEvent

    /** Pin project [slug] to the top of the project list, or unpin it. */
    data class PinProject(val slug: String, val pinned: Boolean) : FeedEvent

    /** Follow or unfollow the thread a card is in: whether its replies alert the owner. */
    data class FollowThread(val targetId: String, val following: Boolean) : FeedEvent

    /** How much of a channel alerts the owner, on every device; null follows its project's setting. */
    data class SetProjectNotify(val projectId: String, val slug: String, val level: dev.wildware.agents.NotifyLevel?) : FeedEvent

    /** Make a project (a named group of channels). [done] as for [CreateProject]. */
    data class CreateGroup(val name: String, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Rename project [slug]. [done] as for [CreateProject]. */
    data class RenameGroup(val slug: String, val name: String, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Give project [slug] its own colour (#rrggbb), or take it off with null. */
    data class SetGroupAccent(val slug: String, val accent: String?) : FeedEvent

    /** Archive project [slug] (with its channels, in the list), or bring it back. */
    data class ArchiveGroup(val slug: String, val archived: Boolean) : FeedEvent

    /** Delete project [slug]. Its channels stay, in no project. */
    data class DeleteGroup(val slug: String) : FeedEvent

    /** Put channel [slug] in project [groupId], or in none with null. */
    data class MoveChannel(val slug: String, val groupId: String?) : FeedEvent

    /** How much of project [slug] alerts the owner, for its channels without their own; null clears it. */
    data class SetGroupNotify(val groupId: String, val slug: String, val level: dev.wildware.agents.NotifyLevel?) : FeedEvent

    /** Archive project [slug], or bring it back. */
    data class ArchiveProject(val slug: String, val archived: Boolean) : FeedEvent

    /** Give project [slug] its own page colour and accent (#rrggbb), or take one off with null. */
    data class SetProjectColours(val slug: String, val background: String?, val accent: String?) : FeedEvent
    /** Show one project's feed, or every project's for null. */
    data class SelectProject(val slug: String?) : FeedEvent
    data object Refresh : FeedEvent
    data object LoadOlder : FeedEvent

    /**
     * Open card [id], or the card a reply [id] is in, on its own: read by itself with its thread,
     * without paging the feed down to it. [result] gets the card's id, or null when there is none.
     */
    data class OpenAlone(val id: String, val result: (String?) -> Unit) : FeedEvent {
        override fun toString() = "OpenAlone($id)"
    }

    /** The card on its own was put away. */
    data object CloseAlone : FeedEvent
    data class RetrySend(val outboxId: String) : FeedEvent
    /** A row under the bell was opened: that one is seen (the screen goes to its card itself). */
    data class OpenNotification(val id: String) : FeedEvent
    /** "Mark all read" under the bell. */
    data object ClearNotifications : FeedEvent
    /** Open the work-state choice on a card (the screen shows it; nothing is sent yet). */
    data class ChooseStatus(val targetId: String, val current: WorkStatus?) : FeedEvent
    /** Set a card's work state, or null for "Not work". */
    data class SetStatus(val targetId: String, val status: WorkStatus?) : FeedEvent
    /** Something done in the Docs tab (server migration 076). */
    data class Docs(val action: DocsAction) : FeedEvent
    /** The board is on screen for [scope], or (null) put away: the dashboard reads it while it shows. */
    data class ShowBoard(val scope: dev.wildware.agents.BoardScope?) : FeedEvent
    /** Move a card on the board to [status]'s column: the same work-state change as [SetStatus]. */
    data class BoardMove(val kind: String, val id: String, val status: WorkStatus) : FeedEvent
    /** Take a finished card off the board for the whole team, or ([removed] false) put it back. */
    data class BoardRemove(val kind: String, val id: String, val removed: Boolean) : FeedEvent
    /** Show the cards taken off the board too, to put one back. */
    data class BoardShowRemoved(val show: Boolean) : FeedEvent
    /** How many days a finished card stays on the board: 1, 3, 7 or 30, or null for never. */
    data class BoardAutoRemove(val days: Int?) : FeedEvent
    /** Read the next page of one column. */
    data class BoardMore(val status: WorkStatus) : FeedEvent
    /** Keep only the board's cards assigned to me, to nobody, or to one person or agent. */
    data class BoardAssigned(val filter: AssignedFilter) : FeedEvent
    /** "Assign…" on a card in the feed or its thread: the picker opens for it. */
    data class Assign(val targetId: String) : FeedEvent
    /** "Assign…" on a board card, or A on a focused one: the picker opens for it. */
    data class AssignCard(val kind: String, val id: String, val title: String) : FeedEvent
    /** The picker's Save: the card's assignees become exactly these (`member:<id>` / `agent:<id>`). */
    data class SaveAssignees(val principals: List<String>) : FeedEvent
    /** The picker closes without saving. */
    data object CloseAssign : FeedEvent
    /** Save which alerts this device gets; null is every alert. */
    data class SetAlertPrefs(val prefs: AlertPrefs?) : FeedEvent
    /** Read how long the dashboard keeps media again, after a read that failed. */
    data object ReadMediaRetention : FeedEvent
    /** Read the team's plan and its limits, for Settings. */
    data object ReadPlan : FeedEvent
    /** Put away the "Upgrade" prompt a free-plan refusal raised. */
    data object DismissUpgrade : FeedEvent
    /** Read the team's feedback and feature requests, for the Feedback sheet. */
    data object ReadFeedback : FeedEvent
    /** Send feedback or a feature request. [done] hears null once sent, or why not. */
    class SendFeedback(val draft: dev.wildware.agents.FeedbackDraft, val done: (String?) -> Unit = {}) : FeedEvent {
        override fun toString() = "SendFeedback(${draft.kind})"
    }
    /** "Feedback…" on a card's menu: the Feedback sheet opens to write about that card. */
    data class FeedbackAbout(val targetId: String) : FeedEvent
    /** Read the dashboard's secrets, without values, for the Secrets sheet; and one secret's audit when [auditFor] is set. */
    data class ReadSecrets(val auditFor: String? = null) : FeedEvent
    /** Add a secret. [done] hears null once stored, or why not. [toString] leaves the value out. */
    class AddSecret(val name: String, val value: String, val note: String?, val allAgents: Boolean, val agentIds: List<String>, val done: (String?) -> Unit = {}) : FeedEvent {
        override fun toString() = "AddSecret($name)"
    }
    /** Change who may use a secret, pause or resume it, or replace its value. Null leaves a part as it is. */
    class ChangeSecret(
        val secretId: String,
        val allAgents: Boolean? = null,
        val agentIds: List<String>? = null,
        val paused: Boolean? = null,
        val value: String? = null,
        val done: (String?) -> Unit = {},
    ) : FeedEvent {
        override fun toString() = "ChangeSecret($secretId)"
    }
    /** Delete a secret. The sheet has already asked. */
    data class DeleteSecret(val secretId: String, val done: (String?) -> Unit = {}) : FeedEvent
    /**
     * Fetch a secret's value for the owner's eyes, or their clipboard with [copy]. The screen has
     * already confirmed it is them. [result] hears the value, or null and why not.
     */
    data class RevealSecret(val secretId: String, val copy: Boolean, val result: (value: String?, problem: String?) -> Unit) : FeedEvent
    /**
     * Open a secret an agent attached to a post, for the owner's eyes or their clipboard with [copy].
     * The chip has already confirmed it is them. [result] hears the value, or null and why not; a
     * [gone] of true means it can no longer be opened at all.
     */
    data class RevealSharedSecret(
        val secret: SharedSecret,
        val copy: Boolean,
        val result: (value: String?, problem: String?, gone: Boolean) -> Unit,
    ) : FeedEvent {
        override fun toString() = "RevealSharedSecret(${secret.cardKind} ${secret.cardId} ${secret.name}, copy=$copy)"
    }
    /** Answer an agent's request for a secret. Sent at once, never queued. [toString] leaves the value out. */
    class ProvideSecret(val questionId: String, val value: String, val done: (String?) -> Unit = {}) : FeedEvent {
        override fun toString() = "ProvideSecret($questionId)"
    }
    /**
     * Answer an agent's request for several secrets ([FeedItem.Question.secretFields]): every value
     * by name, saved together or not at all. Sent at once, never queued. [toString] leaves the values out.
     */
    class ProvideSecrets(val questionId: String, val values: Map<String, String>, val done: (String?) -> Unit = {}) : FeedEvent {
        override fun toString() = "ProvideSecrets($questionId, ${values.keys.joinToString()})"
    }
    /** Read the devices signed in as the owner, for the Devices sheet. */
    data object ReadDevices : FeedEvent
    /** Revoke a device's key, so it can't read or answer anything more. The sheet has already asked. */
    data class RevokeDevice(val deviceId: String) : FeedEvent
    /** Read the people of the team and the waiting invites, for the People sheet. */
    data object ReadPeople : FeedEvent
    /** Make an invite for [role], optionally for one [email]; its link and code show once. */
    data class InvitePerson(val role: String, val email: String?) : FeedEvent
    /** Forget the invite just made, once its link has been copied. */
    data object DismissInvite : FeedEvent
    data class RevokeInvite(val inviteId: String) : FeedEvent
    /** An owner changes someone else's role. */
    data class SetRole(val personId: String, val role: String) : FeedEvent
    /** Remove someone from the team; with your own id, leave it. The sheet has already asked. */
    data class RemovePerson(val personId: String) : FeedEvent
    /** Change your own name, as the team sees it. */
    data class Rename(val name: String) : FeedEvent
    /** Set your own role line and description; empty takes one off. [done] hears null once saved, or why not. */
    data class SetMyProfile(val profileRole: String, val description: String, val done: (String?) -> Unit = {}) : FeedEvent
    /** Set your own picture to [picture] (the picked file, made square and small before it goes), or take it off with null. */
    class SetMyAvatar(val picture: ByteArray?, val done: (String?) -> Unit = {}) : FeedEvent
    /** Delete your own account, everywhere on this dashboard; [password] when the account has one. The sheet has already asked. */
    data class DeleteAccount(val password: String?) : FeedEvent
    /** Remove an agent: revoke it, so it and every app signed in as it stop working. The dialog has already asked. */
    data class RemoveAgent(val agentId: String, val done: (String?) -> Unit = {}) : FeedEvent
    /** Read the MCP clients signed in as this agent over OAuth, for its dialog. */
    data class ReadConnections(val agentId: String) : FeedEvent

    /** Reads which agents hear a channel ([group] false) or a project, by slug. */
    data class ReadMembers(val slug: String, val group: Boolean) : FeedEvent

    /** Adds an agent to a channel or project ([hears] true) or removes it; the agent is told. */
    data class SetMember(val slug: String, val group: Boolean, val agentId: String, val hears: Boolean) : FeedEvent

    /** Reads who may see a channel ([group] false) or a project, by id, for its Permissions panel. */
    data class ReadAccess(val id: String, val group: Boolean) : FeedEvent
    /** Makes a channel or project private, or opens it to the team again. */
    data class SetAccessPrivate(val id: String, val group: Boolean, val isPrivate: Boolean) : FeedEvent
    /** Gives a person ([kind] "member") or agent a level there; null inherits again. */
    data class SetAccessLevel(val id: String, val group: Boolean, val kind: String, val who: String, val level: String?) : FeedEvent
    /** Reads what a person ([agent] false) or an agent can reach, for their page. */
    data class ReadReach(val id: String, val agent: Boolean) : FeedEvent
    /** Sign one of those clients out. The dialog has already asked. */
    data class RevokeConnection(val agentId: String, val connectionId: String) : FeedEvent
    /** Save how long the dashboard keeps media. The screen has already asked when it deletes more. */
    data class SetMediaRetention(val setting: MediaRetention) : FeedEvent
    data class DropUnsent(val outboxId: String) : FeedEvent

    /** The owner opened the choices for an unsure word in a waiting voice note: it holds until they pick. */
    data class PauseUnsent(val outboxId: String) : FeedEvent

    /** The owner settled an unsure word in a waiting voice note: [choice] in its place, or null to keep it. */
    data class ChooseWord(val outboxId: String, val index: Int, val choice: String?) : FeedEvent

    /** The owner swapped an unsure word in their own sent voice note: [choice] in its place (an edit), or null to keep it. */
    data class ChooseSentWord(val messageId: String, val index: Int, val choice: String?) : FeedEvent
    data object MarkAllRead : FeedEvent
    data object ToggleTheme : FeedEvent
    data object SignOut : FeedEvent
}

/** The chips above the feed. They only narrow what is shown; nothing is fetched. */
enum class FeedFilter(val label: String) {
    All("All"),
    Waiting("Waiting"),
    Updates("Updates"),
    Errors("Errors"),
    FromYou("From you"),
    OpenWork("Open work"),
    Todo("To do"),
    InProgress("In progress"),
    Blocked("Blocked"),
    Done("Done"),
    ;

    fun matches(item: FeedItem): Boolean = when (this) {
        All -> true
        Waiting -> item is FeedItem.Question && item.waiting
        Updates -> item is FeedItem.Update
        Errors -> item is FeedItem.Update && item.level == Level.Error
        FromYou -> item is FeedItem.Post && item.author is Author.Owner
        OpenWork -> item.workStatus?.isOpen == true
        Todo -> item.workStatus == WorkStatus.Todo
        InProgress -> item.workStatus == WorkStatus.InProgress
        Blocked -> item.workStatus == WorkStatus.Blocked
        Done -> item.workStatus == WorkStatus.Done
    }
}

/** Words open for selecting on a phone: whose they are, and the words themselves. */
@Immutable
data class SelectingText(val id: String, val text: String)

/** A card's work state, for the cards that can be work (updates and posts). */
val FeedItem.workStatus: WorkStatus?
    get() = when (this) {
        is FeedItem.Update -> status
        is FeedItem.Post -> status
        else -> null
    }

/** The thing a context menu is open on. */
@Immutable
data class MenuTarget(
    val id: String,
    val author: String,
    val text: String,
    val own: Boolean,
    /** Where the pointer was, in the target's own coordinates, for menus that open at the cursor. */
    val at: Offset = Offset.Zero,
    /** A card that can wear tags: an update or a post, not a reply. */
    val taggable: Boolean = false,
    /** It can be marked as work (an update or a post). */
    val work: Boolean = false,
    /** Its work state now, when it is work. */
    val status: WorkStatus? = null,
    /** Whether it is pinned now; null for what can't be pinned, such as a reply. */
    val pinned: Boolean? = null,
    /** Whether the owner may delete it: their own lines, and any update. */
    val deletable: Boolean = own,
    /** Whether the owner may change its words: only their own posts and replies, once they are up. */
    val editable: Boolean = own && !id.startsWith(dev.wildware.agents.DraftPrefix),
    /** A card that can carry fields: an update or a post, not a reply. */
    val hasDetails: Boolean = false,
    /** Whether the owner follows the card's thread; null for what has no thread of its own, such as a reply. */
    val following: Boolean? = null,
    /** A card with a reply from someone else, which can be marked unread again. */
    val markUnread: Boolean = false,
    /** A line in a thread, which a reply can quote. */
    val quotable: Boolean = false,
    /** The originals of its pictures and videos, for the menu's Save; none for placeholders. */
    val saves: List<FileAttachment> = emptyList(),
)

/** The work-state choice, open on card [id], whose state is [current]. */
@Immutable
data class StatusPick(val id: String, val current: WorkStatus?)

/** "Delete this update?": what a delete would take, asked before it goes. */
@Immutable
data class DeleteAsk(val targetId: String, val question: String)

/** The owner's own post or reply open to change its words: [text] is what it says now. */
data class EditAsk(val targetId: String, val text: String)

/** The emoji a double-tap adds. */
const val QuickReaction = "👍"
val MenuReactions = listOf("👍", "✅", "👀", "❤️", "🎉", "😂")

/**
 * Screens open in a given state, for screenshots and previews. A real launch leaves it empty.
 */
@Immutable
data class ScreenPreview(
    /** How far the first question is swiped, as a share of the commit distance; + is right. */
    val questionSwipe: Float = 0f,
    /** A reply to show swiped right, part-way to "Reply". */
    val replySwipe: String? = null,
    /** The reply (by id) showing its desktop Reply button, as if the pointer were over it. */
    val replyHover: String? = null,
    /** The command bar, open with this typed in it. */
    val command: String? = null,
    /** Files already attached in the feed's composer. */
    val composerFiles: List<PendingFile> = emptyList(),
    /** Words already in the feed's composer. */
    val composerText: String = "",
    /** The feed's composer has the cursor, as after a tap on it. */
    val composerOpen: Boolean = false,
    /** The feed's composer mic is held down. */
    val composerListening: Boolean = false,
    /** Options already picked on picture or choice questions, by question id. */
    val picked: Map<String, Set<String>> = emptyMap(),
    /** Cards drawn as having just moved on, wearing the fading ring. */
    val bumped: Set<String> = emptySet(),
    val menu: MenuTarget? = null,
    val viewer: Gallery? = null,
    val drawerOpen: Boolean = false,
    /** Phone only: the open thread is shown full screen. */
    val threadOpen: Boolean = false,
    /** A card wearing the ring a link gives it. */
    val highlighted: String? = null,
    /** The "couldn't find what that link pointed at" notice. */
    val missingLink: Boolean = false,
    /** The video (by address) playing in its card. */
    val inlineVideo: String? = null,
    /** The video filling the window. */
    val playing: VideoSource? = null,
    /** The notification list under the bell, open. */
    val bellOpen: Boolean = false,
    /** The work-state choice, open. */
    val statusPick: StatusPick? = null,
    /** The feed's filter chip, already chosen. */
    val filter: FeedFilter = FeedFilter.All,
    /** The alert settings, open. */
    val alertsOpen: Boolean = false,
    /** The "Keep media" settings, open, with this draft in them (null starts from what is saved). */
    val retentionOpen: Boolean = false,
    val retentionDraft: RetentionForm? = null,
    /** The Devices sheet, open. */
    val devicesOpen: Boolean = false,
    /** The People sheet, open. */
    val peopleOpen: Boolean = false,
    /** The "Connect an agent" sheet, open. */
    val connectOpen: Boolean = false,
    /** An agent's dialog, open, by agent id. */
    val agentOpen: String? = null,
    /** A person's profile, open, by their id. */
    val personOpen: String? = null,
    /** The Secrets sheet, open, on this secret (null: the list) or adding one. */
    val secretsOpen: Boolean = false,
    val secretOpen: String? = null,
    val addingSecret: Boolean = false,
    /** A secret shown in the clear, for a screenshot of what Reveal looks like. */
    val revealedSecret: String? = null,
    /** The Feedback sheet, open: on the team's list, or writing [feedbackCompose]. */
    val feedbackOpen: Boolean = false,
    val feedbackCompose: FeedbackCompose? = null,
    /** The board in the main pane instead of the feed, widened to project [boardGroup] (an id) when set. */
    val boardOpen: Boolean = false,
    val boardGroup: String? = null,
)

/** The Feedback sheet's form, open: about [card] (shown as [cardLabel]) when it came from a card's menu. */
@Immutable
data class FeedbackCompose(
    val card: dev.wildware.agents.FeedbackCard? = null,
    val cardLabel: String? = null,
    val area: String? = null,
)

/**
 * What the screen is showing on top of the feed, and what the owner has done that only this
 * screen knows about. It outlives each new [state] from the engine.
 */
@Stable
class FeedUi(preview: ScreenPreview, private val source: State<FeedState>, var onEvent: (FeedEvent) -> Unit) {
    /**
     * Told whether the owner is away from the top of the feed. A view's place, not something the owner
     * did, so it is kept out of [onEvent].
     */
    var onAwayFromTop: (Boolean) -> Unit = {}
    /** The engine's latest. Each reader is redrawn when it changes, and nothing else is. */
    val state: FeedState get() = source.value
    private val initial = source.value
    var menu by mutableStateOf(preview.menu)
    var viewer by mutableStateOf(preview.viewer)

    /** A video playing full screen in the app (phones). */
    var playing by mutableStateOf(preview.playing)

    /** The video playing in its card, by address: one at a time. */
    var inlineVideo by mutableStateOf(preview.inlineVideo)

    /** A short line at the bottom of the screen, gone by itself. */
    var toast by mutableStateOf<String?>(null)

    /** A button the line [toast] offers, such as "Show in folder"; shown only with the line it was made for. */
    var toastAction by mutableStateOf<ToastAction?>(null)

    /** The agent whose card is open, to read about it and rename it: its id. */
    var agentOpen by mutableStateOf(preview.agentOpen)

    /** The person whose profile is open: their id. */
    var personOpen by mutableStateOf(preview.personOpen)

    /** The new-project or project-settings dialog, while it is open. */
    var projectDialog by mutableStateOf<ProjectDialog?>(null)

    /** The thread open beside the feed (desktop) or over it (phone). */
    var threadId by mutableStateOf(initial.openThread?.id)
    var phoneThreadOpen by mutableStateOf(preview.threadOpen)

    /** Cards whose whole thread the owner asked to see inline, not just its newest replies. */
    val expandedThreads = mutableStateSetOf<String>()
    var replyingTo by mutableStateOf<Reply?>(null)

    /** What a Reply on a line, or calling it off, asks of that thread's reply box; the box clears it once done. */
    var mentionAsk by mutableStateOf<MentionAsk?>(null)

    /**
     * Calls off answering [replyingTo] in thread [threadId]: the reply goes to the thread as a whole,
     * and a box holding only the mention a Reply put there is emptied again.
     */
    fun stopReplying(threadId: String) {
        replyingTo = null
        mentionAsk = MentionAsk(threadId, mention = null, focus = false)
    }

    /**
     * How a reply to [author] names them: an agent by the name the @ list gives it, so the dashboard
     * hands it to that agent alone, wherever it works. The owner's own line names nobody.
     */
    private fun mentionFor(author: Author): String? = when (author) {
        is Author.Owner -> null
        // Another person on the team: their @handle reaches them (migration 055).
        is Author.Person -> author.handle
        is Author.AgentAuthor -> state.mentionable.firstOrNull { it.kind == MentionKind.Agent && it.id == author.agent.id }?.name
            ?: author.agent.name
    }
    var refreshing by mutableStateOf(false)
    var filter by mutableStateOf(preview.filter)

    /** The board shows in the main pane instead of the feed. */
    var boardOpen by mutableStateOf(preview.boardOpen)

    /** A project (group id) the board was widened to; picking a channel or All in the sidebar clears it. */
    var boardGroup by mutableStateOf(preview.boardGroup)

    /** The board card the keyboard is on. */
    var boardFocus by mutableStateOf<String?>(null)

    /** A board card's menu, open: open it, move it, take it off the board. */
    var boardMenu by mutableStateOf<BoardCard?>(null)

    /** Where the board looks: the project chosen on it, else the sidebar's channel, else everything. */
    val boardScope: dev.wildware.agents.BoardScope
        get() = boardScopeFor(state.current.slug, boardGroup?.let { id -> state.groups.firstOrNull { it.id == id } })

    /** Feed to board, or back. */
    fun toggleBoard() {
        menu = null
        boardMenu = null
        docsOpen = false
        boardOpen = !boardOpen
    }

    /** Shows the board for project [groupId] (null: the sidebar's selection). */
    fun showBoard(groupId: String? = null) {
        menu = null
        boardGroup = groupId
        docsOpen = false
        boardOpen = true
    }

    /** The channel's Docs tab in the main pane instead of the feed (server migration 076). */
    var docsOpen by mutableStateOf(false)

    /** Shows the Docs tab, or (false) goes back to the feed. */
    fun showDocs(on: Boolean = true) {
        menu = null
        boardMenu = null
        boardOpen = false
        docsOpen = on && state.current.slug != null
    }

    /**
     * A [[link]] was tapped, in a doc or a message: opens the doc titled [title] in [channel] (the
     * open channel when null) in the Docs tab, making it when nobody has written it yet.
     */
    fun openDocLink(channel: String?, title: String) {
        val slug = channel ?: state.current.slug
        if (slug == null) {
            toast = "Open the channel to follow a link to “$title”."
            return
        }
        threadId = null
        phoneThreadOpen = false
        aloneId = null
        // The Docs tab opens it once it shows that channel.
        pendingDocLink = slug to title
        if (state.current.slug != slug) emit(FeedEvent.SelectProject(slug))
        menu = null
        boardMenu = null
        boardOpen = false
        docsOpen = true
    }

    /** A [[link]] waiting for the Docs tab to show its channel: (channel, title). */
    var pendingDocLink by mutableStateOf<Pair<String, String>?>(null)

    /**
     * Opens a board card's thread on its own ([openAlone]): read by itself with its thread, without
     * paging the feed down to it. The board steps aside for it; the feed stays where it was.
     */
    internal fun openBoardCard(card: BoardCard) {
        boardMenu = null
        boardOpen = false
        openAlone(card.id)
    }
    var showArchived by mutableStateOf(false)
    var commandOpen by mutableStateOf(preview.command != null)

    /** Phones: the words open for selecting, from a card menu's "Select text". */
    var selecting by mutableStateOf<SelectingText?>(null)

    /**
     * A card's or line's words as plain text, the way "Copy text" and "Select text" give them: an
     * update's title and then its body, a post's or reply's body, a question and its detail.
     */
    fun textOf(id: String): String? {
        fun plain(markdown: String) = dev.wildware.agents.PlainText.fromMarkdown(markdown)
        for (item in state.sections.asSequence().flatMap { it.items }) {
            if (item.id == id) return when (item) {
                is FeedItem.Update -> listOf(item.title, plain(item.body)).filter { it.isNotBlank() }.joinToString("\n\n")
                is FeedItem.Post -> plain(item.body)
                is FeedItem.Question -> listOf(item.prompt, plain(item.body)).filter { it.isNotBlank() }.joinToString("\n\n")
            }
            if (item is Threaded) item.replies.firstOrNull { it.id == id }?.let { return plain(it.body) }
        }
        return null
    }

    /** The notification list under the bell. */
    var bellOpen by mutableStateOf(preview.bellOpen)
    /** The work-state choice, when it is open. */
    var statusPick by mutableStateOf(preview.statusPick)
    /** The alert settings for this device. */
    var alertsOpen by mutableStateOf(preview.alertsOpen)
    /** How long the dashboard keeps media. */
    var retentionOpen by mutableStateOf(preview.retentionOpen)

    /** The Devices sheet: every device signed in as the owner, to revoke a lost one. */
    var devicesOpen by mutableStateOf(preview.devicesOpen)
    /** The Secrets sheet: what the dashboard keeps for agents, and who has used it. */
    var secretsOpen by mutableStateOf(preview.secretsOpen)

    /** The Feedback sheet: what the team has sent the people who build the dashboard. */
    var feedbackOpen by mutableStateOf(preview.feedbackOpen)
    /** The Feedback sheet's form, when it is being written; null shows the list. */
    var feedbackCompose by mutableStateOf(preview.feedbackCompose)

    /** Opens the Feedback sheet, on its form ([compose]) or its list. */
    fun openFeedback(compose: FeedbackCompose? = null) {
        menu = null
        alertsOpen = false
        shortcutsOpen = false
        feedbackCompose = compose
        feedbackOpen = true
    }

    /** The card [targetId] as the server names it, and a few words to show for it. */
    internal fun feedbackCardFor(targetId: String): FeedbackCompose {
        for (item in state.sections.asSequence().flatMap { it.items }) {
            val words: (String) -> String = { text -> dev.wildware.agents.PlainText.fromMarkdown(text).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80) }
            if (item.id == targetId) {
                return when (item) {
                    is FeedItem.Update -> FeedbackCompose(dev.wildware.agents.FeedbackCard("update", item.id), item.title.ifBlank { words(item.body) })
                    is FeedItem.Post -> FeedbackCompose(dev.wildware.agents.FeedbackCard("message", item.id), words(item.body))
                    is FeedItem.Question -> FeedbackCompose(dev.wildware.agents.FeedbackCard("request", item.id), item.prompt.take(80))
                }
            }
            if (item is Threaded) item.replies.firstOrNull { it.id == targetId }?.let { return FeedbackCompose(dev.wildware.agents.FeedbackCard("message", it.id), words(it.body)) }
        }
        return FeedbackCompose()
    }

    /** The People sheet: who is in the team, inviting, roles and removing. */
    var peopleOpen by mutableStateOf(preview.peopleOpen)

    /** The "Connect an agent" sheet: a prompt to paste into an agent so it joins this dashboard. */
    var connectOpen by mutableStateOf(preview.connectOpen)
    val composerFocus = FocusRequester()

    /** A message box has the cursor (on a phone, the keyboard is up). Back closes it first. */
    var composerOpen by mutableStateOf(false)

    /**
     * The touch that last went down on a message box (its pointer and when: pointer ids repeat from
     * one touch to the next), so the feed around the box doesn't take it as a tap to close it.
     */
    var composerTouch: Pair<androidx.compose.ui.input.pointer.PointerId, Long>? = null

    /** Asks the open message box to close; each ask counts up, so asking twice closes twice. */
    var closeRequests by mutableIntStateOf(0)
        private set

    /** Closes the open message box without sending, keeping what was written. */
    fun closeComposer() {
        closeRequests++
    }

    /**
     * What was written and not sent, by box: each project's and each thread's. A box that leaves the
     * screen (a thread closed, another project opened) finds its words here when it comes back.
     */
    val drafts = mutableMapOf<String, String>()

    /** Words and files shared in from another app, waiting for the next project composer on screen. */
    var incoming by mutableStateOf<SharedDraft?>(null)

    /** What search looks through: every project's loaded cards when the engine gives them, else what is on screen. */
    val searchEntries: List<SearchEntry>
        get() = state.searchIndex.ifEmpty { searchEntriesOf(state.sections) }

    /** Puts the cursor in the composer, for "New post". */
    /** On "All projects", the project a new post goes to, by slug; null for the first one. */
    var postTo by mutableStateOf<String?>(null)

    /** Where a post written on "All projects" goes: the one picked, else the first project still in use. */
    val postProject: Project?
        get() {
            val active = projects.filter { !it.archived && it.slug != null }
            return active.firstOrNull { it.slug == postTo }
                ?: active.firstOrNull { it.slug == state.postTarget }
                ?: active.firstOrNull()
        }

    fun focusComposer() {
        runCatching { composerFocus.requestFocus() }
    }

    /**
     * Shows a search result. One in the feed on screen is scrolled to, its thread open for a card or
     * reply. One the feed on screen doesn't hold (another channel's, or older than it has read) opens
     * on its own instead, the feed left where it is.
     */
    fun openResult(hit: SearchEntry) {
        val thread = hit.threadId
        if (thread != null && cardFor(thread) == null) return openAlone(hit.id)
        if (state.current.slug != null && state.current.slug != hit.projectSlug) emit(FeedEvent.SelectProject(null))
        reveal(thread ?: hit.id)
        if (thread == null) return
        openThread(thread, phone = isPhone)
        emit(FeedEvent.OpenThread(thread))
    }

    /** The card a link just opened, ringed for a moment. */
    var highlighted by mutableStateOf(preview.highlighted)

    /** A link pointed at something the feed couldn't find. */
    var missingLink by mutableStateOf(preview.missingLink)

    /** The reply a linked card opened its thread at: brought into view and ringed for a moment. */
    var focusedReply by mutableStateOf<String?>(null)

    /** The card that shows [id]: the card itself, or the card a reply or a question asked in its thread is under. */
    fun cardFor(id: String): FeedItem? {
        state.item(id)?.let { return it }
        return state.sections.asSequence().flatMap { it.items }.firstOrNull { item ->
            (item is Threaded && item.replies.any { r -> r.id == id || r.questions.any { it.id == id } }) ||
                (item is FeedItem.Post && item.questions.any { it.id == id })
        }
    }

    /** A card the feed should scroll to. A new [serial] each time, so asking twice scrolls twice. */
    data class Reveal(val id: String, val serial: Long)

    /** The card waiting to be scrolled into view, once it is loaded. */
    var revealing by mutableStateOf<Reveal?>(null)
    private var revealSerial = 0L

    /** Scrolls the feed to card [id], waiting for it to load if it isn't yet. */
    fun reveal(id: String) {
        revealing = Reveal(id, ++revealSerial)
    }

    /**
     * The card open on its own, by id: one the feed on screen hadn't paged to, read by itself. The
     * feed stays where it was under it. On a phone it shows in the thread screen; wider, over the feed.
     */
    var aloneId by mutableStateOf<String?>(null)
    private var aloneAsked = 0L

    /** The card open on its own: the feed's copy once the feed has it, else the one read by itself. */
    val alone: Threaded? get() = aloneId?.let { cardOrLone(it) as? Threaded }

    /** Card [id] in the feed on screen, or the card open on its own. */
    fun cardOrLone(id: String): FeedItem? = state.item(id) ?: state.lone?.takeIf { it.id == id }

    /**
     * Opens card [id], or the card the reply [id] is in, on its own: read by itself with its
     * thread, rather than paging the feed down to it. The feed doesn't move. Not found, the feed says so.
     */
    fun openAlone(id: String, unfold: (String) -> Unit = {}) {
        missingLink = false
        val asked = ++aloneAsked
        emit(FeedEvent.OpenAlone(id) { cardId ->
            if (asked != aloneAsked) return@OpenAlone
            if (cardId == null) {
                missingLink = true
                return@OpenAlone
            }
            unfold(cardId)
            expandedThreads.add(cardId)
            if (cardId != id) focusedReply = id
            // The thread first, then the card on its own: whatever watches the phone's thread screen
            // never sees the card without it.
            openThread(cardId, phone = isPhone)
            aloneId = cardId
            emit(FeedEvent.OpenThread(cardId))
        })
    }

    /** Puts the card on its own away: back to the feed, just as it was. */
    fun closeAlone() {
        val id = aloneId ?: return
        aloneAsked++
        aloneId = null
        if (threadId == id) threadId = null
        emit(FeedEvent.CloseAlone)
    }

    /** "Show in feed": the card on its own, once the feed has it too, shown there instead. */
    fun showAloneInFeed() {
        val id = aloneId ?: return
        if (state.item(id) == null) return
        aloneId = null
        emit(FeedEvent.CloseAlone)
        if (isPhone) phoneThreadOpen = false
        reveal(id)
        highlighted = id
    }

    /**
     * The card the owner just replied on, and where it sat among the cards ([from]) when they sent.
     * Their reply moves it up the feed once it lands, so the feed follows it there.
     */
    data class Follow(val id: String, val from: Int, val serial: Long)

    /** The card the feed follows to its new place, until it moves up or the wait runs out. */
    var following by mutableStateOf<Follow?>(null)

    /** Follows card [id] once it moves: what sending a reply on it asks. */
    private fun follow(id: String) {
        val from = cardIndex(sections, id) ?: return
        following = Follow(id, from, ++revealSerial)
    }
    var isPhone = false

    /** Where the owner has been, for Back and Forward: channels and threads (History.kt). */
    internal val history = dev.wildware.agents.NavHistory(Place(initial.current.slug, null))

    /** Whether there is somewhere to go Back or Forward to; kept up with [history]. */
    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set

    /** The channel last asked for, until the engine shows it: the place the owner is going. */
    internal var askedProject by mutableStateOf<AskedProject?>(null)

    /** In a browser tab, its own history, which Back and Forward then go through; elsewhere null. */
    internal var browser: BrowserHistory? = null

    /** The "Keyboard shortcuts" sheet. */
    var shortcutsOpen by mutableStateOf(false)

    /** The card the keyboard is on (J and K move it), drawn ringed; null for none. */
    var cursor by mutableStateOf<String?>(null)

    /**
     * The sections as the chips narrow them, empty sections dropped. Worked out once per new state
     * or filter rather than on every read: the list, the header and the search all ask for it.
     */
    val sections: List<FeedSection>
        get() {
            val from = state.sections
            val by = filter
            if (from === sectionsFrom && by == sectionsFilter && tagFilter == sectionsTag && tagsHere.toMap() == sectionsTags) return filteredSections
            val tag = tagFilter
            val filtered = when {
                by == FeedFilter.All && tag == null && from.all { it.items.isNotEmpty() } -> from
                else -> from.map { section ->
                    section.copy(items = section.items.filter { by.matches(it) && (tag == null || tagsFor(it.id, tagOf(it)).any { t -> t.name == tag }) })
                }.filter { it.items.isNotEmpty() }
            }
            sectionsFrom = from
            sectionsFilter = by
            sectionsTag = tagFilter
            sectionsTags = tagsHere.toMap()
            filteredSections = filtered
            return filtered
        }
    private var sectionsFrom: List<FeedSection>? = null
    private var sectionsFilter: FeedFilter? = null
    private var sectionsTag: String? = null
    private var sectionsTags: Map<String, Map<String, Pair<CardTag, Boolean>>> = emptyMap()
    private var filteredSections: List<FeedSection> = emptyList()
    val drawer = EdgeProgress(if (preview.drawerOpen) 1f else 0f)

    /** 0 while the phone thread is in place; rises towards 1 as it is swiped away. */
    val threadBack = EdgeProgress(0f)

    val thread: Threaded? get() = threadId?.let { cardOrLone(it) as? Threaded }
    val phoneThread: Threaded? get() = if (phoneThreadOpen) thread else null

    /** Drags sent to the dashboard and not back yet, laid over what it says so a drop lands at once. */
    val arranging = PendingArrangement()

    /** The channels, in the order the dashboard keeps, with drops on their way already in place. */
    val projects: List<Project> get() = arranging.channels(state.projects)

    /** The projects (groups of channels), likewise. */
    val groups: List<ProjectGroup> get() = arranging.groups(state.groups)

    /** A sidebar menu, open on a channel, a project or an agent. */
    var sideMenu by mutableStateOf<SideMenu?>(null)

    /** The activity time last seen per card, to tell when one moves on. Plain data: nothing draws from it. */
    private val seenActivity = HashMap<String, Long>()

    /**
     * Each card's bump: 0, or a new number each time it moves on, marked for a moment ([BumpMillis]).
     * One state per card, not one map, so a bump wakes only its own card.
     */
    private val bumps = HashMap<String, androidx.compose.runtime.MutableIntState>()
    private var bumpCount = 0

    fun bumpOf(id: String): androidx.compose.runtime.MutableIntState = bumps.getOrPut(id) { androidx.compose.runtime.mutableIntStateOf(0) }

    init {
        preview.bumped.forEach { bumpOf(it).intValue = ++bumpCount }
    }

    /**
     * Notes where every card is up to, and marks those whose activity moved forward since last
     * seen, as the website does, so a reader looking elsewhere can tell which one moved. A card
     * seen for the first time isn't a bump: it is arriving, which has its own motion.
     */
    fun noteActivity(sections: List<FeedSection>) {
        sections.forEach { section ->
            section.items.forEach { item ->
                val at = when (item) {
                    is FeedItem.Update -> item.activityAt
                    is FeedItem.Post -> item.activityAt
                    is FeedItem.Question -> return@forEach
                }
                if (at <= 0) return@forEach
                val before = seenActivity.put(item.id, at)
                if (before != null && at > before) bumpOf(item.id).intValue = ++bumpCount
            }
        }
    }

    /** What the owner has picked on choice questions but not sent yet, by question id. */
    val picks = mutableStateMapOf<String, Set<String>>().apply { putAll(preview.picked) }

    /** The owner's picks on [question]; until they touch it, the agent's default ([FeedItem.Question.preset]). */
    fun picksFor(question: FeedItem.Question): Set<String> = picks[question.id] ?: setOfNotNull(question.preset)

    /** Pick or unpick an option: any number for multi-choice, one at a time otherwise. */
    fun toggle(question: FeedItem.Question, label: String) {
        val now = picksFor(question)
        picks[question.id] = when {
            label in now -> now - label
            question.kind == QuestionKind.MultiChoice -> now + label
            else -> setOf(label)
        }
    }

    /**
     * The owner's own reactions as they last set them here, by target id then emoji: true for on.
     * Drawn straight away, before the server echoes them back.
     */
    private val wanted = mutableStateMapOf<String, Map<String, Boolean>>()

    fun openThread(id: String, phone: Boolean) {
        threadId = id
        if (phone) phoneThreadOpen = true
        replyingTo = null
    }

    /** The card whose tag picker is open. */
    var tagging by mutableStateOf<String?>(null)

    /**
     * Tags picked for the next post, before it is written, by project id: the tag picker opens on
     * [NewPostTags] to choose them. They go with the post and are cleared once it is sent.
     */
    val postTags = mutableStateMapOf<String, List<CardTag>>()

    /** The tags picked for the next post in [projectId], handed over and forgotten: the post is going. */
    fun takePostTags(projectId: String): List<CardTag> = postTags.remove(projectId).orEmpty()

    /**
     * Only cards wearing this tag are shown, by name; null for all. Setting one takes the feed to
     * its top, where the header says which tag it is showing and how to show everything again.
     */
    var tagFilter: String?
        get() = tagFilterState
        set(value) {
            if (value != null && value != tagFilterState) toTopAsked++
            tagFilterState = value
        }
    private var tagFilterState by mutableStateOf<String?>(null)

    /** Goes up by one each time the feed should go back to its top. */
    var toTopAsked by mutableStateOf(0)
        private set

    /** Takes the feed back to its top. */
    fun askForTop() {
        toTopAsked++
    }

    /** The guided tour: off until it starts by itself on a first sign-in, or from Settings. */
    val tour = Tour()

    /** Tags the owner put on or took off here, by card then tag key, drawn before the server answers. */
    private val tagsHere = mutableStateMapOf<String, Map<String, Pair<CardTag, Boolean>>>()

    /** New words made here, until the server's list has them. */
    private val madeHere = mutableStateListOf<CardTag>()

    /** The tags a card shows: the server's, with the owner's latest changes here on top. */
    fun tagsFor(id: String, base: List<CardTag>): List<CardTag> {
        val changes = tagsHere[id] ?: return base
        var tags = base
        changes.values.forEach { (tag, on) ->
            val has = tags.any { it.id == tag.id || it.name.equals(tag.name, ignoreCase = true) }
            tags = when {
                on && !has -> tags + tag
                !on && has -> tags.filter { it.id != tag.id && !it.name.equals(tag.name, ignoreCase = true) }
                else -> tags
            }
        }
        return tags
    }

    /** The words a card in [projectId] can wear, in the project's order, with any made here last. */
    fun vocabularyFor(projectId: String?): List<CardTag> {
        val known = state.tags.filter { it.projectId == null || it.projectId == projectId }
        return known + madeHere.filter { m -> m.projectId == projectId && known.none { it.name.equals(m.name, ignoreCase = true) } }
    }

    private fun tagOf(item: FeedItem): List<CardTag> = when (item) {
        is FeedItem.Update -> item.tags
        is FeedItem.Post -> item.tags
        is FeedItem.Question -> item.tags
    }

    /** A tag put on or taken off the post not yet written, in [projectId]. Nothing is sent until it is. */
    fun tagNewPost(projectId: String, tag: String, on: Boolean, create: Boolean) {
        val picked = if (create) {
            CardTag("new:${tag.lowercase()}", tag, null, projectId).also { made -> if (madeHere.none { it.id == made.id }) madeHere += made }
        } else {
            vocabularyFor(projectId).firstOrNull { it.id == tag || it.name.equals(tag, ignoreCase = true) } ?: return
        }
        val now = postTags[projectId].orEmpty().filterNot { it.id == picked.id || it.name.equals(picked.name, ignoreCase = true) }
        postTags[projectId] = if (on) now + picked else now
    }

    private fun noteTag(event: FeedEvent.Tag) {
        val project = state.item(event.targetId)?.let(::projectOf)
        val tag = if (event.create) {
            CardTag("new:${event.tag.lowercase()}", event.tag, null, project).also { made ->
                if (madeHere.none { it.id == made.id }) madeHere += made
            }
        } else {
            vocabularyFor(project).firstOrNull { it.id == event.tag } ?: CardTag(event.tag, event.tag)
        }
        tagsHere[event.targetId] = tagsHere[event.targetId].orEmpty() + (tag.name.lowercase() to (tag to event.on))
    }

    private fun projectOf(item: FeedItem): String = when (item) {
        is FeedItem.Update -> item.project.id
        is FeedItem.Post -> item.project.id
        is FeedItem.Question -> item.project.id
    }
    /** Cards pinned or unpinned here, drawn that way before the server says so. */
    private val pinnedHere = mutableStateMapOf<String, Boolean>()

    /** Whether card [id] shows as pinned: the owner's latest tap here, else what the server says. */
    fun isPinned(id: String, server: Boolean): Boolean = pinnedHere[id] ?: server

    /** Whether the owner follows this card's thread: their own choice, or else what its project's level says. */
    fun following(item: Threaded): Boolean = follows.following(threadKind(item), item.id, threadProject(item))

    /** What the owner follows. Read through here, a card is redrawn only when that changes, not on every new state. */
    val follows: dev.wildware.agents.Follows by androidx.compose.runtime.derivedStateOf { source.value.follows }

    /** Cards whose details are open. */
    val detailsOpen = androidx.compose.runtime.mutableStateSetOf<String>()

    /** Fields set or removed here, by card then name (null for removed), drawn before the server answers. */
    private val fieldsHere = mutableStateMapOf<String, Map<String, String?>>()

    /** The fields a card shows: the server's, with the owner's changes here on top. */
    fun fieldsFor(id: String, base: List<CardField>): List<CardField> {
        val changes = fieldsHere[id] ?: return base
        val kept = base.mapNotNull { f -> if (f.name in changes) changes[f.name]?.let { CardField(f.name, it) } else f }
        val added = changes.filter { (name, value) -> value != null && base.none { it.name == name } }.map { CardField(it.key, it.value!!) }
        return kept + added
    }

    /** The owner's own line open to edit; null when none is. */
    var editing by mutableStateOf<EditAsk?>(null)

    /** The owner's own line open in a message box to change (Up in an empty box), so the feed marks it. */
    var boxEditing by mutableStateOf<String?>(null)

    /** Saves the edit open in [editing]: unchanged or empty words change nothing. */
    fun saveEdit(text: String) {
        val ask = editing ?: return
        editing = null
        val words = text.trim()
        if (words.isNotEmpty() && words != ask.text.trim()) onEvent(FeedEvent.SaveEdit(ask.targetId, words))
    }

    /** The delete waiting for the owner to say yes. Nothing is deleted until they do. */
    var deleting by mutableStateOf<DeleteAsk?>(null)

    /** The owner said yes: delete what [deleting] asked about. */
    fun confirmDelete() {
        val ask = deleting ?: return
        deleting = null
        onEvent(FeedEvent.Delete(ask.targetId))
    }

    /** What deleting [id] would take, in a question. */
    private fun askFor(id: String): DeleteAsk {
        val items = state.sections.flatMap { it.items } + listOfNotNull(state.openThread)
        val question = when (val item = items.firstOrNull { it.id == id }) {
            is FeedItem.Update -> "Delete this update?"
            is FeedItem.Post -> when (val n = item.replies.size) {
                0 -> "Delete this post?"
                1 -> "Delete this post and its reply?"
                else -> "Delete this post and its $n replies?"
            }
            else -> "Delete this reply?"
        }
        return DeleteAsk(id, question)
    }

    /** Long bodies the owner opened in full, by card id. They stay open while the feed is up. */
    private val unfolded = mutableStateMapOf<String, Boolean>()

    fun isUnfolded(key: String): Boolean = unfolded[key] == true

    fun toggleFold(key: String) {
        unfolded[key] = !isUnfolded(key)
    }

    fun emit(event: FeedEvent) {
        if (event is FeedEvent.OpenDetails) {
            detailsOpen += event.targetId
            menu = null
            return
        }
        if (event is FeedEvent.FeedbackAbout) {
            openFeedback(feedbackCardFor(event.targetId))
            return
        }
        if (event is FeedEvent.EditTags) {
            tagging = event.targetId
            menu = null
            return
        }
        // Deleting can't be undone, so every way of asking for it (menu, sheet, screen reader) asks first.
        if (event is FeedEvent.Delete) {
            deleting = askFor(event.targetId)
            menu = null
            return
        }
        if (event is FeedEvent.EditMessage) {
            editing = EditAsk(event.targetId, event.text)
            menu = null
            return
        }
        when (event) {
            // Reacting is a toggle: the same emoji again takes it back, as the server does.
            is FeedEvent.React -> {
                val mineNow = reactionsFor(event.targetId, reactionsOn(event.targetId)).any { it.emoji == event.emoji && it.mine }
                wanted[event.targetId] = wanted[event.targetId].orEmpty() + (event.emoji to !mineNow)
            }
            is FeedEvent.Answer, is FeedEvent.AnswerText, is FeedEvent.AnswerForm, is FeedEvent.AnswerFields, is FeedEvent.DismissQuestion -> picks.remove(questionIdOf(event))
            // "Reply" on a card opens its thread; on a line in a thread it answers that line.
            is FeedEvent.Reply -> if (state.item(event.targetId) is Threaded) {
                openThread(event.targetId, phone = isPhone)
            } else {
                // A line in a thread: open that card's reply box (it may be inline, not yet open) to answer it.
                val card = thread?.takeIf { t -> t.replies.any { it.id == event.targetId } }
                    ?: state.sections.asSequence().flatMap { it.items }.filterIsInstance<Threaded>()
                        .firstOrNull { t -> t.replies.any { it.id == event.targetId } }
                if (card != null && card.id != threadId) openThread(card.id, phone = isPhone)
                val line = card?.replies?.firstOrNull { it.id == event.targetId }
                replyingTo = line
                // The box names who wrote it, and takes the cursor, ready to write the answer.
                if (card != null && line != null) mentionAsk = MentionAsk(card.id, mentionFor(line.author), focus = true)
            }
            // A line in a thread, quoted at the top of its thread's reply box.
            is FeedEvent.Quote -> {
                val card = thread?.takeIf { t -> t.replies.any { it.id == event.targetId } }
                    ?: state.sections.asSequence().flatMap { it.items }.filterIsInstance<Threaded>()
                        .firstOrNull { t -> t.replies.any { it.id == event.targetId } }
                val words = textOf(event.targetId)
                if (card != null && words != null) {
                    if (card.id != threadId) openThread(card.id, phone = isPhone)
                    mentionAsk = MentionAsk(card.id, mention = null, focus = true, quote = words)
                }
            }
            is FeedEvent.SendReply -> {
                replyingTo = null
                // The reply makes this the newest card, so it jumps up the feed: the owner goes with it.
                follow(event.threadId)
            }
            is FeedEvent.SelectText -> selecting = textOf(event.targetId)?.let { SelectingText(event.targetId, it) }
            is FeedEvent.SetField -> fieldsHere[event.targetId] = fieldsHere[event.targetId].orEmpty() + (event.name to event.value)
            is FeedEvent.Tag -> noteTag(event)
            is FeedEvent.ChooseStatus -> statusPick = StatusPick(event.targetId, event.current)
            is FeedEvent.SetStatus -> statusPick = null
            is FeedEvent.Pin -> pinnedHere[event.targetId] = event.pinned
            is FeedEvent.SelectProject -> {
                threadId = null
                aloneId = null
                cursor = null
                boardGroup = null
                boardFocus = null
                // Docs are a channel's: on All channels the feed comes back.
                if (event.slug == null) docsOpen = false
                askedProject = AskedProject(event.slug)
            }
            else -> Unit
        }
        menu = null
        onEvent(event)
    }

    private fun questionIdOf(event: FeedEvent): String? = when (event) {
        is FeedEvent.Answer -> event.questionId
        is FeedEvent.AnswerText -> event.questionId
        is FeedEvent.AnswerForm -> event.questionId
        is FeedEvent.AnswerFields -> event.questionId
        is FeedEvent.DismissQuestion -> event.questionId
        else -> null
    }

    /** The reactions to draw: the item's own, with the owner's latest taps on top until the server catches up. */
    fun reactionsFor(id: String, base: List<Reaction>): List<Reaction> =
        wanted[id].orEmpty().entries.fold(base) { list, (emoji, on) -> list.withMine(emoji, on) }

    /** The reactions on a card or a reply, as the server last sent them. */
    private fun reactionsOn(id: String): List<Reaction> {
        state.sections.forEach { section ->
            section.items.forEach { item ->
                if (item.id == id) return reactionsOf(item)
                if (item is Threaded) item.replies.firstOrNull { it.id == id }?.let { return it.reactions }
            }
        }
        state.openThread?.replies?.firstOrNull { it.id == id }?.let { return it.reactions }
        return emptyList()
    }

    private fun reactionsOf(item: FeedItem): List<Reaction> = when (item) {
        is FeedItem.Update -> item.reactions
        is FeedItem.Post -> item.reactions
        is FeedItem.Question -> item.reactions
    }
}

val LocalFeedUi = staticCompositionLocalOf<FeedUi?> { null }

/** Whether the current layout is the phone one; menus become sheets there. */
val LocalIsPhone = staticCompositionLocalOf { false }

internal val LocalPreview = staticCompositionLocalOf { ScreenPreview() }

/** [this] with the owner's [emoji] reaction on or off. */
internal fun List<Reaction>.withMine(emoji: String, on: Boolean): List<Reaction> {
    val existing = firstOrNull { it.emoji == emoji }
    return when {
        existing == null -> if (on) this + Reaction(emoji, 1, mine = true, spoken = emoji) else this
        existing.mine == on -> this
        on -> map { if (it.emoji == emoji) it.copy(count = it.count + 1, mine = true) else it }
        existing.count <= 1 -> filter { it.emoji != emoji }
        else -> map { if (it.emoji == emoji) it.copy(count = it.count - 1, mine = false) else it }
    }
}

/** Which project dialog is open: making a new one, or the settings of one already there. */
sealed interface ProjectDialog {
    /** A new channel. */
    data object New : ProjectDialog
    /** A channel's settings. */
    data class Settings(val projectId: String) : ProjectDialog
    /** A new project (a group of channels). */
    data object NewGroup : ProjectDialog
    /** A project's settings. */
    data class GroupSettings(val groupId: String) : ProjectDialog
    /** Who may read, post in and manage a channel (server spec/15). */
    data class Permissions(val projectId: String) : ProjectDialog
    /** Who may read, post in and manage a project and the channels in it. */
    data class GroupPermissions(val groupId: String) : ProjectDialog
    /** A new channel, made in project [groupId]: the project menu's "New channel here". */
    data class NewChannelIn(val groupId: String) : ProjectDialog
    /** "Move to project…": which project channel [channelId] goes in. */
    data class MoveChannel(val channelId: String) : ProjectDialog
    /** A channel's or ([group]) a project's new name. */
    data class Rename(val id: String, val group: Boolean) : ProjectDialog
    /** "Delete project?", asked before it goes; its channels stay, in no project. */
    data class DeleteGroup(val groupId: String) : ProjectDialog
}

/** The kind of thread a card heads, as the dashboard names it. */
fun threadKind(item: Threaded): String = when (item) {
    is FeedItem.Update -> "update"
    is FeedItem.Question -> "request"
    is FeedItem.Post -> "message"
}

/** The project a card's thread is in. */
fun threadProject(item: Threaded): String? = when (item) {
    is FeedItem.Update -> item.project.id
    is FeedItem.Question -> item.project.id
    is FeedItem.Post -> item.project.id
}

/** Where card [id] sits among every card in [sections], counting no headings; null when it isn't there. */
internal fun cardIndex(sections: List<FeedSection>, id: String): Int? {
    var i = 0
    for (section in sections) for (item in section.items) {
        if (item.id == id) return i
        i++
    }
    return null
}
