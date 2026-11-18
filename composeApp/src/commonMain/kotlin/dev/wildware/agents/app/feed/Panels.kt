package dev.wildware.agents.app.feed

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
import androidx.compose.foundation.text.input.rememberTextFieldState

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
private fun NavRow(
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
                IconAction(Icons.Bell, "Notifications")
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
                    spoken = "${p.name}, ${p.unread} unread" + if (p.waiting > 0) ", ${questions(p.waiting)} waiting on you" else "",
                    onClick = { ui.emit(FeedEvent.SelectProject(p.slug)) },
                )
            }
            ArchivedProjects(ui)
            if (showAgents) {
                Spacer(Modifier.height(22.dp))
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Agents", Modifier.weight(1f))
                    MetaText("${state.agents.count { it.presence == Presence.Online }} online")
                }
                Spacer(Modifier.height(8.dp))
                state.agents.forEach { a -> AgentRow(a) }
                if (state.agents.isEmpty()) MetaText("No agents online right now", Modifier.padding(horizontal = 10.dp))
            }
            Spacer(Modifier.height(18.dp))
        }
        OwnerRow(state.owner, onToggleTheme = { ui.emit(FeedEvent.ToggleTheme) }) { ui.emit(FeedEvent.SignOut) }
    }
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
    Icon(Icons.Hash, null, tint = colors.textFaint, modifier = Modifier.size(15.dp))
}

@Composable
private fun AgentRow(agent: Agent) {
    val colors = AgentTheme.colors
    val spoken = "${agent.name}, ${agent.role}, ${agent.presence.label}" + (agent.doing?.let { ", $it" } ?: "")
    val shape = AgentTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape).pressable(spoken, shape) {}.padding(horizontal = 10.dp, vertical = 5.dp),
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
private fun OwnerRow(owner: Author.Owner, onToggleTheme: () -> Unit, onSignOut: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(owner, 28.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(owner.name, color = colors.text, style = AgentTheme.type.label)
            MetaText("Owner")
        }
        IconAction(Icons.Moon, if (AgentTheme.colors.isDark) "Switch to light" else "Switch to dark", onClick = onToggleTheme)
        IconAction(Icons.SignOut, "Sign out", onClick = onSignOut)
    }
}

/** The title area above the feed: where you are, who is around, and the filters. */
@Composable
fun FeedHeader(ui: FeedUi, compact: Boolean, modifier: Modifier = Modifier) {
    val state = ui.state
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val online = state.agents.filter { it.presence == Presence.Online }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetaText(if (state.current.slug == null) "EVERYTHING" else "PROJECT", style = type.overline)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
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
                    LinkBadge(state.link)
                    MetaText(
                        " · ${online.size} online · ${state.current.unread} new",
                        color = colors.textMuted,
                    )
                }
                OutboxLine(ui)
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
            Modifier.fillMaxWidth().then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            TextAction("Retry") { ui.emit(FeedEvent.RetrySend(failed)) }
            TextAction("Drop") { ui.emit(FeedEvent.DropUnsent(failed)) }
        }
    }
}

/** A small word you can press, sized for a finger however small it looks. */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val shape = AgentTheme.shapes.small
    Box(Modifier.clip(shape).pressable(label, shape, labelIsShown = true, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = AgentTheme.colors.text, style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold))
    }
}

/**
 * The box for writing: to everyone in a project, or into a thread. Enter adds a line;
 * Cmd+Enter or Ctrl+Enter (or the arrow) sends. Files come in through the paperclip, by dropping
 * them on the box, or by pasting a picture; each shows above the text with a way to remove it.
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
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val text = rememberTextFieldState()
    val preview = LocalPreview.current
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
    // The feed's own composer takes what was shared in from another app.
    val ui = LocalFeedUi.current
    if (focus != null && ui != null) {
        LaunchedEffect(ui.incoming) {
            val draft = ui.incoming ?: return@LaunchedEffect
            ui.incoming = null
            if (draft.text.isNotBlank()) text.edit { replace(0, length, Dictation.appendDictation(text.text.toString(), draft.text)) }
            add(draft.files)
            runCatching { focus.requestFocus() }
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
    Column(
        modifier
            .fillMaxWidth()
            .lift(AgentTheme.elevation.raised, shape)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(if (hovering) 2.dp else 1.dp, if (hovering) colors.accent else colors.border, shape)
            .fileDropTarget(onHover = { hovering = it }, onFiles = add)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        AttachmentStrip(files, onRemove = { files.remove(it) }, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
        refused?.let { MetaText(it, Modifier.padding(start = 8.dp, top = 4.dp), color = colors.error) }
        recorder?.note?.let { MetaText(it, Modifier.padding(start = 8.dp, top = 4.dp), color = colors.textMuted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picker != null) {
                IconAction(Icons.Paperclip, "Attach files") { picker.pick { picked -> add(picked) } }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            if (recorder?.active == true) RecordingStrip(recorder, Modifier.weight(1f)) else TextInput(
                text,
                label = label,
                placeholder = placeholder,
                modifier = Modifier.weight(1f).then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onPreviewKeyEvent { e ->
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
            if (recorder != null && (recorder.active || (text.text.isBlank() && files.isEmpty()))) {
                MicButton(recorder)
            } else {
                SendButton(enabled = text.text.isNotBlank() || files.isNotEmpty(), onClick = send)
            }
        }
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
    val spoken = "${reply.author.name}, $time" + if (reply.unread) ", new" else ""
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
                MetaText(time, Modifier.padding(start = 8.dp).clearAndSetSemantics { })
            }
            Spacer(Modifier.height(2.dp))
            MarkdownBody(reply.body)
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
internal fun NewDivider(count: Int) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$count new replies below" },
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
