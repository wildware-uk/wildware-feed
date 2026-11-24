package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.Typing
import kotlin.time.TimeSource

/** Whether shortcuts here use ⌘ and ⌥ (a Mac, an iPad, a browser on a Mac) rather than Ctrl and Alt. */
internal expect val shortcutsUseCommand: Boolean

/**
 * In a browser tab, ties the feed's places to the browser's own history (see [BrowserHistory]), so
 * its Back and Forward walk them; elsewhere nothing.
 */
@Composable
internal expect fun FollowBrowserHistory(ui: FeedUi)

/** One shortcut as the sheet lists it: the [keys] to press, and what it [does]. */
data class Shortcut(val keys: String, val does: String)

/** Shortcuts under one heading. */
data class ShortcutGroup(val title: String, val shortcuts: List<Shortcut>)

/**
 * Every shortcut, in words for this platform: ⌘ on a Mac ([mac]), Ctrl elsewhere. In a browser
 * ([web]) Back and Forward are the browser's own, so its buttons and the mouse's work too.
 */
fun keyboardShortcuts(mac: Boolean, web: Boolean): List<ShortcutGroup> {
    val cmd = if (mac) "⌘" else "Ctrl+"
    val alt = if (mac) "⌥" else "Alt+"
    val shift = if (mac) "⇧" else "Shift+"
    val back = if (mac) "⌘[" else "Alt+←"
    val forward = if (mac) "⌘]" else "Alt+→"
    val buttons = if (web) "the browser's Back, the mouse's Back button" else "the mouse's Back button"
    return listOf(
        ShortcutGroup(
            "Getting around",
            listOf(
                Shortcut("$back, $buttons", "Back"),
                Shortcut("$forward, ${buttons.replace("Back", "Forward")}", "Forward"),
                Shortcut("$alt↑  $alt↓", "Previous or next channel"),
                Shortcut("$alt$shift↑  $alt$shift↓", "Previous or next channel with something unread"),
                Shortcut("${cmd}1 … ${cmd}9", "Channel 1 to 9, as the sidebar lists them"),
                Shortcut("$shift" + "F10  or  Menu", "Menu for the channel, project or agent in focus"),
                Shortcut("$cmd$shift↑  $cmd$shift↓", "Move the channel or project in focus up or down"),
                Shortcut("${cmd}B", "Board or feed"),
            ) + (if (web) emptyList() else listOf(Shortcut("$cmd${shift}1 … $cmd${shift}9", "Team 1 to 9, as the team switcher lists them"))) + listOf(
                Shortcut("G then H", "All channels"),
                Shortcut("G then W", "Waiting on you"),
                Shortcut("G then S", "Alerts on this device"),
                Shortcut("Home", "Back to the top of the feed"),
            ),
        ),
        ShortcutGroup(
            "Search",
            listOf(
                Shortcut("${cmd}K", "Search and commands"),
                Shortcut("${cmd}F  or  /", "Search"),
                Shortcut("?  or  $cmd/", "These shortcuts"),
            ),
        ),
        ShortcutGroup(
            "The feed",
            listOf(
                Shortcut("J  or  ↓", "Next card"),
                Shortcut("K  or  ↑", "Previous card"),
                Shortcut("Enter  or  O", "Open the card's thread"),
                Shortcut("R", "Reply to the card"),
                Shortcut(if (web) "N" else "N  or  ${cmd}N", "New post in this channel"),
                Shortcut("Esc", "Close the thread, sheet or menu"),
                Shortcut(if (mac) "F5  or  ⌘R" else "F5  or  Ctrl+R", "Refresh"),
            ),
        ),
        ShortcutGroup(
            "The board",
            listOf(
                Shortcut("↑  ↓  ←  →", "Move between cards"),
                Shortcut("$cmd$shift←  $cmd$shift→", "Move the card a column"),
                Shortcut("Enter", "Open the card's thread"),
                Shortcut("Delete", "Remove a Done card from the board"),
                Shortcut("A", "Assign the card to people or agents"),
            ),
        ),
        ShortcutGroup(
            "Writing",
            listOf(
                Shortcut(if (mac) "⌘↵" else "Ctrl+Enter", "Send"),
                Shortcut("Enter", "New line"),
                Shortcut("Esc", "Put the box away, keeping what you wrote"),
                Shortcut("↑", "Edit your last message, from an empty box. Enter saves it, Esc stops"),
            ),
        ),
    )
}

/** Whether a sheet, menu, picker or dialog is over the feed. */
internal val FeedUi.overlayOpen: Boolean
    get() = tour.on || viewer != null || playing != null || commandOpen || bellOpen || alertsOpen || retentionOpen ||
        devicesOpen || secretsOpen || feedbackOpen || peopleOpen || connectOpen || statusPick != null || state.assign != null || menu != null || selecting != null || boardMenu != null ||
        tagging != null || deleting != null || editing != null || projectDialog != null || agentOpen != null || shortcutsOpen

/** Closes whatever is on top of the feed; false when nothing was. */
internal fun FeedUi.closeTopOverlay(): Boolean {
    when {
        tour.on -> tour.skip()
        viewer != null -> viewer = null
        playing != null -> playing = null
        deleting != null -> deleting = null
        editing != null -> editing = null
        commandOpen -> commandOpen = false
        shortcutsOpen -> shortcutsOpen = false
        bellOpen -> bellOpen = false
        statusPick != null -> statusPick = null
        state.assign != null -> emit(FeedEvent.CloseAssign)
        boardMenu != null -> boardMenu = null
        tagging != null -> tagging = null
        alertsOpen -> alertsOpen = false
        retentionOpen -> retentionOpen = false
        devicesOpen -> devicesOpen = false
        secretsOpen -> secretsOpen = false
        feedbackOpen -> feedbackOpen = false
        peopleOpen -> peopleOpen = false
        connectOpen -> connectOpen = false
        projectDialog != null -> projectDialog = null
        agentOpen != null -> agentOpen = null
        selecting != null -> selecting = null
        menu != null -> menu = null
        else -> return false
    }
    return true
}

/** The channels in the order the sidebar lists them: each project's, then those in none. */
internal val FeedUi.channelOrder: List<Project>
    get() = channelSections.let { s -> s.groups.flatMap { it.second } + s.loose }

/**
 * Moves [by] channels up or down the sidebar's list, "All channels" at its top. With [unread],
 * to the nearest channel that way with something unread, if there is one.
 */
fun FeedUi.stepChannel(by: Int, unread: Boolean = false) {
    val stops = listOf<String?>(null) + channelOrder.map { it.slug }
    val here = stops.indexOf(place.project).coerceAtLeast(0)
    val target = if (unread) {
        val unreadSlugs = channelOrder.filter { it.unread > 0 }.map { it.slug }.toSet()
        generateSequence(here + by) { it + by }.takeWhile { it in stops.indices }.firstOrNull { stops[it] in unreadSlugs } ?: return
    } else {
        (here + by).takeIf { it in stops.indices } ?: return
    }
    emit(FeedEvent.SelectProject(stops[target]))
}

/** Opens the [n]th channel in the sidebar's list, counting from 1. */
fun FeedUi.openChannel(n: Int) {
    val channel = channelOrder.getOrNull(n - 1) ?: return
    emit(FeedEvent.SelectProject(channel.slug))
}

/**
 * Moves the keyboard's card [by] cards down (or up) the feed and scrolls to it. With none yet, it
 * starts at [start] (the first card on screen), or the top.
 */
internal fun FeedUi.moveCursor(by: Int, start: String? = null) {
    val ids = sections.flatMap { it.items }.map { it.id }
    if (ids.isEmpty()) return
    val now = cursor?.let(ids::indexOf)?.takeIf { it >= 0 }
    val next = if (now == null) start?.let(ids::indexOf)?.takeIf { it >= 0 } ?: 0 else (now + by).coerceIn(ids.indices)
    cursor = ids[next]
    reveal(ids[next])
}

/** Opens the thread of the keyboard's card, showing every reply. */
internal fun FeedUi.openCursor() {
    val id = cursor ?: return
    if (state.item(id) !is Threaded) return
    expandedThreads.add(id)
    openThread(id, phone = isPhone)
    emit(FeedEvent.OpenThread(id))
}

/**
 * The feed's keyboard: what each shortcut does. [preview] takes the ones with a modifier key,
 * which work even while writing; [bubble] takes single keys, which only a key nothing else wanted
 * reaches, and never while a text box has the cursor or something is open over the feed.
 */
internal class FeedKeys(private val ui: FeedUi, private val mac: Boolean = shortcutsUseCommand) {
    var refresh: () -> Unit = {}
    var toTop: () -> Unit = {}

    /** Whether the feed itself has the keyboard, not a button in it: the arrows and Enter are then the feed's. */
    var feedFocused: () -> Boolean = { false }

    /** The first card on screen, where J and K start. */
    var firstVisible: () -> String? = { null }

    /** The teams, for Cmd/Ctrl+Shift+1 to 9; null outside a signed-in app. */
    var teams: TeamsUi? = null

    private var gAt: TimeSource.Monotonic.ValueTimeMark? = null

    private val KeyEvent.command get() = if (mac) isMetaPressed else isCtrlPressed

    fun preview(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val either = e.isMetaPressed || e.isCtrlPressed
        // In a browser tab its own history does Back and Forward (FollowBrowserHistory).
        val local = ui.browser == null
        return when {
            e.key == Key.K && either -> run { ui.commandOpen = true; true }
            e.key == Key.F && either && !e.isShiftPressed -> run { ui.commandOpen = true; true }
            e.key == Key.Slash && either -> run { ui.shortcutsOpen = true; true }
            e.key == Key.F5 || (e.key == Key.R && either) -> run { refresh(); true }
            local && isBack(e) -> run { ui.goBack(); true }
            local && isForward(e) -> run { ui.goForward(); true }
            e.isAltPressed && !either && e.key == Key.DirectionUp -> run { ui.stepChannel(-1, unread = e.isShiftPressed); true }
            e.isAltPressed && !either && e.key == Key.DirectionDown -> run { ui.stepChannel(1, unread = e.isShiftPressed); true }
            e.command && !e.isAltPressed && !e.isShiftPressed && digit(e) != null -> run { ui.openChannel(digit(e)!!); true }
            // With Shift: that team, in the switcher's order. The web app is one team per tab.
            e.command && !e.isAltPressed && e.isShiftPressed && digit(e) != null && teams?.web == false ->
                teams?.numbered(digit(e)!!)?.let { teams?.switchTo(it); true } ?: false
            e.command && !e.isShiftPressed && e.key == Key.N -> run { ui.focusComposer(); true }
            e.command && !e.isShiftPressed && !e.isAltPressed && e.key == Key.B -> run { ui.toggleBoard(); true }
            else -> false
        }
    }

    private fun isBack(e: KeyEvent) =
        if (mac) e.isMetaPressed && e.key == Key.LeftBracket else e.isAltPressed && !e.isCtrlPressed && e.key == Key.DirectionLeft

    private fun isForward(e: KeyEvent) =
        if (mac) e.isMetaPressed && e.key == Key.RightBracket else e.isAltPressed && !e.isCtrlPressed && e.key == Key.DirectionRight

    private fun digit(e: KeyEvent): Int? = when (e.key) {
        Key.One -> 1
        Key.Two -> 2
        Key.Three -> 3
        Key.Four -> 4
        Key.Five -> 5
        Key.Six -> 6
        Key.Seven -> 7
        Key.Eight -> 8
        Key.Nine -> 9
        else -> null
    }

    fun bubble(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        if (e.key == Key.Escape) return escape()
        if (e.key == Key.MoveHome) return run { toTop(); true }
        // Single keys: never while writing, never over a sheet, never with Ctrl, Alt or ⌘ held.
        if (Typing.active || ui.overlayOpen || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val g = gAt
        gAt = null
        if (g != null && g.elapsedNow().inWholeMilliseconds < SequenceMillis) {
            when (e.key) {
                Key.H -> return run { ui.emit(FeedEvent.SelectProject(null)); true }
                Key.W -> return run { ui.showWaiting(); true }
                Key.S -> return run { ui.alertsOpen = true; true }
                else -> Unit
            }
        }
        val char = e.utf16CodePoint
        if (char == '?'.code || (e.key == Key.Slash && e.isShiftPressed)) return run { ui.shortcutsOpen = true; true }
        if (e.isShiftPressed) return false
        val feed = feedFocused()
        return when (e.key) {
            Key.Slash -> run { ui.commandOpen = true; true }
            Key.G -> run { gAt = TimeSource.Monotonic.markNow(); true }
            Key.J -> run { ui.moveCursor(1, firstVisible()); true }
            Key.K -> run { ui.moveCursor(-1, firstVisible()); true }
            Key.DirectionDown -> feed && run { ui.moveCursor(1, firstVisible()); true }
            Key.DirectionUp -> feed && run { ui.moveCursor(-1, firstVisible()); true }
            Key.O -> ui.cursor != null && run { ui.openCursor(); true }
            Key.Enter, Key.NumPadEnter -> feed && ui.cursor != null && run { ui.openCursor(); true }
            Key.R -> ui.cursor?.let { ui.emit(FeedEvent.Reply(it)); true } ?: false
            Key.N -> run { ui.focusComposer(); true }
            else -> false
        }
    }

    /** Escape closes what is on top: a sheet, then the message box, then the thread, then the keyboard's card. */
    private fun escape(): Boolean {
        if (ui.closeTopOverlay()) return true
        if (ui.composerOpen) return run { ui.closeComposer(); true }
        if (ui.aloneId != null) return run { ui.closeAlone(); true }
        if (ui.threadId != null) return run {
            ui.threadId = null
            ui.phoneThreadOpen = false
            true
        }
        if (ui.cursor != null) return run { ui.cursor = null; true }
        return false
    }

    companion object {
        /** How long after G its second key counts, as in G then H. */
        const val SequenceMillis = 1_500L
    }
}

/**
 * The mouse's Back and Forward side buttons walk the feed's history, where the app sees them (the
 * desktop). Android sends them as system back; a browser does them through its own history.
 */
internal expect fun Modifier.mouseBackForward(ui: FeedUi): Modifier

/** Every shortcut, opened with ? or Ctrl+/ (⌘/), and from the command bar. Escape or a tap outside close it. */
@Composable
fun ShortcutsSheet(ui: FeedUi, safeArea: SafeArea, mac: Boolean = shortcutsUseCommand) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val close = { ui.shortcutsOpen = false }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val groups = remember(mac) { keyboardShortcuts(mac, web = ui.browser != null) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 64.dp, start = 12.dp, end = 12.dp, bottom = 24.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Keyboard shortcuts" }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Keyboard shortcuts", Modifier.weight(1f).semantics { heading() }, color = colors.text, style = AgentTheme.type.title)
                IconAction(Icons.Close, "Close keyboard shortcuts", onClick = close)
            }
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                groups.forEach { group ->
                    SectionLabel(group.title, Modifier.padding(top = 14.dp, bottom = 6.dp))
                    group.shortcuts.forEach { ShortcutRow(it) }
                }
                // Something missing, or in the way? Say so from here too.
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Missing something, or something in the way?", Modifier.weight(1f), color = colors.textMuted, style = AgentTheme.type.body)
                    AppButton("Send feedback", ButtonTone.Secondary, icon = Icons.Feedback) { ui.openFeedback(FeedbackCompose()) }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: Shortcut) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            // One line for a screen reader: what it does, then the keys.
            .clearAndSetSemantics { contentDescription = "${shortcut.does}: ${shortcut.keys}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(shortcut.does, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
        Spacer(Modifier.width(12.dp))
        Kbd(shortcut.keys)
    }
}
