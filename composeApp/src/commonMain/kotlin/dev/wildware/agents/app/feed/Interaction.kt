package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
    data class Post(val text: String, val files: List<PendingFile> = emptyList()) : FeedEvent
    data class CopyText(val targetId: String) : FeedEvent
    data class CopyLink(val targetId: String) : FeedEvent
    data class Delete(val targetId: String) : FeedEvent
    data class OpenThread(val cardId: String) : FeedEvent
    data class MoveProject(val from: Int, val to: Int) : FeedEvent
    /** Show one project's feed, or every project's for null. */
    data class SelectProject(val slug: String?) : FeedEvent
    data object Refresh : FeedEvent
    data object LoadOlder : FeedEvent
    data class RetrySend(val outboxId: String) : FeedEvent
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
    ;

    fun matches(item: FeedItem): Boolean = when (this) {
        All -> true
        Waiting -> item is FeedItem.Question
        Updates -> item is FeedItem.Update
        Errors -> item is FeedItem.Update && item.level == Level.Error
        FromYou -> item is FeedItem.Post && item.author is Author.Owner
    }
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
)

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
    /** Options already picked on picture or choice questions, by question id. */
    val picked: Map<String, Set<String>> = emptyMap(),
    val menu: MenuTarget? = null,
    val viewer: Gallery? = null,
    val drawerOpen: Boolean = false,
    /** Phone only: the open thread is shown full screen. */
    val threadOpen: Boolean = false,
)

/**
 * What the screen is showing on top of the feed, and what the owner has done that only this
 * screen knows about. It outlives each new [state] from the engine.
 */
@Stable
class FeedUi(preview: ScreenPreview, private val source: State<FeedState>, var onEvent: (FeedEvent) -> Unit) {
    /** The engine's latest. Each reader is redrawn when it changes, and nothing else is. */
    val state: FeedState get() = source.value
    private val initial = source.value
    var menu by mutableStateOf(preview.menu)
    var viewer by mutableStateOf(preview.viewer)

    /** The thread open beside the feed (desktop) or over it (phone). */
    var threadId by mutableStateOf(initial.openThread?.id)
    var phoneThreadOpen by mutableStateOf(preview.threadOpen)

    /** Cards whose whole thread the owner asked to see inline, not just its newest replies. */
    val expandedThreads = mutableStateSetOf<String>()
    var replyingTo by mutableStateOf<Reply?>(null)
    var refreshing by mutableStateOf(false)
    var filter by mutableStateOf(FeedFilter.All)
    var showArchived by mutableStateOf(false)
    var commandOpen by mutableStateOf(preview.command != null)
    val composerFocus = FocusRequester()

    /** Words and files shared in from another app, waiting for the next project composer on screen. */
    var incoming by mutableStateOf<SharedDraft?>(null)

    /** What search looks through: every project's loaded cards when the engine gives them, else what is on screen. */
    val searchEntries: List<SearchEntry>
        get() = state.searchIndex.ifEmpty { searchEntriesOf(state.sections) }

    /** Puts the cursor in the composer, for "New post". */
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
            if (from === sectionsFrom && by == sectionsFilter) return filteredSections
            val filtered = when {
                by == FeedFilter.All && from.all { it.items.isNotEmpty() } -> from
                else -> from.map { it.copy(items = it.items.filter(by::matches)) }.filter { it.items.isNotEmpty() }
            }
            sectionsFrom = from
            sectionsFilter = by
            filteredSections = filtered
            return filtered
        }
    private var sectionsFrom: List<FeedSection>? = null
    private var sectionsFilter: FeedFilter? = null
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

    /** What the owner has picked on choice questions but not sent yet, by question id. */
    val picks = mutableStateMapOf<String, Set<String>>().apply { putAll(preview.picked) }

    fun picksFor(questionId: String): Set<String> = picks[questionId] ?: emptySet()

    /** Pick or unpick an option: any number for multi-choice, one at a time otherwise. */
    fun toggle(question: FeedItem.Question, label: String) {
        val now = picksFor(question.id)
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

    fun emit(event: FeedEvent) {
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
        is FeedItem.Question -> emptyList()
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
