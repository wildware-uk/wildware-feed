package dev.wildware.agents.app.feed

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.SemanticsPropertyKey
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.wildware.agents.app.design.AgentTheme
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A request to show one project, and maybe one card in it. [project] null means every project.
 * [serial] tells two taps on the same notification apart, so the second one opens it again.
 */
data class OpenCard(val project: String?, val cardId: String?, val serial: Long = 0) {
    companion object {
        /**
         * Reads a dashboard page path the way notifications, widgets and search carry it:
         * `/projects/garden-game?focus=abc` is card `abc` in Garden Game; `/` is every project.
         */
        fun fromPath(path: String): OpenCard {
            val route = path.substringBefore('#').substringBefore('?')
            val query = path.substringBefore('#').substringAfter('?', "")
            val parts = route.split('/').filter { it.isNotEmpty() }
            val project = if (parts.size >= 2 && parts[0] == "projects") parts[1] else null
            val focus = query.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "focus" }
                ?.get(1)
                ?.takeIf { it.isNotEmpty() }
            return OpenCard(project, focus)
        }
    }
}

/**
 * How things outside the feed (a tapped notification, a widget, a search result, Siri) ask it to
 * show a card. A request made before the feed is on screen, such as the one that launched the app,
 * waits until it is, then is used once.
 */
class AppNavigator {
    private val pending = MutableStateFlow<OpenCard?>(null)
    private var serial = 0L

    val requests: StateFlow<OpenCard?> = pending.asStateFlow()

    fun open(project: String?, cardId: String?) {
        pending.value = OpenCard(project, cardId, ++serial)
    }

    /** Opens a dashboard page path, such as `/projects/garden-game?focus=abc`. */
    fun openPath(path: String) {
        pending.value = OpenCard.fromPath(path).copy(serial = ++serial)
    }

    /** Marks [request] as handled, unless a newer one has already replaced it. */
    internal fun handled(request: OpenCard) {
        pending.compareAndSet(request, null)
    }

    private val pendingShare = MutableStateFlow<SharedDraft?>(null)

    /** Something shared into the app, waiting for the feed. */
    val shares: StateFlow<SharedDraft?> = pendingShare.asStateFlow()

    /**
     * Puts [text] and [files] shared from another app (Android's share sheet) into a project's
     * composer, to check and send. With every project showing, the projects open to pick one.
     */
    fun share(text: String, files: List<PendingFile>) {
        pendingShare.value = SharedDraft(text, files, ++serial)
    }

    internal fun handled(draft: SharedDraft) {
        pendingShare.compareAndSet(draft, null)
    }

    private val pendingInvite = MutableStateFlow<InviteLink?>(null)

    /** An invite link the app was opened with, waiting for the join screen. */
    val invites: StateFlow<InviteLink?> = pendingInvite.asStateFlow()

    /**
     * Opens the join screen with [link] filled in: an `agents://invite?server=…&token=…` link, a
     * `…/join/<code>` one, or the code itself. Anything else is ignored. Returns whether it was one.
     */
    fun openInvite(link: String): Boolean {
        val invite = InviteLink.of(link) ?: return false
        pendingInvite.value = invite.copy(serial = ++serial)
        return true
    }

    internal fun handled(invite: InviteLink) {
        pendingInvite.compareAndSet(invite, null)
    }
}

/**
 * An invite to join a team, as a link opened the app. [link] is what the join screen's box gets;
 * [server] is the address it names, when it names one. [serial] tells two opens of one link apart.
 */
data class InviteLink(val link: String, val server: String?, val serial: Long = 0) {
    companion object {
        private val Code = Regex("^[A-Za-z0-9_-]{43}$")
        private val JoinPath = Regex("/join/([A-Za-z0-9_-]{43})(?:[/?#]|$)")

        /** The invite code in a web address's fragment, `#invite=<code>`, as an invite's page links to the web app. */
        fun inFragment(hash: String): String? =
            hash.removePrefix("#").split('&').map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "invite" }?.get(1)?.takeIf { Code.matches(it) }

        /** [text] as an invite: the app's `agents://invite` link, a `/join/<code>` link, or a bare code. */
        fun of(text: String): InviteLink? {
            val trimmed = text.trim()
            val server = dev.wildware.agents.SignIn.serverOfInvite(trimmed)?.let(dev.wildware.agents.SignIn::normalise)
            return when {
                // Shown as the page's own link, `<server>/join/<code>`: the same invite, in words people read.
                trimmed.startsWith("agents://invite", ignoreCase = true) -> {
                    val token = Regex("[?&]token=([A-Za-z0-9_-]{43})(?:&|$)").find(trimmed)?.groupValues?.get(1) ?: return null
                    InviteLink(if (server != null) "$server/join/$token" else token, server)
                }
                trimmed.startsWith("agents:", ignoreCase = true) -> null
                Code.matches(trimmed) || JoinPath.containsMatchIn(trimmed) -> InviteLink(trimmed, server)
                else -> null
            }
        }
    }
}

/** Words and files shared in from another app. [serial] tells two shares of the same thing apart. */
class SharedDraft(val text: String, val files: List<PendingFile>, val serial: Long)

/** How long to wait for a card to load before leaving the project open without it. */
private const val CardWaitMillis = 10_000L

/** Follows the navigator's requests on this feed: picks the project, then opens the card's thread. */
@Composable
internal fun FollowNavigation(ui: FeedUi, navigator: AppNavigator?) {
    if (navigator == null) return
    val motion = AgentTheme.motion
    LaunchedEffect(ui, navigator) {
        navigator.shares.filterNotNull().collect { draft ->
            navigator.handled(draft)
            ui.menu = null
            ui.viewer = null
            ui.commandOpen = false
            ui.phoneThreadOpen = false
            ui.incoming = draft
            // Every project is showing, so there is no composer yet: offer the projects to pick one.
            if (ui.state.current.slug == null && ui.isPhone) ui.drawer.settle(true, motion.normal())
        }
    }
    // A folded card a link points at opens, as the website unfolds its focus.
    val folds = LocalCardFolds.current
    LaunchedEffect(ui, navigator) {
        navigator.requests.filterNotNull().collect { open ->
            navigator.handled(open)
            ui.goTo(open) { id -> if (folds.isFolded(id)) folds.set(id, false) }
        }
    }
    // The ring says "this one" for a moment, then the card is just a card again.
    val ringed = ui.highlighted
    LaunchedEffect(ringed) {
        if (ringed == null) return@LaunchedEffect
        delay(RingMillis)
        if (ui.highlighted == ringed) ui.highlighted = null
    }
}

/**
 * Shows [open]'s project, then its card with the thread open, as the website follows `?focus=`. The id
 * may be a reply or a question asked in a thread: its card is shown. A card not loaded yet is looked
 * for in older pages, [MaxOlderPages] at most. Found, it is opened if folded ([unfold]), scrolled to
 * and ringed for a moment; not found, the feed says so rather than seeming to do nothing.
 */
internal suspend fun FeedUi.goTo(open: OpenCard, unfold: (String) -> Unit = {}) {
    menu = null
    viewer = null
    bellOpen = false
    missingLink = false
    drawer.snap(false)
    if (open.project != state.current.slug) emit(FeedEvent.SelectProject(open.project))
    val id = open.cardId ?: return
    var pages = 0
    val item = withTimeoutOrNull(CardWaitMillis) {
        // Each look is a change in the feed: a project arriving, a page read. Not found yet, it reads
        // one older page; it gives up when there is nothing older, or once the last allowed page is in.
        snapshotFlow { Look(cardFor(id), state.current.slug == open.project && state.loaded, state.hasMore, state.loading, state.sections.sumOf { it.items.size }) }
            .first { look ->
                when {
                    look.card != null -> true
                    !look.here || look.loading -> false
                    !look.more || pages >= MaxOlderPages -> true
                    else -> {
                        pages++
                        emit(FeedEvent.LoadOlder)
                        false
                    }
                }
            }
            .card
    }
    if (item == null) {
        missingLink = true
        return
    }
    unfold(item.id)
    reveal(item.id)
    highlighted = item.id
    if (item is Threaded) {
        openThread(item.id, phone = isPhone)
        emit(FeedEvent.OpenThread(item.id))
    }
}

/**
 * Opens the card [link] points at, as a link to it would ([goTo]): its thread, at it. A reply opens
 * its card's thread with every reply showing, and is brought into view there and ringed. The
 * project on screen stays unless it is another one than the card's; every project stays every project.
 */
internal suspend fun FeedUi.openLinked(link: LinkedCard, unfold: (String) -> Unit = {}) {
    if (link.removed) return
    val project = if (state.current.slug == null) null else link.projectSlug ?: state.current.slug
    goTo(OpenCard(project, link.id), unfold)
    val card = cardFor(link.id) ?: return
    if (card.id == link.id) return
    expandedThreads.add(card.id)
    focusedReply = link.id
}

/**
 * One look for a linked card: found yet, the right feed showing, whether there is more to read, and
 * how many cards are in (so a page that arrives without the card still counts as a new look).
 */
private data class Look(val card: FeedItem?, val here: Boolean, val more: Boolean, val loading: Boolean, val cards: Int)

/** How many older pages a link reads looking for its card, as the website does. */
internal const val MaxOlderPages = 5

/** How long a linked card wears its ring. */
internal const val RingMillis = 4_000L

/** Set on a card a link just opened, while it wears its ring. Read by tests and accessibility checks. */
val Highlighted = SemanticsPropertyKey<Boolean>("Highlighted")

/** Whether the card being drawn is the one a link just opened. */
internal val LocalLit = staticCompositionLocalOf { false }
