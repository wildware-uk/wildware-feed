package dev.wildware.agents.app.feed

import androidx.compose.foundation.selection.selectableGroup
import dev.wildware.agents.NotifyLevel
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.ProjectColours
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable
import androidx.compose.foundation.layout.width

/** A colour the owner can give a project, by name. */
private data class Swatch(val name: String, val hex: String)

/** Page colours: a few dark and a few light, each with text that reads on it. */
private val PageSwatches = listOf(
    Swatch("Harbour", "#0f3d3e"),
    Swatch("Night", "#14182b"),
    Swatch("Plum", "#2b1631"),
    Swatch("Forest", "#17301f"),
    Swatch("Sand", "#f3ead8"),
    Swatch("Mist", "#e8eef3"),
    Swatch("Rose", "#f7e1e4"),
)

private val AccentSwatches = listOf(
    Swatch("Coral", "#ff7a59"),
    Swatch("Lime", "#c8f26b"),
    Swatch("Sky", "#5ab4ff"),
    Swatch("Gold", "#f5b83d"),
    Swatch("Violet", "#a78bfa"),
    Swatch("Mint", "#3ddc97"),
)

/**
 * Making a channel, or one channel's settings: its name, what it is for, its colours, its project,
 * and archiving it. Names and descriptions are saved with a button and wait for the dashboard's say,
 * as a name can be refused; colours, the project and archiving take effect as they are picked.
 * (A project, a group of channels, has its own dialog: [GroupDialogView].)
 */
@Composable
fun ProjectDialogView(ui: FeedUi, dialog: ProjectDialog) {
    if (dialog is ProjectDialog.NewGroup || dialog is ProjectDialog.GroupSettings) return GroupDialogView(ui, dialog)
    val project = (dialog as? ProjectDialog.Settings)?.let { s ->
        ui.projects.firstOrNull { it.id == s.projectId } ?: ui.state.current.takeIf { it.id == s.projectId }
    }
    if (dialog is ProjectDialog.Settings && project?.slug == null) return run { ui.projectDialog = null }
    val colors = AgentTheme.colors
    val name = remember(dialog) { TextFieldState(project?.name.orEmpty()) }
    val description = remember(dialog) { TextFieldState(project?.description.orEmpty()) }
    var problem by remember(dialog) { mutableStateOf<String?>(null) }
    var busy by remember(dialog) { mutableStateOf(false) }
    var page by remember(dialog) { mutableStateOf(project?.background) }
    var accent by remember(dialog) { mutableStateOf(project?.accent) }
    val close = { ui.projectDialog = null }
    val done: (String?) -> Unit = { refused ->
        busy = false
        if (refused == null) close() else problem = refused
    }
    val save = {
        val typed = name.text.toString().trim()
        when {
            typed.isEmpty() -> problem = "A channel needs a name."
            !busy -> {
                busy = true
                problem = null
                val about = description.text.toString().trim().ifEmpty { null }
                ui.emit(
                    if (project?.slug == null) FeedEvent.CreateProject(typed, about, done)
                    else FeedEvent.EditProject(project.slug, typed, about, done),
                )
            }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text(if (project == null) "New channel" else "Channel settings", style = AgentTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BoxedField("Name", name, "Channel name", "garden-game", onSubmit = save)
                Spacer(Modifier.height(12.dp))
                BoxedField("What it's for", description, "What it's for", "Optional", multiline = true)
                problem?.let { Text(it, Modifier.padding(top = 10.dp), color = colors.error, style = AgentTheme.type.label) }
                if (project?.slug != null) {
                    val slug = project.slug
                    Spacer(Modifier.height(18.dp))
                    SwatchRow("Page colour", PageSwatches, page) {
                        page = it
                        ui.emit(FeedEvent.SetProjectColours(slug, page, accent))
                    }
                    Spacer(Modifier.height(12.dp))
                    SwatchRow("Accent colour", AccentSwatches, accent) {
                        accent = it
                        ui.emit(FeedEvent.SetProjectColours(slug, page, accent))
                    }
                    Spacer(Modifier.height(18.dp))
                    LogoPicker(ui, project, slug)
                    Spacer(Modifier.height(18.dp))
                    GroupPicker(ui, project, slug)
                    Spacer(Modifier.height(18.dp))
                    NotifyPicker(ui, project, slug)
                    Spacer(Modifier.height(18.dp))
                    AppButton(
                        if (project.pinned) "Unpin channel" else "Pin channel",
                        ButtonTone.Secondary,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Pin,
                    ) {
                        ui.emit(FeedEvent.PinProject(slug, !project.pinned))
                        close()
                    }
                    Spacer(Modifier.height(8.dp))
                    AppButton(
                        if (project.archived) "Unarchive channel" else "Archive channel",
                        ButtonTone.Secondary,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Inbox,
                    ) {
                        ui.emit(FeedEvent.ArchiveProject(slug, !project.archived))
                        close()
                    }
                    // Wraps, unlike MetaText: a phone's dialog is too narrow for it on one line.
                    Text(
                        if (project.archived) "It goes back among the others." else "It moves under Archived. Nothing in it is deleted.",
                        Modifier.padding(top = 6.dp),
                        color = colors.textFaint,
                        style = AgentTheme.type.meta,
                    )
                }
            }
        },
        dismissButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
        confirmButton = {
            AppButton(
                when {
                    busy -> "Saving…"
                    project == null -> "Create channel"
                    else -> "Save"
                },
                ButtonTone.Primary,
                enabled = !busy,
                onClick = save,
            )
        },
    )
}

/** What each notification level is called, and what it lets through. */
internal val NotifyChoices = listOf(
    NotifyLevel.All to "All activity",
    NotifyLevel.Replies to "Replies and mentions",
    NotifyLevel.Muted to "Muted",
)

internal fun notifyHint(level: NotifyLevel): String = when (level) {
    NotifyLevel.All -> "Every new card, reply and question alerts you."
    NotifyLevel.Replies -> "Only questions, replies to you, and messages that name you."
    NotifyLevel.Muted -> "Nothing, except critical alerts and threads you follow."
}

/**
 * How much of the channel alerts the owner, on every device: the bell, pushes and desktop notices.
 * Each device still picks which kinds of alert it takes. A thread followed or unfollowed by hand
 * keeps that choice whatever the level. A channel in a project can follow the project's setting.
 */
@Composable
private fun NotifyPicker(ui: FeedUi, project: Project, slug: String) {
    val colors = AgentTheme.colors
    val level = ui.follows.level(project.id)
    val own = ui.follows.ownLevel(project.id)
    val group = project.groupId?.let { id -> ui.state.groups.firstOrNull { it.id == id } }
    Column {
        Text("Notifications", color = colors.text, style = AgentTheme.type.label)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.selectableGroup()) {
            if (group != null) {
                val inherited = NotifyChoices.first { it.first == (ui.follows.groupLevel(group.id) ?: NotifyLevel.All) }.second
                RadioRow("Same as ${group.name} ($inherited)", selected = own == null, enabled = true) {
                    if (own != null) ui.emit(FeedEvent.SetProjectNotify(project.id, slug, null))
                }
            }
            for ((choice, label) in NotifyChoices) {
                RadioRow(label, selected = (group == null || own != null) && level == choice, enabled = true) {
                    if (own != choice) ui.emit(FeedEvent.SetProjectNotify(project.id, slug, choice))
                }
            }
        }
        Text(notifyHint(level), Modifier.padding(top = 2.dp), color = colors.textFaint, style = AgentTheme.type.meta)
    }
}

/** Which project (group of channels) the channel is in, or none. Takes effect as it is picked. */
@Composable
private fun GroupPicker(ui: FeedUi, project: Project, slug: String) {
    val colors = AgentTheme.colors
    val groups = ui.state.groups.filter { !it.archived || it.id == project.groupId }
    Column {
        Text("Project", color = colors.text, style = AgentTheme.type.label)
        Spacer(Modifier.height(4.dp))
        if (groups.isEmpty()) {
            Text("No projects yet. Make one to group channels together.", color = colors.textFaint, style = AgentTheme.type.meta)
        } else {
            Column(Modifier.selectableGroup()) {
                RadioRow("None", selected = project.groupId == null, enabled = true) {
                    if (project.groupId != null) ui.emit(FeedEvent.MoveChannel(slug, null))
                }
                for (g in groups) {
                    RadioRow(g.name, selected = project.groupId == g.id, enabled = true) {
                        if (project.groupId != g.id) ui.emit(FeedEvent.MoveChannel(slug, g.id))
                    }
                }
            }
        }
    }
}

/**
 * Making a project (a named group of channels), or one project's settings: its name, colour,
 * channels and notifications, archiving it, and deleting it, which only ungroups its channels.
 */
@Composable
private fun GroupDialogView(ui: FeedUi, dialog: ProjectDialog) {
    val group = (dialog as? ProjectDialog.GroupSettings)?.let { s -> ui.state.groups.firstOrNull { it.id == s.groupId } }
    if (dialog is ProjectDialog.GroupSettings && group == null) return run { ui.projectDialog = null }
    val colors = AgentTheme.colors
    val name = remember(dialog) { TextFieldState(group?.name.orEmpty()) }
    var problem by remember(dialog) { mutableStateOf<String?>(null) }
    var busy by remember(dialog) { mutableStateOf(false) }
    var accent by remember(dialog) { mutableStateOf(group?.accent) }
    var confirmDelete by remember(dialog) { mutableStateOf(false) }
    val close = { ui.projectDialog = null }
    val save = {
        val typed = name.text.toString().trim()
        when {
            typed.isEmpty() -> problem = "A project needs a name."
            group != null && typed == group.name -> close()
            !busy -> {
                busy = true
                problem = null
                val done: (String?) -> Unit = { refused ->
                    busy = false
                    if (refused == null) close() else problem = refused
                }
                ui.emit(if (group == null) FeedEvent.CreateGroup(typed, done) else FeedEvent.RenameGroup(group.slug, typed, done))
            }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text(if (group == null) "New project" else "Project settings", style = AgentTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BoxedField("Name", name, "Project name", "Garden game", onSubmit = save)
                if (group == null) {
                    Text(
                        "A project groups channels. Add channels to it here once it is made, or from a channel's settings.",
                        Modifier.padding(top = 8.dp),
                        color = colors.textFaint,
                        style = AgentTheme.type.meta,
                    )
                }
                problem?.let { Text(it, Modifier.padding(top = 10.dp), color = colors.error, style = AgentTheme.type.label) }
                if (group != null) {
                    Spacer(Modifier.height(18.dp))
                    SwatchRow("Colour", AccentSwatches, accent) {
                        accent = it
                        ui.emit(FeedEvent.SetGroupAccent(group.slug, it))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Channels", color = colors.text, style = AgentTheme.type.label)
                    Spacer(Modifier.height(4.dp))
                    val channels = ui.projects.filter { !it.archived || it.groupId == group.id }
                    if (channels.isEmpty()) MetaText("No channels yet.")
                    for (c in channels) {
                        val inHere = c.groupId == group.id
                        val elsewhere = c.groupId?.takeIf { !inHere }?.let { id -> ui.state.groups.firstOrNull { it.id == id }?.name }
                        CheckRow(c.name + (elsewhere?.let { "  ·  in $it" } ?: ""), inHere, enabled = true) {
                            c.slug?.let { ui.emit(FeedEvent.MoveChannel(it, if (inHere) null else group.id)) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    GroupNotifyPicker(ui, group)
                    Spacer(Modifier.height(18.dp))
                    AppButton(
                        if (group.archived) "Unarchive project" else "Archive project",
                        ButtonTone.Secondary,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Inbox,
                    ) {
                        ui.emit(FeedEvent.ArchiveGroup(group.slug, !group.archived))
                        close()
                    }
                    Spacer(Modifier.height(8.dp))
                    AppButton(
                        if (confirmDelete) "Delete it: the channels stay" else "Delete project",
                        ButtonTone.Secondary,
                        Modifier.fillMaxWidth(),
                        icon = Icons.Close,
                    ) {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            ui.emit(FeedEvent.DeleteGroup(group.slug))
                            close()
                        }
                    }
                    Text(
                        "Deleting a project only ungroups its channels. Nothing in them is deleted.",
                        Modifier.padding(top = 6.dp),
                        color = colors.textFaint,
                        style = AgentTheme.type.meta,
                    )
                }
            }
        },
        dismissButton = { AppButton(if (group == null) "Cancel" else "Close", ButtonTone.Secondary, onClick = close) },
        confirmButton = {
            AppButton(
                when {
                    busy -> "Saving…"
                    group == null -> "Create project"
                    else -> "Save"
                },
                ButtonTone.Primary,
                enabled = !busy,
                onClick = save,
            )
        },
    )
}

/** How much of a project alerts the owner, for each of its channels that has no setting of its own. */
@Composable
private fun GroupNotifyPicker(ui: FeedUi, group: ProjectGroup) {
    val colors = AgentTheme.colors
    val level = ui.follows.groupLevel(group.id) ?: NotifyLevel.All
    Column {
        Text("Notifications", color = colors.text, style = AgentTheme.type.label)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.selectableGroup()) {
            for ((choice, label) in NotifyChoices) {
                RadioRow(label, selected = level == choice, enabled = true) {
                    if (level != choice) ui.emit(FeedEvent.SetGroupNotify(group.id, group.slug, choice))
                }
            }
        }
        Text(
            notifyHint(level) + " A channel with its own setting keeps it.",
            Modifier.padding(top = 2.dp),
            color = colors.textFaint,
            style = AgentTheme.type.meta,
        )
    }
}

@Composable
internal fun BoxedField(
    label: String,
    state: TextFieldState,
    spoken: String,
    placeholder: String,
    multiline: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column {
        MetaText(label.uppercase(), style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 12.dp),
        ) {
            TextInput(state, spoken, placeholder, Modifier.fillMaxWidth(), multiline = multiline, onSubmit = onSubmit)
        }
    }
}

/**
 * The project's logo, picked from the pictures already posted into it, as on the website: an owner has
 * no author to upload as, and an agent can set any picture with set_project_theme. Each pick takes
 * effect at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoPicker(ui: FeedUi, project: Project, slug: String) {
    val colors = AgentTheme.colors
    val choices = ui.state.logoChoices
    val shape = AgentTheme.shapes.small
    Column {
        MetaText("LOGO", style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(8.dp))
        if (choices.isEmpty()) {
            Text(
                "No pictures in this channel yet. Post one, or have an agent set one with set_project_theme.",
                color = colors.textFaint,
                style = AgentTheme.type.meta,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEachIndexed { i, choice ->
                    val picked = choice.mediaId == project.logoMediaId
                    AttachmentImage(
                        choice.image,
                        Modifier
                            .size(48.dp)
                            .clip(shape)
                            .border(if (picked) 2.dp else 1.dp, if (picked) colors.accent else colors.border, shape)
                            .pressable("Use picture ${i + 1} as the logo", shape, role = Role.RadioButton) {
                                ui.emit(FeedEvent.SetProjectLogo(slug, choice.mediaId))
                            }
                            .semantics { selected = picked },
                    )
                }
            }
        }
        if (project.logoMediaId != null) {
            Spacer(Modifier.height(10.dp))
            CheckRow("Use the logo instead of the name", project.logoInsteadOfName, enabled = true) {
                ui.emit(FeedEvent.SetLogoInsteadOfName(slug, !project.logoInsteadOfName))
            }
            AppButton("Remove logo", ButtonTone.Secondary, icon = Icons.Close) {
                ui.emit(FeedEvent.SetProjectLogo(slug, null))
            }
        }
    }
}

/** A label and its colours to pick from, "None" first. Picking one says so at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwatchRow(label: String, swatches: List<Swatch>, picked: String?, pick: (String?) -> Unit) {
    val colors = AgentTheme.colors
    Column {
        MetaText(label.uppercase(), style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwatchDot("$label None", null, selected = picked == null) { pick(null) }
            swatches.forEach { s ->
                SwatchDot("$label ${s.name}", s.hex, selected = picked.equals(s.hex, ignoreCase = true)) { pick(s.hex) }
            }
        }
    }
}

@Composable
private fun SwatchDot(spoken: String, hex: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val fill = ProjectColours.parse(hex)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.border, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(fill ?: colors.surfaceSunken)
            .pressable(spoken, CircleShape, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        when {
            fill == null -> Icon(Icons.Close, null, tint = colors.textFaint, modifier = Modifier.size(16.dp))
            selected -> Icon(Icons.Check, null, tint = ProjectColours.ink(fill), modifier = Modifier.size(16.dp))
            else -> Row {}
        }
    }
}

