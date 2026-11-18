package dev.wildware.agents.app.feed

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.MediaRetention
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
    data class DismissQuestion(val questionId: String) : FeedEvent
    data class React(val targetId: String, val emoji: String) : FeedEvent
    /** Start replying to a line in a thread: the reply box says who it answers. */
    data class Reply(val targetId: String) : FeedEvent
    /** Send a reply into a card's thread, answering [answers] when set. */
    data class SendReply(val threadId: String, val text: String, val answers: String?, val files: List<PendingFile> = emptyList()) : FeedEvent
    /** Write a new post into the project on screen. */
    /** Write a new post: into [project] (a slug) when given, as from "All projects", else the project on screen. */
    data class Post(val text: String, val files: List<PendingFile> = emptyList(), val project: String? = null) : FeedEvent
    data class CopyText(val targetId: String) : FeedEvent
    /** Phones: show this card's or line's words with all of them selected, to pick some out. */
    data class SelectText(val targetId: String) : FeedEvent
    data class CopyLink(val targetId: String) : FeedEvent
    data class Delete(val targetId: String) : FeedEvent
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
    data class MoveProject(val from: Int, val to: Int) : FeedEvent

    /** Make a project. [done] hears null once it is made, or why it wasn't. */
    data class CreateProject(val name: String, val description: String?, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Rename project [slug] and say what it is for. [done] as for [CreateProject]. */
    data class EditProject(val slug: String, val name: String, val description: String?, val done: (problem: String?) -> Unit = {}) : FeedEvent

    /** Rename agent [agentId] everywhere it shows. [done] hears null once renamed, or why not. */
    data class RenameAgent(val agentId: String, val name: String, val done: (String?) -> Unit = {}) : FeedEvent

    /** Make picture [mediaId] project [slug]'s logo, or take the logo off with null. */
    data class SetProjectLogo(val slug: String, val mediaId: String?) : FeedEvent

    /** Show project [slug]'s logo instead of its name above the feed, or beside it. */
    data class SetLogoInsteadOfName(val slug: String, val instead: Boolean) : FeedEvent

    /** Pin project [slug] to the top of the project list, or unpin it. */
    data class PinProject(val slug: String, val pinned: Boolean) : FeedEvent

    /** Archive project [slug], or bring it back. */
    data class ArchiveProject(val slug: String, val archived: Boolean) : FeedEvent

    /** Give project [slug] its own page colour and accent (#rrggbb), or take one off with null. */
    data class SetProjectColours(val slug: String, val background: String?, val accent: String?) : FeedEvent
    /** Show one project's feed, or every project's for null. */
    data class SelectProject(val slug: String?) : FeedEvent
    data object Refresh : FeedEvent
    data object LoadOlder : FeedEvent
    data class RetrySend(val outboxId: String) : FeedEvent
    /** A row under the bell was opened: that one is seen (the screen goes to its card itself). */
    data class OpenNotification(val id: String) : FeedEvent
    /** "Mark all read" under the bell. */
    data object ClearNotifications : FeedEvent
    /** Open the work-state choice on a card (the screen shows it; nothing is sent yet). */
    data class ChooseStatus(val targetId: String, val current: WorkStatus?) : FeedEvent
    /** Set a card's work state, or null for "Not work". */
    data class SetStatus(val targetId: String, val status: WorkStatus?) : FeedEvent
    /** Save which alerts this device gets; null is every alert. */
    data class SetAlertPrefs(val prefs: AlertPrefs?) : FeedEvent
    /** Read how long the dashboard keeps media again, after a read that failed. */
    data object ReadMediaRetention : FeedEvent
    /** Save how long the dashboard keeps media. The screen has already asked when it deletes more. */
    data class SetMediaRetention(val setting: MediaRetention) : FeedEvent
    data class DropUnsent(val outboxId: String) : FeedEvent
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
        Waiting -> item is FeedItem.Question
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
    /** A card that can carry fields: an update or a post, not a reply. */
    val hasDetails: Boolean = false,
)

/** The work-state choice, open on card [id], whose state is [current]. */
@Immutable
data class StatusPick(val id: String, val current: WorkStatus?)

/** "Delete this update?": what a delete would take, asked before it goes. */
@Immutable
data class DeleteAsk(val targetId: String, val question: String)

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

    /** The agent whose card is open, to read about it and rename it: its id. */
    var agentOpen by mutableStateOf<String?>(null)

    /** The new-project or project-settings dialog, while it is open. */
    var projectDialog by mutableStateOf<ProjectDialog?>(null)

    /** The thread open beside the feed (desktop) or over it (phone). */
    var threadId by mutableStateOf(initial.openThread?.id)
    var phoneThreadOpen by mutableStateOf(preview.threadOpen)

    /** Cards whose whole thread the owner asked to see inline, not just its newest replies. */
    val expandedThreads = mutableStateSetOf<String>()
    var replyingTo by mutableStateOf<Reply?>(null)
    var refreshing by mutableStateOf(false)
    var filter by mutableStateOf(preview.filter)
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
    val composerFocus = FocusRequester()

    /** A message box has the cursor (on a phone, the keyboard is up). Back closes it first. */
    var composerOpen by mutableStateOf(false)

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

    /** Shows a search result: scrolls to its card, and opens the thread for a card or reply. */
    fun openResult(hit: SearchEntry) {
        if (state.current.slug != null && state.current.slug != hit.projectSlug) emit(FeedEvent.SelectProject(null))
        val thread = hit.threadId
        reveal(thread ?: hit.id)
        if (thread == null) return
        openThread(thread, phone = isPhone)
        emit(FeedEvent.OpenThread(thread))
    }

    /** The card a link just opened, ringed for a moment. */
    var highlighted by mutableStateOf(preview.highlighted)

    /** A link pointed at something the feed couldn't find. */
    var missingLink by mutableStateOf(preview.missingLink)

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
    var isPhone = false

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

    val thread: Threaded? get() = threadId?.let { state.item(it) as? Threaded }
    val phoneThread: Threaded? get() = if (phoneThreadOpen) thread else null

    /** The owner's own order for the project list, kept on this device. */
    private var projectOrder by mutableStateOf<List<String>?>(null)

    val projects: List<Project>
        get() {
            val order = projectOrder ?: return state.projects
            return state.projects.sortedBy { p -> order.indexOf(p.id).let { if (it < 0) Int.MAX_VALUE else it } }
        }

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
        when (event) {
            // Reacting is a toggle: the same emoji again takes it back, as the server does.
            is FeedEvent.React -> {
                val mineNow = reactionsFor(event.targetId, reactionsOn(event.targetId)).any { it.emoji == event.emoji && it.mine }
                wanted[event.targetId] = wanted[event.targetId].orEmpty() + (event.emoji to !mineNow)
            }
            // Moves count among the active projects; archived ones keep their place at the end.
            is FeedEvent.MoveProject -> {
                val (active, archived) = projects.partition { !it.archived }
                projectOrder = active.map { it.id }.toMutableList().apply { add(event.to, removeAt(event.from)) } + archived.map { it.id }
            }
            is FeedEvent.Answer, is FeedEvent.AnswerText, is FeedEvent.AnswerForm, is FeedEvent.DismissQuestion -> picks.remove(questionIdOf(event))
            // "Reply" on a card opens its thread; on a line in a thread it answers that line.
            is FeedEvent.Reply -> if (state.item(event.targetId) is Threaded) {
                openThread(event.targetId, phone = isPhone)
            } else {
                // A line in a thread: open that card's reply box (it may be inline, not yet open) to answer it.
                val card = thread?.takeIf { t -> t.replies.any { it.id == event.targetId } }
                    ?: state.sections.asSequence().flatMap { it.items }.filterIsInstance<Threaded>()
                        .firstOrNull { t -> t.replies.any { it.id == event.targetId } }
                if (card != null && card.id != threadId) openThread(card.id, phone = isPhone)
                replyingTo = card?.replies?.firstOrNull { it.id == event.targetId }
            }
            is FeedEvent.SendReply -> replyingTo = null
            is FeedEvent.SelectText -> selecting = textOf(event.targetId)?.let { SelectingText(event.targetId, it) }
            is FeedEvent.SetField -> fieldsHere[event.targetId] = fieldsHere[event.targetId].orEmpty() + (event.name to event.value)
            is FeedEvent.Tag -> noteTag(event)
            is FeedEvent.ChooseStatus -> statusPick = StatusPick(event.targetId, event.current)
            is FeedEvent.SetStatus -> statusPick = null
            is FeedEvent.Pin -> pinnedHere[event.targetId] = event.pinned
            is FeedEvent.SelectProject -> threadId = null
            else -> Unit
        }
        menu = null
        onEvent(event)
    }

    private fun questionIdOf(event: FeedEvent): String? = when (event) {
        is FeedEvent.Answer -> event.questionId
        is FeedEvent.AnswerText -> event.questionId
        is FeedEvent.AnswerForm -> event.questionId
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
    data object New : ProjectDialog
    data class Settings(val projectId: String) : ProjectDialog
}
