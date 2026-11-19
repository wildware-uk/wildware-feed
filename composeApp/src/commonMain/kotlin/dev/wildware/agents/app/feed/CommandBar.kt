package dev.wildware.agents.app.feed

import dev.wildware.agents.app.crash.CrashReportSetting
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable

/**
 * An action the app around the feed adds to the command bar (the desktop's "Check for updates").
 * [keywords] also match what is typed.
 */
data class AppCommand(val title: String, val detail: String = "", val keywords: List<String> = emptyList(), val run: () -> Unit)

/** Extra actions for the command bar, from whoever hosts the feed. */
val LocalAppCommands = staticCompositionLocalOf { emptyList<AppCommand>() }

/** Opens "This computer" settings (the server the desktop app runs), or null where there are none. */
val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** One line in the command bar: something to do, somewhere to go, or something found. */
private class Command(
    val group: String,
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val run: () -> Unit,
)

/**
 * The ⌘K / Ctrl+K bar: type to search every loaded card and reply across projects, jump to a
 * project, or run an action. Up and Down move, Enter runs, Escape closes. Every line is also a
 * button, so it works by touch and with a screen reader too.
 */
@Composable
fun CommandBar(ui: FeedUi, safeArea: SafeArea, panelModifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val query = rememberTextFieldState(LocalPreview.current.command.orEmpty())
    val focus = remember { FocusRequester() }
    var highlighted by remember { mutableIntStateOf(0) }
    val close = { ui.commandOpen = false }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val extra = LocalAppCommands.current
    val crashReports = LocalCrashReports.current
    val commands by remember(extra, crashReports) {
        derivedStateOf { commandsFor(ui, query.text.toString().trim(), extra, crashReports, close) }
    }
    LaunchedEffect(query.text) { highlighted = 0 }
    val list = rememberLazyListState()
    LaunchedEffect(highlighted) { if (commands.isNotEmpty()) list.animateScrollToItem(highlighted.coerceIn(0, commands.lastIndex)) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            panelModifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 64.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Search and commands" }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> { close(); true }
                        Key.DirectionDown -> { highlighted = (highlighted + 1).coerceAtMost(commands.lastIndex.coerceAtLeast(0)); true }
                        Key.DirectionUp -> { highlighted = (highlighted - 1).coerceAtLeast(0); true }
                        Key.Enter -> { commands.getOrNull(highlighted)?.run?.invoke(); true }
                        else -> false
                    }
                },
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Search, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                TextInput(
                    query,
                    label = "Search or run a command",
                    placeholder = "Search cards and replies, jump to a channel, or run a command…",
                    modifier = Modifier.weight(1f).padding(start = 10.dp).focusRequester(focus),
                )
                MetaText("esc", Modifier.padding(start = 8.dp))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).heightIn(max = 1.dp).background(colors.border))
            if (commands.isEmpty()) {
                MetaText("Nothing matches.", Modifier.padding(16.dp), color = colors.textMuted)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp), state = list) {
                itemsIndexed(commands) { i, command ->
                    val first = i == 0 || commands[i - 1].group != command.group
                    if (first) {
                        SectionLabel(command.group, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                    }
                    CommandRow(command, highlighted = i == highlighted)
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: Command, highlighted: Boolean) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(shape)
            .background(if (highlighted) colors.text.copy(alpha = if (colors.isDark) 0.08f else 0.06f) else Color.Transparent)
            .pressable("${command.title}. ${command.detail}", shape, role = Role.Button, onClick = command.run)
            .semantics { selected = highlighted }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(command.icon, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(command.title, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (command.detail.isNotEmpty()) {
                MetaText(command.detail, color = colors.textMuted)
            }
        }
        if (highlighted) MetaText("↵")
    }
}

/** What the bar offers for [query]: actions and projects whose names match, then search results. */
private fun commandsFor(ui: FeedUi, query: String, extra: List<AppCommand>, crashReports: CrashReportSetting?, close: () -> Unit): List<Command> {
    fun done(action: () -> Unit): () -> Unit = {
        close()
        action()
    }
    val q = query.lowercase()
    fun wanted(vararg words: String) = q.isEmpty() || words.any { it.lowercase().contains(q) }
    val state = ui.state
    val actions = buildList {
        if (wanted("Mark all read")) add(Command("Actions", "Mark all read", "Clear every unread badge", Icons.Check, done { ui.emit(FeedEvent.MarkAllRead) }))
        if (wanted("New channel", "create channel")) {
            add(Command("Actions", "New channel", "Make a channel to post and work in", Icons.Plus, done { ui.projectDialog = ProjectDialog.New }))
        }
        if (wanted("New project", "create project", "group")) {
            add(Command("Actions", "New project", "Group channels together", Icons.Plus, done { ui.projectDialog = ProjectDialog.NewGroup }))
        }
        if (state.current.slug != null && wanted("Channel settings", "rename archive colour colours project")) {
            val id = state.current.id
            add(Command("Actions", "Channel settings", "Rename ${state.current.name}, set its colours, its project, or archive it", Icons.Settings, done { ui.projectDialog = ProjectDialog.Settings(id) }))
        }
        state.groups.filter { !it.archived && wanted(it.name, "project settings") }.forEach { g ->
            add(Command("Actions", "${g.name} settings", "Rename the project, colour it, pick its channels", Icons.Settings, done { ui.projectDialog = ProjectDialog.GroupSettings(g.id) }))
        }
        val postTarget = if (state.current.slug == null) ui.postProject else state.current
        if (postTarget != null && wanted("New post", "write")) {
            add(Command("Actions", "New post", "Write to everyone in ${postTarget.name}", Icons.Plus, done { ui.focusComposer() }))
        }
        if (wanted("Refresh", "reload")) add(Command("Actions", "Refresh", "Read everything again", Icons.Refresh, done { ui.emit(FeedEvent.Refresh) }))
        if (wanted("Alerts on this device", "notifications", "settings")) add(Command("Actions", "Alerts on this device", "Which alerts this device gets", Icons.Sliders, done { ui.alertsOpen = true }))
        if (wanted("Devices", "devices", "phones", "sign out", "revoke", "lost", "settings")) {
            add(Command("Actions", "Devices", "Every device signed in to this dashboard; revoke a lost one", Icons.Phone, done { ui.devicesOpen = true }))
            add(Command("Actions", "People", "Who is in the team; invite someone, change roles", Icons.People, done { ui.peopleOpen = true }))
        }
        if (state.retention.allowed && wanted("Keep media", "retention", "delete", "media", "settings")) {
            add(Command("Actions", "Keep media", "How long the dashboard keeps pictures and files", Icons.Clock, done { ui.retentionOpen = true }))
        }
        if (state.secrets.available && wanted("Secrets", "keys", "passwords", "tokens", "settings")) {
            add(Command("Actions", "Secrets", "Keys and passwords shared with agents", Icons.Lock, done { ui.secretsOpen = true }))
        }
        if (wanted("Switch theme", "dark", "light")) add(Command("Actions", "Switch light or dark", "", Icons.Moon, done { ui.emit(FeedEvent.ToggleTheme) }))
        if (crashReports != null && wanted("Crash reports", "privacy", "settings")) {
            val on = crashReports.enabled.value
            add(
                Command(
                    "Actions",
                    if (on) "Stop sending crash reports" else "Send crash reports",
                    if (on) "Crashes are reported, without your messages or names" else "Report crashes, without your messages or names",
                    Icons.Warning,
                    done { crashReports.set(!on) },
                ),
            )
        }
        extra.filter { wanted(it.title, *it.keywords.toTypedArray()) }.forEach { add(Command("Actions", it.title, it.detail, Icons.Download, done(it.run))) }
        if (wanted("Sign out")) add(Command("Actions", "Sign out", "Forget this device's key", Icons.SignOut, done { ui.emit(FeedEvent.SignOut) }))
    }
    val projects = buildList {
        if (wanted("All channels")) add(Command("Channels", "All channels", "Every channel's feed", Icons.Inbox, done { ui.emit(FeedEvent.SelectProject(null)) }))
        val groupNames = state.groups.associate { it.id to it.name }
        state.projects.filter { !it.archived && wanted(it.name, it.groupId?.let(groupNames::get).orEmpty()) }.forEach { p ->
            val where = p.groupId?.let(groupNames::get)
            val detail = listOfNotNull(where, if (p.unread > 0) "${p.unread} unread" else null).joinToString(" · ")
            add(Command("Channels", p.name, detail, Icons.Hash, done { ui.emit(FeedEvent.SelectProject(p.slug)) }))
        }
    }
    val found = if (q.isEmpty()) emptyList() else ui.searchEntries.filter { it.matches(q) }.take(25).map { hit ->
        Command(
            group = "Found",
            title = hit.title.ifBlank { hit.snippet },
            detail = listOf(hit.kind.label, hit.who, hit.projectName, hit.time).filter { it.isNotBlank() }.joinToString(" · "),
            icon = when (hit.kind) {
                SearchEntry.Kind.Card -> Icons.Hash
                SearchEntry.Kind.Reply -> Icons.Reply
                SearchEntry.Kind.Question -> Icons.Clock
            },
            run = done { ui.openResult(hit) },
        )
    }
    // Typing usually means searching, so results lead once there is a query.
    return if (q.isEmpty()) actions + projects else found + actions + projects
}
