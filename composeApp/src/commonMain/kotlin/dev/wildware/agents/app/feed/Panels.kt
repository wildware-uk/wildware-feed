package dev.wildware.agents.app.feed

import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.TextFieldState
import dev.wildware.agents.Composer as Dictation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.AppMark
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale

@Composable
private fun Kbd(text: String, light: Boolean = false) {
    val colors = AgentTheme.colors
    Text(
        text,
        Modifier
            .clip(AgentTheme.shapes.small)
            .border(1.dp, colors.border, AgentTheme.shapes.small)
            .background(if (light) colors.surface else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
        color = colors.textFaint,
        style = AgentTheme.type.meta.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

@Composable
private fun SearchField(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .pressable("Search everything. Shortcut Command K", shape, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, tint = colors.textFaint, modifier = Modifier.size(16.dp))
        Text("Search", Modifier.padding(start = 8.dp).weight(1f), color = colors.textFaint, style = AgentTheme.type.label)
        Kbd("⌘K", light = true)
    }
}

@Composable
internal fun NavRow(
    label: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    spoken: String = label,
    onClick: () -> Unit = {},
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val bg = if (selected) colors.text.copy(alpha = if (colors.isDark) 0.07f else 0.06f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .pressable(spoken, shape, role = Role.Tab, labelIsShown = spoken == label, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
        Text(
            label,
            Modifier.padding(start = 10.dp).weight(1f),
            color = if (selected) colors.text else colors.textMuted,
            style = AgentTheme.type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** Projects, people and the way in to everything else. Desktop only; phones use the top bar. */
@Composable
fun Sidebar(
    ui: FeedUi,
    modifier: Modifier = Modifier,
    /** Off when the agents rail is on screen: one list of who is working, not two. */
    showAgents: Boolean = true,
) {
    val state = ui.state
    val colors = AgentTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.sidebar),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark(28.dp)
                // One line at the sidebar's 240: the name never wraps under its own mark.
                Text(
                    dev.wildware.agents.app.ProductName,
                    Modifier.padding(start = 9.dp).weight(1f),
                    color = colors.text,
                    style = AgentTheme.type.title.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                BellButton(state.bell.unseen) { ui.bellOpen = !ui.bellOpen }
            }
            Spacer(Modifier.height(14.dp))
            SearchField { ui.commandOpen = true }
            Spacer(Modifier.height(14.dp))
            NavRow(
                "All projects",
                selected = state.current.slug == null && !ui.showingWaiting,
                leading = { Icon(Icons.Inbox, null, tint = colors.textMuted, modifier = Modifier.size(18.dp)) },
                trailing = { UnreadBadge(state.totalUnread) },
                spoken = "All projects, ${state.totalUnread} unread",
                onClick = { ui.emit(FeedEvent.SelectProject(null)) },
            )
            NavRow(
                "Waiting on you",
                selected = ui.showingWaiting,
                // The count is every project's, so the row opens every project's questions.
                onClick = { if (ui.showingWaiting) ui.filter = FeedFilter.All else ui.showWaiting() },
                leading = { Icon(Icons.Clock, null, tint = colors.waiting, modifier = Modifier.size(18.dp)) },
                // Nothing waiting is the normal state: no badge, rather than a loud "0".
                trailing = { WaitingBadge(state.waitingTotal) },
                spoken = "Waiting on you, " + if (state.waitingTotal == 0) "nothing" else questions(state.waitingTotal),
            )
            Spacer(Modifier.height(22.dp))
            SectionLabel("Projects", Modifier.padding(horizontal = 10.dp))
            Spacer(Modifier.height(8.dp))
            ui.projects.filter { !it.archived }.forEach { p ->
                val selected = p.id == state.current.id
                NavRow(
                    p.name,
                    selected = selected,
                    leading = { ProjectGlyph(p) },
                    trailing = {
                        WaitingBadge(p.waiting, Modifier.padding(end = 6.dp))
                        UnreadBadge(p.unread, emphasised = selected)
                    },
                    spoken = projectSpoken(p),
                    onClick = { ui.emit(FeedEvent.SelectProject(p.slug)) },
                )
            }
            NewProjectRow(ui)
            ArchivedProjects(ui)
            if (showAgents) {
                Spacer(Modifier.height(22.dp))
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Agents", Modifier.weight(1f))
                    MetaText("${state.agents.count { it.presence == Presence.Online }} online")
                }
                Spacer(Modifier.height(8.dp))
                state.agents.forEach { a -> AgentRow(a) { ui.agentOpen = a.id } }
                if (state.agents.isEmpty()) MetaText("No agents online right now", Modifier.padding(horizontal = 10.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
        OwnerRow(state.owner, onAlerts = { ui.alertsOpen = true }, onToggleTheme = { ui.emit(FeedEvent.ToggleTheme) }) { ui.emit(FeedEvent.SignOut) }
    }
}

/** "+ New project", under the list of projects. */
@Composable
internal fun NewProjectRow(ui: FeedUi) {
    val colors = AgentTheme.colors
    NavRow(
        "New project",
        selected = false,
        leading = { Icon(Icons.Plus, null, tint = colors.textMuted, modifier = Modifier.size(18.dp)) },
        trailing = {},
        spoken = "New project",
        onClick = { ui.projectDialog = ProjectDialog.New },
    )
}

/**
 * Archived projects, folded away under one row at the bottom of the list. They can share a name
 * with an active project, so they never sit among the active ones.
 */
@Composable
internal fun ArchivedProjects(ui: FeedUi) {
    val archived = ui.projects.filter { it.archived }
    if (archived.isEmpty()) return
    val colors = AgentTheme.colors
    NavRow(
        "Archived",
        selected = false,
        leading = { Icon(if (ui.showArchived) Icons.ChevronDown else Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.size(15.dp)) },
        trailing = { MetaText("${archived.size}") },
        spoken = if (ui.showArchived) "Hide ${archived.size} archived projects" else "Show ${archived.size} archived projects",
        onClick = { ui.showArchived = !ui.showArchived },
    )
    if (ui.showArchived) {
        archived.forEach { p ->
            NavRow(
                p.name,
                selected = p.id == ui.state.current.id,
                leading = { ProjectGlyph(p) },
                spoken = "${p.name}, archived",
                onClick = { ui.emit(FeedEvent.SelectProject(p.slug)) },
            )
        }
    }
}

@Composable
private fun ProjectGlyph(p: Project) {
    val colors = AgentTheme.colors
    // A pinned project wears a pin in the accent where the others have a hash, as on the website.
    if (p.pinned) Icon(Icons.Pin, null, tint = colors.accent, modifier = Modifier.size(15.dp))
    else Icon(Icons.Hash, null, tint = colors.textFaint, modifier = Modifier.size(15.dp))
}

@Composable
private fun AgentRow(agent: Agent, onOpen: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape).pressable(agentSpoken(agent), shape, onClick = onOpen).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(Author.AgentAuthor(agent), 26.dp, showPresence = true)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                agent.name,
                color = if (agent.presence == Presence.Offline) colors.textFaint else colors.text,
                style = AgentTheme.type.label,
            )
            MetaText(agent.doing ?: agent.presence.label)
        }
    }
}

@Composable
private fun OwnerRow(owner: Author.Owner, onAlerts: () -> Unit, onToggleTheme: () -> Unit, onSignOut: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val demo = LocalDemo.current
        // You and your settings: the alerts this device gets, as the avatar opens on a phone.
        Row(
            Modifier.weight(1f).clip(AgentTheme.shapes.small).pressable("Alerts on this device", AgentTheme.shapes.small, onClick = onAlerts),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(owner, 28.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(owner.name, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(if (demo) "Demo" else "Alerts")
            }
        }
        LocalOpenSettings.current?.let { IconAction(Icons.Settings, "This computer", onClick = it) }
        IconAction(Icons.Moon, if (AgentTheme.colors.isDark) "Switch to light" else "Switch to dark", onClick = onToggleTheme)
        IconAction(Icons.SignOut, if (demo) "Leave demo" else "Sign out", onClick = onSignOut)
    }
}

/**
 * A project's logo above the feed. Beside the name it is decoration, as the name says it all; standing
 * in for the name (a wordmark) it is the heading, and a screen reader hears the name.
 */
@Composable
private fun ProjectLogo(logo: Attachment, name: String, wordmark: Boolean, compact: Boolean) {
    val height = if (compact) 32.dp else 40.dp
    val shape = AgentTheme.shapes.small
    AttachmentImage(
        logo,
        Modifier
            .then(if (wordmark) Modifier.widthIn(max = 260.dp) else Modifier.padding(end = 10.dp))
            .height(height)
            .aspectRatio(if (wordmark) logo.aspect else 1f, matchHeightConstraintsFirst = true)
            .clip(shape)
            .then(if (wordmark) Modifier.semantics { heading(); contentDescription = name } else Modifier.clearAndSetSemantics { }),
        contentScale = ContentScale.Fit,
    )
}

/** The title area above the feed: where you are, who is around, and the filters. */
@Composable
fun FeedHeader(ui: FeedUi, compact: Boolean, modifier: Modifier = Modifier) {
    val state = ui.state
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val online = state.agents.filter { it.presence == Presence.Online }
    Column(modifier) {
        if (ui.missingLink) MissingLink(Modifier.padding(bottom = 12.dp)) { ui.missingLink = false }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetaText(if (state.current.slug == null) "EVERYTHING" else "PROJECT", style = type.overline)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val logo = state.current.logo
                    val wordmark = logo != null && state.current.logoInsteadOfName
                    if (logo != null) ProjectLogo(logo, state.current.name, wordmark, compact)
                    if (!wordmark) Text(
                        state.current.name,
                        Modifier.semantics { heading() },
                        color = colors.text,
                        style = if (compact) type.display.copy(fontSize = type.display.fontSize * 0.82f, lineHeight = type.display.lineHeight * 0.85f) else type.display,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (compact) {
                        Icon(Icons.ChevronDown, "Switch project", tint = colors.textMuted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (LocalDemo.current) DemoBadge(Modifier.padding(end = 8.dp))
                    LinkBadge(state.link)
                    MetaText(
                        " · ${online.size} online · ${state.current.unread} new",
                        color = colors.textMuted,
                    )
                }
                OutboxLine(ui)
            }
            if (state.current.slug != null) {
                val id = state.current.id
                IconAction(Icons.Settings, "Project settings") { ui.projectDialog = ProjectDialog.Settings(id) }
                Spacer(Modifier.width(if (compact) 0.dp else 10.dp))
            }
            if (!compact) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Online: " + online.joinToString { it.name }
                }) {
                    online.forEach { a ->
                        Box(Modifier.clip(CircleShape).background(colors.background).padding(2.dp)) { Avatar(Author.AgentAuthor(a), 28.dp) }
                    }
                }
                Spacer(Modifier.width(14.dp))
                AppButton("Mark all read", ButtonTone.Secondary, icon = Icons.Check, enabled = state.totalUnread > 0) {
                    ui.emit(FeedEvent.MarkAllRead)
                }
            }
        }
        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
        Row(
            // Ten chips: they scroll sideways rather than wrap, on every size.
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // First, so it is in sight on a phone, where the row scrolls sideways.
            TagFilterChip()
            val counts = remember(state.sections) {
                val all = state.sections.flatMap { it.items }
                FeedFilter.entries.associateWith { f -> all.count(f::matches) }
            }
            FeedFilter.entries.filter { !compact || it != FeedFilter.FromYou }.forEach { f ->
                val count = counts.getValue(f)
                Chip(f.label, selected = ui.filter == f, count = count.takeIf { f != FeedFilter.Updates && it > 0 }) { ui.filter = f }
            }
        }
    }
}

/** The live connection in a word, with a dot: green when live, amber while it reconnects. */
@Composable
private fun LinkBadge(link: LinkStatus) {
    val colors = AgentTheme.colors
    val dot = when (link) {
        LinkStatus.Live -> colors.online
        LinkStatus.Connecting, LinkStatus.Reconnecting -> colors.warning
        LinkStatus.Offline -> colors.textFaint
    }
    Row(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Connection: ${link.label}"
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        MetaText(link.label, Modifier.padding(start = 7.dp).clearAndSetSemantics { }, color = colors.textMuted)
    }
}

/** "2 waiting to send", and a way out when the dashboard refused something. Hidden when all is sent. */
@Composable
private fun OutboxLine(ui: FeedUi) {
    val outbox = ui.state.outbox
    if (outbox.waiting == 0 && outbox.failed == 0) return
    val colors = AgentTheme.colors
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (outbox.waiting > 0) {
            Icon(Icons.Clock, null, tint = colors.warning, modifier = Modifier.size(13.dp))
            MetaText(
                if (outbox.waiting == 1) "1 waiting to send" else "${outbox.waiting} waiting to send",
                Modifier.padding(start = 5.dp, end = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.warning,
            )
        }
        val failed = outbox.failedId
        if (failed != null) {
            Icon(Icons.Close, null, tint = colors.error, modifier = Modifier.size(13.dp))
            MetaText(
                if (outbox.failed == 1) "1 couldn't send" else "${outbox.failed} couldn't send",
                Modifier.padding(start = 5.dp),
                color = colors.error,
            )
            MetaAction("Retry") { ui.emit(FeedEvent.RetrySend(failed)) }
            MetaAction("Drop") { ui.emit(FeedEvent.DropUnsent(failed)) }
        }
    }
}

/** A small word you can press, sized for a finger however small it looks. */
@Composable
internal fun MetaAction(label: String, onClick: () -> Unit) {
    val shape = AgentTheme.shapes.small
    Box(Modifier.clip(shape).pressable(label, shape, labelIsShown = true, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = AgentTheme.colors.text, style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold))
    }
}

/**
 * The box for writing: to everyone in a project, or into a thread. Enter adds a line;
 * Cmd+Enter or Ctrl+Enter (or the arrow) sends. Files come in through the paperclip, by dropping
 * them on the box, or by pasting a picture; each shows above the text with a way to remove it.
 *
 * Once open (the cursor in it; on a phone, the keyboard up) it closes without sending by its close
 * button, a swipe down on it, a tap on the feed, back, or Escape. What was written stays, and with
 * a [draftKey] it stays even when the box leaves the screen and comes back.
 */
@Composable
fun Composer(
    placeholder: String,
    onSend: (String, List<PendingFile>) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String = "Message",
    /** Lets "New post" in the command bar put the cursor here. */
    focus: FocusRequester? = null,
    /** Which draft this box writes: "project:<slug>", "thread:<id>". */
    draftKey: String? = null,
    /** A new post's box: the project (by id) whose tags it can go up wearing. Replies have none. */
    tagProject: String? = null,
    /** A reply box: who a Reply on a line asks it to name, and whether to take the cursor. [onAsked] once done. */
    ask: MentionAsk? = null,
    onAsked: () -> Unit = {},
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val preview = LocalPreview.current
    // The feed's own composer takes what was shared in from another app.
    val ui = LocalFeedUi.current
    val text = remember(draftKey) {
        TextFieldState(draftKey?.let { ui?.drafts?.get(it) } ?: preview.composerText.takeIf { focus != null }.orEmpty())
    }
    if (draftKey != null && ui != null) {
        LaunchedEffect(text) {
            snapshotFlow { text.text.toString() }.collect { ui.drafts[draftKey] = it }
        }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var open by remember { mutableStateOf(false) }
    // The box's own, for a Reply to put the cursor in it; [focus] when the screen holds one.
    val fieldFocus = focus ?: remember { FocusRequester() }
    val close: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }
    // Back, or a tap on the feed, asks through the screen's state.
    val closeRequests = ui?.closeRequests ?: 0
    LaunchedEffect(closeRequests) { if (closeRequests > 0 && open) close() }
    DisposableEffect(ui) { onDispose { if (open) ui?.composerOpen = false } }
    val stillOpen by rememberUpdatedState(open)
    val swipeClosePx = with(LocalDensity.current) { SwipeToClose.toPx() }
    val files = remember { mutableStateListOf<PendingFile>().apply { if (focus != null) addAll(preview.composerFiles) } }
    var hovering by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    val picker = LocalFilePicker.current
    // Hold-to-talk puts the words into the box, after anything already typed, to check before sending.
    val recorder = rememberVoiceRecorder(LocalVoiceInput.current) { words ->
        val joined = Dictation.appendDictation(text.text.toString(), words)
        text.edit { replace(0, length, joined) }
    }
    val add: (List<PendingFile>) -> Unit = { incoming ->
        val (fits, big) = incoming.partition { !tooBig(it) }
        files += fits
        refused = big.firstOrNull()?.let { "${it.name} is over 25 MB, so it was left out." }
    }
    if (focus != null && (preview.composerOpen || preview.composerListening)) {
        LaunchedEffect(Unit) {
            if (preview.composerOpen) runCatching { focus.requestFocus() }
            if (preview.composerListening) recorder?.press()
        }
    }
    if (focus != null && ui != null) {
        LaunchedEffect(ui.incoming) {
            val draft = ui.incoming ?: return@LaunchedEffect
            ui.incoming = null
            if (draft.text.isNotBlank()) text.edit { replace(0, length, Dictation.appendDictation(text.text.toString(), draft.text)) }
            add(draft.files)
            runCatching { focus.requestFocus() }
        }
    }
    if (ask != null) {
        LaunchedEffect(ask) {
            // Read here, not while drawing, so the box doesn't redraw with every engine publish.
            val known = ui?.state?.mentionable.orEmpty().map { it.name }
            val now = text.text.toString()
            val next = MentionText.retarget(now, ask.mention, known)
            if (next != now || ask.focus) text.edit {
                if (next != now) replace(0, length, next)
                selection = TextRange(length)
            }
            if (ask.focus) runCatching { fieldFocus.requestFocus() }
            onAsked()
        }
    }
    // Read as a yes/no, so typing redraws only the text, not the whole box, on every key.
    val blank by remember { derivedStateOf { text.text.isBlank() } }
    // The @mention under the cursor, if any: only the word at the cursor is read, so other typing
    // redraws nothing here.
    val typing by remember { derivedStateOf { if (text.selection.collapsed) MentionText.at(text.text.toString(), text.selection.end) else null } }
    var mentionClosedAt by remember { mutableStateOf<Int?>(null) }
    var mentionSelected by remember { mutableIntStateOf(0) }
    val mention = typing?.takeIf { open && it.start != mentionClosedAt }
    val mentionChoices = mention?.let { MentionText.matches(ui?.state?.mentionable.orEmpty(), it.query) }.orEmpty()
    LaunchedEffect(mention?.start, mention?.query) { mentionSelected = 0 }
    val pickMention: (Mentionable) -> Unit = { choice ->
        val at = typing
        if (at != null) {
            val (next, cursor) = MentionText.insert(text.text.toString(), at, choice.name, text.selection.end)
            text.edit {
                replace(0, length, next)
                selection = TextRange(cursor)
            }
        }
    }
    val send = {
        val typed = text.text.toString().trim()
        if (typed.isNotEmpty() || files.isNotEmpty()) {
            onSend(typed, files.toList())
            text.clearText()
            files.clear()
            refused = null
        }
    }
    Column(modifier.fillMaxWidth()) {
    if (mentionChoices.isNotEmpty()) MentionList(mentionChoices, mentionSelected, pickMention)
    Column(
        Modifier
            .fillMaxWidth()
            .lift(AgentTheme.elevation.raised, shape)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(if (hovering) 2.dp else 1.dp, if (hovering) colors.accent else colors.border, shape)
            .fileDropTarget(onHover = { hovering = it }, onFiles = add)
            .pointerInput(Unit) {
                // A swipe down on the open box closes it. Watched before the text box sees it, and
                // never taken from it, so dragging to select still works.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val dy = change.position.y - down.position.y
                        val dx = change.position.x - down.position.x
                        if (stillOpen && dy > swipeClosePx && dy > 2 * kotlin.math.abs(dx)) {
                            close()
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            }
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (ui != null && tagProject != null) NewPostTagChips(ui, tagProject, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
        AttachmentStrip(files, onRemove = { files.remove(it) }, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
        refused?.let { MetaText(it, Modifier.padding(start = 8.dp, top = 4.dp), color = colors.error) }
        recorder?.note?.let { MetaText(it, Modifier.padding(start = 8.dp, top = 4.dp), color = colors.textMuted) }
        recorder?.takeIf { it.active && it.heard.isNotBlank() }?.let { LiveWords(it.heard) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // On a phone the open box says how to put it away, as the keyboard covers the feed.
            if (open && LocalIsPhone.current) IconAction(Icons.ChevronDown, "Close the message box", onClick = close)
            if (picker != null) {
                IconAction(Icons.Paperclip, "Attach files") { picker.pick { picked -> add(picked) } }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            // Tags to post it with, chosen before it is written, as on the website.
            if (ui != null && tagProject != null) {
                IconAction(Icons.Hash, "Add a tag") { ui.tagging = NewPostTags + tagProject }
            }
            if (recorder?.active == true) RecordingStrip(recorder, Modifier.weight(1f)) else TextInput(
                text,
                label = label,
                placeholder = placeholder,
                modifier = Modifier.weight(1f).focusRequester(fieldFocus).onFocusChanged {
                    if (it.isFocused != open) {
                        open = it.isFocused
                        ui?.composerOpen = it.isFocused
                    }
                }.onPreviewKeyEvent { e ->
                    // While the @ list is up, the arrows, Enter, Tab and Escape are its.
                    if (e.type == KeyEventType.KeyDown && mentionChoices.isNotEmpty()) {
                        when (e.key) {
                            Key.DirectionDown -> { mentionSelected = (mentionSelected + 1) % mentionChoices.size; return@onPreviewKeyEvent true }
                            Key.DirectionUp -> { mentionSelected = (mentionSelected - 1 + mentionChoices.size) % mentionChoices.size; return@onPreviewKeyEvent true }
                            Key.Enter, Key.Tab -> { pickMention(mentionChoices[mentionSelected.coerceIn(mentionChoices.indices)]); return@onPreviewKeyEvent true }
                            Key.Escape -> { mentionClosedAt = mention?.start; return@onPreviewKeyEvent true }
                            else -> {}
                        }
                    }
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        close()
                        return@onPreviewKeyEvent true
                    }
                    // Paste a picture or files from the clipboard; plain text pastes as usual.
                    val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isMetaPressed || e.isCtrlPressed)
                    val pasted = if (paste) clipboardFiles() else null
                    if (pasted != null) add(pasted)
                    pasted != null
                },
                multiline = true,
                onSubmit = send,
            )
            if (!compact) {
                IconAction(Icons.At, "Mention an agent") { text.edit { append("@") } }
                MetaText("⌘↵", Modifier.padding(horizontal = 6.dp))
            }
            // An empty box offers the mic; once there is something to send, the send button.
            if (recorder != null && (recorder.active || (blank && files.isEmpty()))) {
                MicButton(recorder)
            } else {
                SendButton(enabled = !blank || files.isNotEmpty(), onClick = send)
            }
        }
    }
    }
}

/** How far down a finger drags the open box to close it. */
private val SwipeToClose = 48.dp

/**
 * A touch on the feed puts the open message box away, as tapping or scrolling the messages does
 * across iOS. Only watched: the touch still reaches whatever it landed on.
 */
fun Modifier.closesComposer(ui: FeedUi): Modifier = pointerInput(ui) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (ui.composerOpen) ui.closeComposer()
    }
}

/** Said once, above the feed, when a link pointed at something that isn't here: better than a tap that seems to do nothing. */
@Composable
private fun MissingLink(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Couldn't find what that link pointed at. It may have been deleted.",
            Modifier.weight(1f).padding(vertical = 10.dp),
            color = colors.textMuted,
            style = AgentTheme.type.body,
        )
        IconAction(Icons.Close, "Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Box(Modifier.pressable("Send", shape, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(34.dp).clip(shape).background(if (enabled) colors.accent else colors.text.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Send, null, tint = if (enabled) colors.onAccent else colors.textFaint, modifier = Modifier.size(18.dp))
        }
    }
}

/** One reply in a thread. */
@Composable
fun ReplyView(reply: Reply, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val time = if (reply.sending) SendingLabel else reply.time
    val detail = if (reply.sending) "" else reply.timeDetail
    val answering = reply.answering?.let { "answering $it" }
    val spoken = listOfNotNull(reply.author.name, answering, detail.ifEmpty { time }, reply.edited, "new".takeIf { reply.unread }).joinToString(", ")
    Row(modifier.fillMaxWidth().alpha(if (reply.sending) SendingAlpha else 1f)) {
        Avatar(reply.author, 28.dp, showPresence = false)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (reply.author is Author.Owner) "You" else reply.author.name,
                    Modifier.clearAndSetSemantics { },
                    color = colors.text,
                    style = AgentTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                )
                TimeText(time, detail, Modifier.padding(start = 8.dp).clearAndSetSemantics { })
                if (reply.edited != null) EditedMark(Modifier.padding(start = 6.dp).clearAndSetSemantics { })
                if (answering != null) {
                    // Last, and the one to give way: a long name ends in "…" before the time does.
                    Icon(Icons.Reply, null, tint = colors.textFaint, modifier = Modifier.padding(start = 8.dp).size(12.dp))
                    MetaText(answering, Modifier.padding(start = 3.dp).weight(1f, fill = false).clearAndSetSemantics { })
                }
            }
            Spacer(Modifier.height(2.dp))
            MarkdownBody(reply.body)
            PostMedia(reply.attachments, reply.sounds, reply.files, reply.embeds)
            if (reply.undelivered) NotDelivered(Modifier.padding(top = 6.dp))
            AckLine(reply.acks, Modifier.padding(top = 6.dp))
            // A question asked under this reply is answered right here, as on the website.
            reply.questions.forEach { q ->
                QuestionCard(q, Modifier.padding(top = 8.dp), density = Density.Compact, inThread = true)
            }
            if (reply.reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ReactionRow(reply.reactions, reply.id, showAdd = false)
            }
        }
    }
}

@Composable
internal fun NewDivider(count: Int, newAbove: Boolean = false) {
    val colors = AgentTheme.colors
    val where = if (newAbove) "above" else "below"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$count new replies $where" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.accent.copy(alpha = 0.5f)))
        Text(
            "$count new",
            Modifier.padding(horizontal = 10.dp).clearAndSetSemantics { },
            color = colors.accent,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.accent.copy(alpha = 0.5f)))
    }
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(AgentTheme.colors.border))
}

/**
 * "To #garden-game ▾" above the composer on "All projects", which has no one place to post: says
 * where a post will go, and picks another project.
 */
@Composable
fun PostToPicker(ui: FeedUi, target: Project, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // A raised pill, so where the post goes is plain before it is sent.
        Row(
            Modifier
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .pressable("Posting to ${target.name}. Choose a project", shape) { open = true }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("To", color = colors.textMuted, style = AgentTheme.type.label)
            Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(colors.agent(target.hue)))
            Text(
                target.name,
                Modifier.padding(start = 6.dp),
                color = colors.text,
                style = AgentTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            )
            Icon(Icons.ChevronDown, null, tint = colors.textMuted, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = AgentTheme.shapes.medium,
            containerColor = colors.surfaceRaised,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
            ui.projects.filter { !it.archived && it.slug != null }.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name, style = AgentTheme.type.label) },
                    leadingIcon = { Box(Modifier.size(9.dp).clip(CircleShape).background(colors.agent(p.hue))) },
                    trailingIcon = if (p.id == target.id) ({ Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = {
                        ui.postTo = p.slug
                        open = false
                    },
                    modifier = Modifier.semantics { contentDescription = "Post to ${p.name}" },
                    colors = MenuDefaults.itemColors(textColor = colors.text),
                )
            }
        }
    }
}
