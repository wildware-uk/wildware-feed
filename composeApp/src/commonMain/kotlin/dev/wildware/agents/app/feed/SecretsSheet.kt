package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.touchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a revealed value stays on screen, and a copied one on the clipboard. */
private const val RevealMs = 30_000L
private const val ClipboardMs = 60_000L

/** The way into Secrets from the account sheet, on a dashboard that has them. */
@Composable
internal fun SecretsEntry(ui: FeedUi) {
    val secrets = ui.state.secrets
    if (!secrets.available) return
    SettingsLinkRow(Icons.Lock, "Secrets", if (secrets.loaded && secrets.enabled) "${secrets.secrets.size}" else "") {
        ui.alertsOpen = false
        ui.secretsOpen = true
    }
}

private sealed interface SecretsPage {
    data object List : SecretsPage
    data object Add : SecretsPage
    data class Detail(val id: String) : SecretsPage
}

/**
 * The secrets the dashboard keeps for agents: API keys, passwords, tokens. An agent gets a one-time
 * link that puts the value straight into a file, never into its conversation; the owner sees names
 * here, and the value only after Face ID, Touch ID or the device's passcode, for 30 seconds.
 */
@Composable
fun SecretsSheet(ui: FeedUi, safeArea: SafeArea) {
    val preview = LocalPreview.current
    var page by remember {
        mutableStateOf(
            when {
                preview.addingSecret -> SecretsPage.Add
                preview.secretOpen != null -> SecretsPage.Detail(preview.secretOpen)
                else -> SecretsPage.List
            },
        )
    }
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadSecrets()) }
    val title = when (val p = page) {
        SecretsPage.List -> "Secrets"
        SecretsPage.Add -> "Add a secret"
        is SecretsPage.Detail -> ui.state.secrets.secrets.firstOrNull { it.id == p.id }?.name ?: "Secret"
    }
    SettingsSheet(title, safeArea, onClose = { ui.secretsOpen = false }) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            when (val p = page) {
                SecretsPage.List -> SecretList(ui, onAdd = { page = SecretsPage.Add }, onOpen = { page = SecretsPage.Detail(it) })
                SecretsPage.Add -> AddSecret(ui, onDone = { page = SecretsPage.List })
                is SecretsPage.Detail -> {
                    val row = ui.state.secrets.secrets.firstOrNull { it.id == p.id }
                    if (row == null) {
                        // Deleted, here or elsewhere.
                        LaunchedEffect(p.id) { if (ui.state.secrets.loaded) page = SecretsPage.List }
                    } else {
                        SecretDetail(ui, row, onBack = { page = SecretsPage.List })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SecretList(ui: FeedUi, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val colors = AgentTheme.colors
    val list = ui.state.secrets
    list.secrets.forEach { row -> SecretLine(row) { onOpen(row.id) } }
    val note = list.problem ?: when {
        !list.loaded -> "Reading the secrets…"
        !list.enabled -> "This dashboard can't keep secrets yet: set SECRETS_KEY on its server first."
        list.secrets.isEmpty() -> "Keep API keys and passwords here for your agents. They get a one-time link that saves the value to a file, so it never shows in their conversation."
        else -> "Agents get a one-time link, never the value. Every use is logged."
    }
    Note(note, error = list.problem != null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        AppButton("Add a secret", ButtonTone.Primary, icon = Icons.Plus, enabled = list.loaded && list.enabled, onClick = onAdd)
    }
}

@Composable
private fun SecretLine(row: SecretRow, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .touchTarget()
            .pressable("${row.name}, ${row.scope}${if (row.paused) ", paused" else ""}", shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Lock, null, tint = if (row.paused) colors.textFaint else colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, Modifier.weight(1f, fill = false), color = colors.text, style = AgentTheme.type.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.paused) MetaText("  Paused", color = colors.warning, style = AgentTheme.type.label)
            }
            MetaText("${row.scope} · ${row.used}", color = colors.textMuted)
        }
        Icon(Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

@Composable
private fun AddSecret(ui: FeedUi, onDone: () -> Unit) {
    val name = rememberTextFieldState()
    // Not saveable: a half-typed value must never land in the platform's saved state.
    val value = remember { TextFieldState() }
    val note = rememberTextFieldState()
    var allAgents by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The value lives only in this box, and goes when the screen does.
    DisposableEffect(Unit) { onDispose { value.clearText() } }

    SettingsHeading("Name")
    Field(name, "Name, like OPENAI_API_KEY", "OPENAI_API_KEY", enabled = !saving, code = true)
    SettingsHeading("Value")
    Field(value, "Value, hidden as you type", "Paste or type it", enabled = !saving, secret = true)
    SettingsHeading("Note")
    Field(note, "Note: what it is for, optional", "What it is for (optional)", enabled = !saving)
    ScopeChoice(ui, allAgents, chosen, enabled = !saving, onAll = { allAgents = it }, onToggle = { id -> chosen = if (id in chosen) chosen - id else chosen + id })
    val typedName = name.text.toString().trim()
    val formProblem = when {
        typedName.isNotEmpty() && !SecretName.matches(typedName) -> "A name is letters, digits, _ . or -, starting with a letter or _."
        else -> null
    }
    Note(problem ?: formProblem ?: "Stored encrypted. Agents never see it in their conversation.", error = (problem ?: formProblem) != null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        AppButton("Cancel", ButtonTone.Secondary, enabled = !saving, onClick = onDone)
        AppButton(
            if (saving) "Saving…" else "Save",
            ButtonTone.Primary,
            enabled = !saving && formProblem == null && typedName.isNotEmpty() && value.text.isNotEmpty(),
        ) {
            saving = true
            problem = null
            ui.emit(FeedEvent.AddSecret(typedName, value.text.toString(), note.text.toString().trim().ifEmpty { null }, allAgents, if (allAgents) emptyList() else chosen.toList()) { failed ->
                saving = false
                problem = failed
                if (failed == null) onDone()
            })
        }
    }
}

@Composable
private fun SecretDetail(ui: FeedUi, row: SecretRow, onBack: () -> Unit) {
    val colors = AgentTheme.colors
    val list = ui.state.secrets
    val auth = LocalDeviceAuth.current ?: rememberPlatformDeviceAuth()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val preview = LocalPreview.current
    var shown by remember(row.id) { mutableStateOf(preview.revealedSecret) }
    var problem by remember(row.id) { mutableStateOf<String?>(null) }
    var asking by remember(row.id) { mutableStateOf(false) }
    var allAgents by remember(row.id, row.allAgents) { mutableStateOf(row.allAgents) }
    var chosen by remember(row.id, row.agentIds) { mutableStateOf(row.agentIds.toSet()) }
    var replacing by remember(row.id) { mutableStateOf(false) }
    val replacement = remember { TextFieldState() }
    val busy = list.busy == row.id
    LaunchedEffect(row.id) { ui.emit(FeedEvent.ReadSecrets(auditFor = row.id)) }
    // A revealed value hides itself again, and never outlives this screen.
    LaunchedEffect(shown) { if (shown != null && preview.revealedSecret == null) { delay(RevealMs); shown = null } }
    DisposableEffect(row.id) { onDispose { shown = null; replacement.clearText() } }
    // Leaving the app (or this window) hides it, so it is not in the app switcher's picture.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused && preview.revealedSecret == null) shown = null }

    fun reveal(copy: Boolean) {
        problem = null
        scope.launch {
            if (!auth.confirm(if (copy) "Copy ${row.name}" else "Show ${row.name}")) {
                problem = "Not confirmed, so it stays hidden."
                return@launch
            }
            ui.emit(FeedEvent.RevealSecret(row.id, copy) { value, failed ->
                problem = failed
                if (value == null) return@RevealSecret
                if (!copy) {
                    shown = value
                    return@RevealSecret
                }
                clipboard.setText(AnnotatedString(value))
                problem = "Copied. The clipboard is cleared in a minute."
                ClipboardWiper.wipeLater(clipboard, value, ClipboardMs)
            })
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppButton("All secrets", ButtonTone.Quiet, icon = Icons.Back, onClick = onBack)
    }
    if (row.note != null) Text(row.note, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = colors.text, style = AgentTheme.type.body)
    MetaText("${row.origin} · ${row.used}", Modifier.padding(horizontal = 16.dp), color = colors.textMuted)

    SettingsHeading("Value")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val masked = shown == null
        Text(
            shown ?: "••••••••••••",
            Modifier.weight(1f).semantics { contentDescription = if (masked) "Hidden value" else "Value of ${row.name}" },
            color = if (masked) colors.textMuted else colors.text,
            style = AgentTheme.type.code,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        AppButton(if (masked) "Show" else "Hide", ButtonTone.Quiet, icon = if (masked) Icons.Eye else Icons.EyeOff) {
            if (masked) reveal(copy = false) else shown = null
        }
        AppButton("Copy", ButtonTone.Quiet, icon = Icons.Copy) { reveal(copy = true) }
    }
    if (replacing) {
        Field(replacement, "New value, hidden as you type", "Paste the new value", enabled = !busy, secret = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            AppButton("Cancel", ButtonTone.Secondary, enabled = !busy) { replacing = false; replacement.clearText() }
            AppButton("Replace", ButtonTone.Primary, enabled = !busy && replacement.text.isNotEmpty()) {
                ui.emit(FeedEvent.ChangeSecret(row.id, value = replacement.text.toString()) { failed ->
                    problem = failed ?: "Replaced. Links already handed out no longer work."
                    if (failed == null) { replacing = false; replacement.clearText() }
                })
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Replace the value", ButtonTone.Quiet, icon = Icons.Pencil, enabled = !busy) { replacing = true }
        }
    }

    problem?.let { Note(it, error = !it.startsWith("Copied") && !it.startsWith("Replaced")) }

    SettingsHeading("Activity")
    if (list.auditFor == row.id && list.audit.isNotEmpty()) {
        list.audit.take(6).forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.what, Modifier.weight(1f), color = if (entry.warning) colors.error else colors.text, style = AgentTheme.type.body, maxLines = 2)
                MetaText(entry.time, Modifier.padding(start = 8.dp), color = colors.textMuted)
            }
        }
    } else {
        Note(if (list.auditFor == row.id) "Nothing yet." else "Reading…", error = false)
    }

    ScopeChoice(ui, allAgents, chosen, enabled = !busy, onAll = { allAgents = it }, onToggle = { id -> chosen = if (id in chosen) chosen - id else chosen + id })
    val scopeChanged = allAgents != row.allAgents || (!allAgents && chosen != row.agentIds.toSet())
    if (scopeChanged) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Save who can use it", ButtonTone.Primary, enabled = !busy) {
                ui.emit(FeedEvent.ChangeSecret(row.id, allAgents = allAgents, agentIds = if (allAgents) emptyList() else chosen.toList()) { problem = it })
            }
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        AppButton(if (row.paused) "Resume" else "Pause", ButtonTone.Secondary, icon = if (row.paused) Icons.Play else Icons.Pause, enabled = !busy) {
            ui.emit(FeedEvent.ChangeSecret(row.id, paused = !row.paused) { problem = it })
        }
        AppButton("Delete", ButtonTone.Destructive, icon = Icons.Trash, enabled = !busy) { asking = true }
    }
    if (asking) {
        ConfirmDeleteSecret(
            row.name,
            onCancel = { asking = false },
            onConfirm = {
                asking = false
                ui.emit(FeedEvent.DeleteSecret(row.id) { failed -> if (failed == null) onBack() else problem = failed })
            },
        )
    }
}

/** Who may use a secret: every agent, or only the ticked ones. */
@Composable
private fun ScopeChoice(ui: FeedUi, allAgents: Boolean, chosen: Set<String>, enabled: Boolean, onAll: (Boolean) -> Unit, onToggle: (String) -> Unit) {
    SettingsHeading("Who can use it")
    Column(Modifier.selectableGroup()) {
        RadioRow("Every agent", allAgents, enabled) { onAll(true) }
        RadioRow("Only these agents", !allAgents, enabled) { onAll(false) }
    }
    if (!allAgents) {
        val agents = ui.state.agents
        if (agents.isEmpty()) Note("No agents have connected yet.", error = false)
        Column(Modifier.padding(start = 24.dp)) {
            agents.forEach { agent -> CheckRow(agent.name, agent.id in chosen, enabled) { onToggle(agent.id) } }
        }
    }
}

@Composable
private fun ConfirmDeleteSecret(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.error.copy(alpha = 0.08f))
            .border(1.dp, colors.error.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    ) {
        Text("Delete $name? It is gone for good, and agents can't fetch it any more. Its activity stays.", color = colors.text, style = AgentTheme.type.body)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            AppButton("Keep it", ButtonTone.Secondary, onClick = onCancel)
            AppButton("Delete", ButtonTone.Destructive, onClick = onConfirm)
        }
    }
}

@Composable
private fun Field(state: TextFieldState, label: String, placeholder: String, enabled: Boolean, secret: Boolean = false, code: Boolean = false) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextInput(
            state,
            label = label,
            placeholder = placeholder,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 10.dp),
            secret = secret,
            enabled = enabled,
            style = if (code) AgentTheme.type.code else AgentTheme.type.body,
        )
    }
}

@Composable
private fun Note(text: String, error: Boolean) {
    val colors = AgentTheme.colors
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) colors.error else colors.textMuted,
        style = AgentTheme.type.body,
    )
}

/**
 * Clears a copied secret from the clipboard after a while, if it is still there. It runs on its own
 * scope, so closing the sheet (to go and paste) does not cancel it.
 */
internal object ClipboardWiper {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    fun wipeLater(clipboard: androidx.compose.ui.platform.ClipboardManager, value: String, afterMs: Long) {
        scope.launch {
            delay(afterMs)
            if (runCatching { clipboard.getText()?.text }.getOrNull() == value) clipboard.setText(AnnotatedString(""))
        }
    }
}

/** What the dashboard accepts as a secret's name. */
internal val SecretName = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$")
