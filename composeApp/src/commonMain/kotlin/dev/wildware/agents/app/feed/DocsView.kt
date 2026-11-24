package dev.wildware.agents.app.feed

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/*
 * The Docs tab (server migration 076): a channel's markdown docs, linked to each other by title with
 * [[Doc name]]. Read on the channel reads them; Post edits them. Editing shows the text and a live
 * preview: side by side on a wide window, "Write" and "Preview" on a phone. A save someone else beat
 * comes back with both texts, to merge into one; nothing is saved over another person's words.
 */

/** The Docs tab in the main pane, for the sidebar's channel. */
@OptIn(FlowPreview::class)
@Composable
fun DocsPane(ui: FeedUi, modifier: Modifier = Modifier, phone: Boolean = false, top: @Composable () -> Unit = {}) {
    val state = ui.state
    val docs = state.docs
    val channel = state.current.slug
    // The dashboard reads the docs while they are on screen, and stops when they go.
    LaunchedEffect(channel) { ui.emit(FeedEvent.Docs(DocsAction.Show(channel))) }
    DisposableEffect(Unit) { onDispose { ui.emit(FeedEvent.Docs(DocsAction.Show(null))) } }
    // A [[link]] tapped elsewhere opens here once this shows its channel.
    val pending = ui.pendingDocLink
    LaunchedEffect(channel, pending) {
        if (pending != null && pending.first == channel) {
            ui.pendingDocLink = null
            ui.emit(FeedEvent.Docs(DocsAction.OpenTitle(pending.first, pending.second)))
        }
    }
    Column(modifier.semantics { paneTitle = "Docs" }) {
        top()
        DocsHeader(ui, compact = phone)
        if (channel == null) {
            Empty("Docs belong to a channel. Pick one in the sidebar to see its docs.")
            return@Column
        }
        val showDoc = docs.open != null || docs.opening != null || docs.missing != null
        if (phone) {
            if (showDoc) DocArea(ui, docs, phone = true, Modifier.fillMaxSize()) else DocList(ui, docs, Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                DocList(ui, docs, Modifier.width(280.dp).fillMaxHeight())
                VerticalRule()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (showDoc) DocArea(ui, docs, phone = false, Modifier.fillMaxSize())
                    else Empty(if (docs.docs.isEmpty() && docs.loaded) "No docs in this channel yet." else "Pick a doc to read it.")
                }
            }
        }
    }
}

@Composable
private fun DocsHeader(ui: FeedUi, compact: Boolean) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 8.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText("DOCS  ·  CHANNEL", style = type.overline)
            Text(
                ui.state.current.name,
                Modifier.semantics { heading() },
                color = colors.text,
                style = if (compact) type.title else type.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ViewToggle(ui, ui.state.current.slug != null, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = AgentTheme.colors.textMuted, style = AgentTheme.type.body)
    }
}

/** The channel's docs, newest change first, with a search box and "New doc". */
@OptIn(FlowPreview::class)
@Composable
private fun DocList(ui: FeedUi, docs: DocsState, modifier: Modifier) {
    val colors = AgentTheme.colors
    val search = remember(docs.channel) { TextFieldState(docs.query) }
    LaunchedEffect(search) {
        snapshotFlow { search.text.toString() }.debounce(250).collect { ui.emit(FeedEvent.Docs(DocsAction.Search(it.trim()))) }
    }
    var naming by remember(docs.channel) { mutableStateOf(false) }
    val name = remember(docs.channel) { TextFieldState() }
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clip(AgentTheme.shapes.medium).background(colors.surfaceSunken).border(1.dp, colors.border, AgentTheme.shapes.medium).padding(horizontal = 10.dp)) {
                TextInput(search, "Search docs", "Search docs", Modifier.fillMaxWidth())
            }
            if (docs.canEdit) IconAction(Icons.Plus, "New doc", Modifier.padding(start = 6.dp)) { naming = !naming }
        }
        if (naming) {
            val make = {
                val title = name.text.toString().trim()
                if (title.isNotEmpty()) {
                    ui.emit(FeedEvent.Docs(DocsAction.Create(title)))
                    name.setTextAndPlaceCursorAtEnd("")
                    naming = false
                }
            }
            Spacer(Modifier.height(8.dp))
            BoxedField("New doc", name, "Title of the new doc", "Title", onSubmit = make)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Create", ButtonTone.Primary, enabled = name.text.isNotBlank()) { make() }
                AppButton("Cancel", ButtonTone.Quiet) { naming = false }
            }
        }
        docs.problem?.let { MetaText(it, Modifier.padding(top = 8.dp), color = colors.error) }
        Spacer(Modifier.height(8.dp))
        when {
            !docs.loaded -> MetaText("Reading docs…", Modifier.padding(vertical = 8.dp))
            docs.docs.isEmpty() && docs.query.isNotBlank() -> MetaText("No doc has “${docs.query}”.", Modifier.padding(vertical = 8.dp))
            docs.docs.isEmpty() -> MetaText(if (docs.canEdit) "No docs yet. Press + to write the first." else "No docs yet.", Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(docs.docs, key = { it.id }) { doc ->
                val chosen = docs.open?.id == doc.id
                val shape = AgentTheme.shapes.medium
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (chosen) colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.06f) else colors.background.copy(alpha = 0f))
                        .pressable("${doc.title}, saved by ${doc.savedBy} ${doc.savedAgo}", shape) { ui.emit(FeedEvent.Docs(DocsAction.Open(doc.id))) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(doc.title, color = colors.text, style = AgentTheme.type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MetaText("${doc.savedBy} · ${doc.savedAgo}")
                }
            }
        }
    }
}

/** The doc open: reading it, editing it, merging after a conflict, or its history. */
@Composable
private fun DocArea(ui: FeedUi, docs: DocsState, phone: Boolean, modifier: Modifier) {
    val colors = AgentTheme.colors
    val open = docs.open
    Column(modifier.padding(horizontal = if (phone) 16.dp else 24.dp)) {
        if (phone) {
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.Back, "All docs") { ui.emit(FeedEvent.Docs(DocsAction.Close)) }
                MetaText("All docs", Modifier.padding(start = 2.dp), color = colors.textMuted)
            }
        }
        when {
            docs.missing != null -> Column(Modifier.padding(top = 12.dp)) {
                Text(docs.missing, color = colors.text, style = AgentTheme.type.title)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nobody has written this doc yet. You can read this channel but not add docs to it.",
                    color = colors.textMuted, style = AgentTheme.type.body,
                )
            }
            open == null -> MetaText("Opening…", Modifier.padding(top = 12.dp))
            open.viewing != null -> VersionReader(ui, open, open.viewing)
            open.conflict != null -> ConflictMerge(ui, open, open.conflict, phone)
            open.editing -> DocEditor(ui, open, phone)
            open.history != null -> HistoryList(ui, open)
            else -> DocReader(ui, open)
        }
    }
}

@Composable
private fun DocMeta(open: OpenDoc) {
    MetaText("Version ${open.version} · saved by ${open.savedBy}${if (open.savedByAgent) " (agent)" else ""} · ${open.savedAgo}")
}

@Composable
private fun Problem(open: OpenDoc) {
    open.problem?.let {
        MetaText(it, Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = AgentTheme.colors.error)
    }
}

/** The doc as it reads, with its links, and the docs that link to it. */
@Composable
private fun DocReader(ui: FeedUi, open: OpenDoc) {
    val colors = AgentTheme.colors
    var confirmDelete by remember(open.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(open.title, Modifier.weight(1f).semantics { heading() }, color = colors.text, style = AgentTheme.type.title)
            if (open.canEdit) AppButton("Edit", ButtonTone.Secondary, icon = Icons.Pencil) { ui.emit(FeedEvent.Docs(DocsAction.Edit(true))) }
            IconAction(Icons.Clock, "History", Modifier.padding(start = 4.dp)) { ui.emit(FeedEvent.Docs(DocsAction.History(true))) }
            if (open.canEdit) IconAction(Icons.Trash, "Delete doc", Modifier.padding(start = 2.dp)) { confirmDelete = true }
        }
        DocMeta(open)
        if (!open.canEdit) MetaText("You can read this channel but not edit its docs.", color = colors.textMuted)
        Problem(open)
        Spacer(Modifier.height(12.dp))
        if (open.body.isBlank()) MetaText("This doc is empty.", color = colors.textMuted) else MarkdownBody(open.body)
        Backlinks(ui, open)
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.text,
            textContentColor = colors.textMuted,
            shape = AgentTheme.shapes.large,
            title = { Text("Delete “${open.title}”?", style = AgentTheme.type.title) },
            text = { Text("It goes for everyone in the channel, with its history.", style = AgentTheme.type.body) },
            dismissButton = { AppButton("Cancel", ButtonTone.Secondary) { confirmDelete = false } },
            confirmButton = { AppButton("Delete", ButtonTone.Destructive) { confirmDelete = false; ui.emit(FeedEvent.Docs(DocsAction.Delete)) } },
        )
    }
}

@Composable
private fun Backlinks(ui: FeedUi, open: OpenDoc) {
    val colors = AgentTheme.colors
    Spacer(Modifier.height(20.dp))
    SectionLabel("Linked from")
    Spacer(Modifier.height(6.dp))
    if (open.backlinks.isEmpty()) {
        MetaText("No other doc links here yet. Link one with [[${open.title}]].", color = colors.textMuted)
        return
    }
    open.backlinks.forEach { link ->
        val shape = AgentTheme.shapes.small
        Row(
            Modifier.clip(shape).pressable("Open ${link.title}", shape) { ui.emit(FeedEvent.Docs(DocsAction.Open(link.id))) }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Link, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
            Text(link.title, Modifier.padding(start = 6.dp), color = colors.accent, style = AgentTheme.type.label)
        }
    }
}

/**
 * Editing: the title, the markdown, and a live preview of it. Saves name the version the editing
 * started from, so a save someone else beat asks to merge. Cmd+S or Ctrl+S saves.
 */
@OptIn(FlowPreview::class)
@Composable
private fun DocEditor(ui: FeedUi, open: OpenDoc, phone: Boolean) {
    val colors = AgentTheme.colors
    // The words stay while the dashboard answers; a new doc or a fresh edit starts from the doc.
    val base = remember(open.id) { open.version }
    val title = remember(open.id) { TextFieldState(open.title) }
    val body = remember(open.id) { TextFieldState(open.body) }
    var preview by remember(open.id) { mutableStateOf(open.body) }
    LaunchedEffect(body) { snapshotFlow { body.text.toString() }.debounce(200).collect { preview = it } }
    var tab by remember { mutableStateOf("Write") }
    val save = { if (!open.saving) ui.emit(FeedEvent.Docs(DocsAction.Save(title.text.toString(), body.text.toString(), base))) }
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.S && (e.isMetaPressed || e.isCtrlPressed)) { save(); true } else false
    }
    Column(Modifier.fillMaxSize().then(keys)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { BoxedField("Title", title, "Doc title", "Title") }
            Spacer(Modifier.width(10.dp))
            AppButton(if (open.saving) "Saving…" else "Save", ButtonTone.Primary, enabled = !open.saving && title.text.isNotBlank()) { save() }
            AppButton("Cancel", ButtonTone.Quiet) { ui.emit(FeedEvent.Docs(DocsAction.Edit(false))) }
        }
        open.newerVersion?.let {
            MetaText(
                "Someone saved version $it while you were editing. Saving will show both, to merge.",
                Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.waiting,
            )
        }
        Problem(open)
        MetaText("Markdown. Link another doc with [[Doc name]] or [[Doc name|other words]].", Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        if (phone) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Write", selected = tab == "Write") { tab = "Write" }
                Chip("Preview", selected = tab == "Preview") { tab = "Preview" }
            }
            Spacer(Modifier.height(8.dp))
            if (tab == "Write") EditorBox(body, Modifier.weight(1f).fillMaxWidth()) else PreviewBox(preview, Modifier.weight(1f).fillMaxWidth())
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorBox(body, Modifier.weight(1f).fillMaxHeight())
                PreviewBox(preview, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EditorBox(state: TextFieldState, modifier: Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Box(modifier.clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape).padding(12.dp).verticalScroll(rememberScrollState())) {
        TextInput(state, "Doc text", "Write in markdown…", Modifier.fillMaxWidth(), multiline = true, minLines = 12, style = AgentTheme.type.code)
    }
}

@Composable
private fun PreviewBox(markdown: String, modifier: Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(modifier.clip(shape).border(1.dp, colors.border, shape).padding(12.dp).verticalScroll(rememberScrollState()).semantics { paneTitle = "Preview" }) {
        MetaText("PREVIEW", style = AgentTheme.type.overline)
        Spacer(Modifier.height(6.dp))
        if (markdown.isBlank()) MetaText("Nothing to show yet.", color = colors.textMuted) else MarkdownBody(markdown)
    }
}

/**
 * Someone saved first: their text and this person's, side by side (one above the other on a
 * phone), and a box to merge them in, which saves on top of theirs.
 */
@Composable
private fun ConflictMerge(ui: FeedUi, open: OpenDoc, conflict: DocConflict, phone: Boolean) {
    val colors = AgentTheme.colors
    val merged = remember(conflict) { TextFieldState(conflict.mine) }
    val title = remember(conflict) { TextFieldState(conflict.mineTitle.ifBlank { conflict.theirsTitle }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("${conflict.theirsBy} saved this doc while you were editing", Modifier.semantics { heading() }, color = colors.text, style = AgentTheme.type.title)
        MetaText(
            "Nothing was overwritten. Their version ${conflict.theirsVersion} is on the left, yours on the right. Merge them below, then save.",
            Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.textMuted,
        )
        Problem(open)
        Spacer(Modifier.height(10.dp))
        val sides = @Composable { m: Modifier ->
            TextPanel("Theirs (version ${conflict.theirsVersion})", conflict.theirs, m)
            TextPanel("Yours", conflict.mine, m)
        }
        if (phone) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { sides(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { sides(Modifier.weight(1f)) }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Merged")
        Spacer(Modifier.height(6.dp))
        BoxedField("Title", title, "Doc title", "Title")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Start from theirs", selected = false) { merged.setTextAndPlaceCursorAtEnd(conflict.theirs) }
            Chip("Start from yours", selected = false) { merged.setTextAndPlaceCursorAtEnd(conflict.mine) }
        }
        Spacer(Modifier.height(6.dp))
        EditorBox(merged, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp))
        Row(Modifier.padding(top = 10.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(if (open.saving) "Saving…" else "Save merged", ButtonTone.Primary, enabled = !open.saving) {
                ui.emit(FeedEvent.Docs(DocsAction.Save(title.text.toString(), merged.text.toString(), conflict.theirsVersion)))
            }
            AppButton("Drop mine, keep theirs", ButtonTone.Secondary) {
                ui.emit(FeedEvent.Docs(DocsAction.DismissConflict))
                ui.emit(FeedEvent.Docs(DocsAction.Edit(false)))
                ui.emit(FeedEvent.Docs(DocsAction.Open(open.id)))
            }
        }
    }
}

@Composable
private fun TextPanel(label: String, text: String, modifier: Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(modifier.clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape).padding(12.dp).heightIn(max = 360.dp)) {
        MetaText(label.uppercase(), style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.verticalScroll(rememberScrollState())) {
            SelectableText(Modifier) { Text(text.ifEmpty { "(empty)" }, color = colors.text, style = AgentTheme.type.code) }
        }
    }
}

/** Every kept version, newest first, each saying who saved it. */
@Composable
private fun HistoryList(ui: FeedUi, open: OpenDoc) {
    val colors = AgentTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History of “${open.title}”", Modifier.weight(1f).semantics { heading() }, color = colors.text, style = AgentTheme.type.title)
            AppButton("Done", ButtonTone.Secondary) { ui.emit(FeedEvent.Docs(DocsAction.History(false))) }
        }
        MetaText("Each save is kept, with who saved it. Open one to read it or put it back.", color = colors.textMuted)
        Problem(open)
        Spacer(Modifier.height(8.dp))
        val history = open.history.orEmpty()
        if (history.isEmpty()) MetaText("Reading its history…")
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(history, key = { it.version }) { v ->
                val shape = AgentTheme.shapes.medium
                val restored = v.restoredFrom?.let { " · restored version $it" } ?: ""
                val now = if (v.version == open.version) " · now" else ""
                Column(
                    Modifier.fillMaxWidth().clip(shape)
                        .pressable("Version ${v.version}, saved by ${v.savedBy} ${v.savedAgo}$restored", shape) { ui.emit(FeedEvent.Docs(DocsAction.ViewVersion(v.version))) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("Version ${v.version}$now", color = colors.text, style = AgentTheme.type.label)
                    MetaText("${v.savedBy}${if (v.savedByAgent) " (agent)" else ""} · ${v.savedAgo}$restored")
                }
            }
        }
    }
}

/** One old version, as it read then, with Restore. */
@Composable
private fun VersionReader(ui: FeedUi, open: OpenDoc, v: ViewedVersion) {
    val colors = AgentTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.title, Modifier.semantics { heading() }, color = colors.text, style = AgentTheme.type.title)
                MetaText("Version ${v.version} · saved by ${v.savedBy} · ${v.savedAgo}")
            }
            if (open.canEdit && v.version != open.version) {
                AppButton(if (open.saving) "Restoring…" else "Restore this version", ButtonTone.Primary, enabled = !open.saving) {
                    ui.emit(FeedEvent.Docs(DocsAction.Restore(v.version)))
                }
            }
            AppButton("Back", ButtonTone.Quiet) { ui.emit(FeedEvent.Docs(DocsAction.ViewVersion(null))) }
        }
        Problem(open)
        Spacer(Modifier.height(12.dp))
        if (v.body.isBlank()) MetaText("This version is empty.", color = colors.textMuted) else MarkdownBody(v.body)
        Spacer(Modifier.height(24.dp))
    }
}
