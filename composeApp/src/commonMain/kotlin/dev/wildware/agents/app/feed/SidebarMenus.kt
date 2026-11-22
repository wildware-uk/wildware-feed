package dev.wildware.agents.app.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/*
 * Right-click menus (a hold, on a phone) for what the sidebar lists: a channel, a project and an
 * agent. Each offers only what this person may do there: a member can open, read, mute and copy, and
 * only an owner or admin sees Move, Rename, Archive and Delete. On a desktop the menu opens at the
 * pointer; on a phone it is a sheet from the bottom. The keyboard opens it with Shift+F10 or the Menu
 * key, and screen readers get it as an action on the row.
 */

/** What a sidebar menu is open on. */
@Immutable
sealed interface SideTarget {
    data class Channel(val id: String) : SideTarget
    data class Group(val id: String) : SideTarget
    data class Agent(val id: String) : SideTarget
}

/** A sidebar menu, open on [target]; [at] is where the pointer was, in the row's own coordinates. */
@Immutable
data class SideMenu(val target: SideTarget, val at: Offset = Offset.Zero)

/** One line of a sidebar menu. */
@Immutable
internal class SideRow(val label: String, val icon: ImageVector, val destructive: Boolean = false, val run: () -> Unit)

/** Tells one "Message" draft from the next, so the same one twice still reaches the box. */
private var draftSerial = 0L

/** The address of channel [slug] on the open team's dashboard, as its web page has it. */
@Composable
internal fun rememberChannelLink(): (String) -> String {
    val server = LocalTeams.current?.active?.server?.trimEnd('/').orEmpty()
    return { slug -> "$server/projects/$slug" }
}

/** Every line of the menu on [target], as this person may use them. [close] shuts the drawer, on a phone. */
internal fun sideMenuRows(
    ui: FeedUi, target: SideTarget, folds: CardFolds?, copy: (String) -> Unit, link: (String) -> String, close: () -> Unit,
): List<SideRow> {
    val state = ui.state
    val may = state.mayArrange
    return when (target) {
        is SideTarget.Channel -> buildList {
            val p = ui.projects.firstOrNull { it.id == target.id } ?: return@buildList
            val slug = p.slug ?: return@buildList
            add(SideRow("Open", Icons.Hash) { ui.emit(FeedEvent.SelectProject(slug)); close() })
            if (p.unread > 0) add(SideRow("Mark read", Icons.Check) { ui.emit(FeedEvent.MarkChannelRead(p.id)) })
            val follows = state.follows
            if (follows.level(p.id) == NotifyLevel.Muted) {
                // Back to its project's level, unless that is what mutes it: then on for everything.
                val groupMuted = p.groupId?.let(follows::groupLevel) == NotifyLevel.Muted
                val back = if (follows.ownLevel(p.id) == NotifyLevel.Muted && !groupMuted) null else NotifyLevel.All
                add(SideRow("Unmute", Icons.Bell) { ui.emit(FeedEvent.SetProjectNotify(p.id, slug, back)) })
            } else {
                add(SideRow("Mute", Icons.BellOff) { ui.emit(FeedEvent.SetProjectNotify(p.id, slug, NotifyLevel.Muted)) })
            }
            if (may && !p.archived) add(SideRow("Move to project…", Icons.Inbox) { ui.projectDialog = ProjectDialog.MoveChannel(p.id) })
            if (may) add(SideRow("Rename…", Icons.Pencil) { ui.projectDialog = ProjectDialog.Rename(p.id, group = false) })
            add(SideRow("Channel settings", Icons.Settings) { ui.projectDialog = ProjectDialog.Settings(p.id) })
            // Permissions… goes here (the channel-permissions change adds it).
            add(SideRow("Copy link", Icons.Link) { copy(link(slug)); ui.toast = "Link copied" })
            if (may) add(SideRow(if (p.archived) "Unarchive" else "Archive", Icons.Inbox) { ui.emit(FeedEvent.ArchiveProject(slug, !p.archived)) })
        }
        is SideTarget.Group -> buildList {
            val g = ui.groups.firstOrNull { it.id == target.id } ?: return@buildList
            val folded = folds?.isFolded(groupFoldKey(g.id)) == true
            if (may) add(SideRow("Rename…", Icons.Pencil) { ui.projectDialog = ProjectDialog.Rename(g.id, group = true) })
            if (may) add(SideRow("New channel here", Icons.Plus) { ui.projectDialog = ProjectDialog.NewChannelIn(g.id) })
            if (folds != null) add(SideRow(if (folded) "Expand" else "Collapse", if (folded) Icons.ChevronDown else Icons.ChevronRight) { folds.set(groupFoldKey(g.id), !folded) })
            if (state.follows.groupLevel(g.id) == NotifyLevel.Muted) {
                add(SideRow("Unmute all", Icons.Bell) { ui.emit(FeedEvent.SetGroupNotify(g.id, g.slug, null)) })
            } else {
                add(SideRow("Mute all", Icons.BellOff) { ui.emit(FeedEvent.SetGroupNotify(g.id, g.slug, NotifyLevel.Muted)) })
            }
            add(SideRow("Project settings", Icons.Settings) { ui.projectDialog = ProjectDialog.GroupSettings(g.id) })
            // Permissions… goes here (the channel-permissions change adds it).
            if (may) add(SideRow("Delete project…", Icons.Trash, destructive = true) { ui.projectDialog = ProjectDialog.DeleteGroup(g.id) })
        }
        is SideTarget.Agent -> buildList {
            val a = state.agents.firstOrNull { it.id == target.id } ?: return@buildList
            add(SideRow("Message", Icons.At) {
                ui.incoming = SharedDraft("@${a.name} ", emptyList(), ++draftSerial)
                close()
            })
            val slug = state.current.slug
            val members = slug?.let { state.members[memberTarget(it, group = false)] }?.takeIf { it.loaded && it.busy == null }
            val member = members?.members?.firstOrNull { it.agentId == a.id }
            if (slug != null && member != null && may) {
                if (member.hears) {
                    add(SideRow("Remove from this channel", Icons.BellOff) { ui.emit(FeedEvent.SetMember(slug, false, a.id, hears = false)) })
                } else {
                    add(SideRow("Add to this channel", Icons.Bell) { ui.emit(FeedEvent.SetMember(slug, false, a.id, hears = true)) })
                }
            }
            add(SideRow("Settings", Icons.Settings) { ui.agentOpen = a.id; close() })
        }
    }
}

/** What a menu is about, for its title: the channel's, project's or agent's name. */
private fun FeedUi.nameOf(target: SideTarget): String = when (target) {
    is SideTarget.Channel -> projects.firstOrNull { it.id == target.id }?.name
    is SideTarget.Group -> groups.firstOrNull { it.id == target.id }?.name
    is SideTarget.Agent -> state.agents.firstOrNull { it.id == target.id }?.name
}.orEmpty()

@Suppress("DEPRECATION")
@Composable
private fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return { text -> clipboard.setText(AnnotatedString(text)) }
}

/**
 * A row of the sidebar that can open a menu: on a desktop the menu opens here, at the pointer.
 * [modifier] is the row's, so the drag and the keys reach it.
 */
@Composable
internal fun SideMenuAnchor(ui: FeedUi, target: SideTarget, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val phone = LocalIsPhone.current
    var height by remember { mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { height = it.height }) {
        content()
        val open = ui.sideMenu
        if (!phone && open != null && open.target == target) SideDropdown(ui, open, height)
    }
}

/** The desktop menu: opens at the pointer (below the row from a key), closes on Escape or a click outside. */
@Composable
private fun SideDropdown(ui: FeedUi, menu: SideMenu, anchorHeightPx: Int) {
    val colors = AgentTheme.colors
    val copy = rememberCopy()
    val link = rememberChannelLink()
    val density = LocalDensity.current
    val rows = sideMenuRows(ui, menu.target, LocalCardFolds.current, copy, link) {}
    val offset = with(density) {
        if (menu.at == Offset.Zero) DpOffset(8.dp, 0.dp) else DpOffset(menu.at.x.toDp(), (menu.at.y - anchorHeightPx).toDp())
    }
    DropdownMenu(
        expanded = true,
        onDismissRequest = { ui.sideMenu = null },
        offset = offset,
        shape = AgentTheme.shapes.medium,
        containerColor = colors.surfaceRaised,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.semantics { paneTitle = "Actions for ${ui.nameOf(menu.target)}" },
    ) {
        rows.forEach { row ->
            DropdownMenuItem(
                text = { Text(row.label, style = AgentTheme.type.label) },
                leadingIcon = { Icon(row.icon, null, modifier = Modifier.size(16.dp)) },
                onClick = {
                    ui.sideMenu = null
                    row.run()
                },
                colors = MenuDefaults.itemColors(
                    textColor = if (row.destructive) colors.error else colors.text,
                    leadingIconColor = if (row.destructive) colors.error else colors.textMuted,
                ),
            )
        }
    }
}

/** The phone menu: a sheet from the bottom, over the drawer. Tap outside or Cancel to close it. */
@Composable
fun SideSheet(ui: FeedUi, menu: SideMenu, safeArea: SafeArea, sheetModifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val copy = rememberCopy()
    val link = rememberChannelLink()
    val closeDrawer = { ui.drawer.snap(false) }
    val rows = sideMenuRows(ui, menu.target, LocalCardFolds.current, copy, link, closeDrawer)
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close menu",
                ) { ui.sideMenu = null },
        )
        Column(
            sheetModifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .semantics { paneTitle = "Actions for ${ui.nameOf(menu.target)}" }
                .safePadding(safeArea, bottom = 0.dp)
                .padding(top = 12.dp),
        ) {
            Text(
                ui.nameOf(menu.target),
                Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                color = colors.textMuted,
                style = AgentTheme.type.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            rows.forEach { row ->
                SheetRow(row.label, row.icon, row.destructive) {
                    ui.sideMenu = null
                    row.run()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            SheetRow("Cancel", Icons.Close, false) { ui.sideMenu = null }
        }
    }
}

/**
 * The small dialogs the sidebar's menus open: moving a channel to a project, renaming a channel or
 * a project, and deleting a project, which asks first.
 */
@Composable
internal fun SidebarDialogView(ui: FeedUi, dialog: ProjectDialog) {
    when (dialog) {
        is ProjectDialog.MoveChannel -> MoveChannelDialog(ui, dialog)
        is ProjectDialog.Rename -> RenameDialog(ui, dialog)
        is ProjectDialog.DeleteGroup -> DeleteGroupDialog(ui, dialog)
        else -> Unit
    }
}

@Composable
private fun MoveChannelDialog(ui: FeedUi, dialog: ProjectDialog.MoveChannel) {
    val p = ui.projects.firstOrNull { it.id == dialog.channelId } ?: return run { ui.projectDialog = null }
    val colors = AgentTheme.colors
    val close = { ui.projectDialog = null }
    val groups = ui.channelSections.groups.map { it.first }
    val now = p.groupId?.takeIf { id -> groups.any { it.id == id } }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text("Move ${p.name} to…", style = AgentTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                RadioRow("No project", selected = now == null, enabled = true) {
                    if (now != null) ui.placeChannel(p, null, Int.MAX_VALUE)
                    close()
                }
                groups.forEach { g ->
                    RadioRow(g.name, selected = now == g.id, enabled = true) {
                        if (now != g.id) ui.placeChannel(p, g.id, Int.MAX_VALUE)
                        close()
                    }
                }
                if (groups.isEmpty()) {
                    Text("No projects yet. Make one with New project.", Modifier.padding(top = 8.dp), style = AgentTheme.type.meta, color = colors.textFaint)
                }
            }
        },
        confirmButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
    )
}

@Composable
private fun RenameDialog(ui: FeedUi, dialog: ProjectDialog.Rename) {
    val channel = if (dialog.group) null else ui.projects.firstOrNull { it.id == dialog.id }
    val group = if (dialog.group) ui.groups.firstOrNull { it.id == dialog.id } else null
    val current = channel?.name ?: group?.name ?: return run { ui.projectDialog = null }
    val colors = AgentTheme.colors
    val name = remember(dialog) { TextFieldState(current) }
    var problem by remember(dialog) { mutableStateOf<String?>(null) }
    var busy by remember(dialog) { mutableStateOf(false) }
    val close = { ui.projectDialog = null }
    val save = {
        val typed = name.text.toString().trim()
        val done: (String?) -> Unit = { refused ->
            busy = false
            if (refused == null) close() else problem = refused
        }
        when {
            typed.isEmpty() -> problem = if (dialog.group) "A project needs a name." else "A channel needs a name."
            typed == current -> close()
            busy -> Unit
            channel?.slug != null -> {
                busy = true
                ui.emit(FeedEvent.EditProject(channel.slug, typed, channel.description, done))
            }
            group != null -> {
                busy = true
                ui.emit(FeedEvent.RenameGroup(group.slug, typed, done))
            }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text(if (dialog.group) "Rename project" else "Rename channel", style = AgentTheme.type.title) },
        text = {
            Column {
                BoxedField("Name", name, if (dialog.group) "Project name" else "Channel name", current, onSubmit = save)
                problem?.let { Text(it, Modifier.padding(top = 10.dp), color = colors.error, style = AgentTheme.type.label) }
            }
        },
        dismissButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
        confirmButton = { AppButton(if (busy) "Saving…" else "Rename", ButtonTone.Primary, enabled = !busy, onClick = save) },
    )
}

@Composable
private fun DeleteGroupDialog(ui: FeedUi, dialog: ProjectDialog.DeleteGroup) {
    val g = ui.groups.firstOrNull { it.id == dialog.groupId } ?: return run { ui.projectDialog = null }
    val colors = AgentTheme.colors
    val count = ui.projects.count { it.groupId == g.id }
    val close = { ui.projectDialog = null }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text("Delete ${g.name}?", style = AgentTheme.type.title) },
        text = {
            Text(
                when (count) {
                    0 -> "It has no channels. Nothing else is deleted."
                    1 -> "Its channel moves to Channels, in no project. Nothing in it is deleted."
                    else -> "Its $count channels move to Channels, in no project. Nothing in them is deleted."
                },
                style = AgentTheme.type.body,
            )
        },
        dismissButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
        confirmButton = {
            AppButton("Delete project", ButtonTone.Destructive) {
                ui.emit(FeedEvent.DeleteGroup(g.slug))
                close()
            }
        },
    )
}
