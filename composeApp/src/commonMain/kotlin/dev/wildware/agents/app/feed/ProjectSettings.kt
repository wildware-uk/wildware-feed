package dev.wildware.agents.app.feed

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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.ProjectColours
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable

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
 * Making a project, or one project's settings: its name, what it is for, its colours, and archiving
 * it. Names and descriptions are saved with a button and wait for the dashboard's say, as a name can
 * be refused; colours and archiving take effect as they are picked.
 */
@Composable
fun ProjectDialogView(ui: FeedUi, dialog: ProjectDialog) {
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
            typed.isEmpty() -> problem = "A project needs a name."
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
        title = { Text(if (project == null) "New project" else "Project settings", style = AgentTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BoxedField("Name", name, "Project name", "garden-game", onSubmit = save)
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
                    AppButton(
                        if (project.archived) "Unarchive project" else "Archive project",
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
                    project == null -> "Create project"
                    else -> "Save"
                },
                ButtonTone.Primary,
                enabled = !busy,
                onClick = save,
            )
        },
    )
}

@Composable
private fun BoxedField(
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

