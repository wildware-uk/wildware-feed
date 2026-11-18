package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MediaRetention
import dev.wildware.agents.RetentionForm
import dev.wildware.agents.RetentionPeriod
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.touchTarget

private val kindLabels = listOf(
    MediaRetention.Image to "Images",
    MediaRetention.Video to "Videos",
    MediaRetention.Audio to "Audio",
    MediaRetention.File to "Files",
)

/**
 * The way into "Keep media" from the account sheet: a row saying how long media is kept now.
 * Shown only to those [RetentionSettings.allowed] lets change it.
 */
@Composable
internal fun KeepMediaEntry(ui: FeedUi) {
    val retention = ui.state.retention
    if (!retention.allowed) return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val now = if (retention.loaded) MediaRetention.label(retention.setting.days) else ""
    SettingsHeading("This dashboard")
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .clip(shape)
            .pressable("Keep media" + if (now.isNotEmpty()) ", $now" else "", shape, labelIsShown = true) {
                ui.alertsOpen = false
                ui.retentionOpen = true
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Clock, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text("Keep media", Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
        MetaText(now, color = colors.textMuted)
        Icon(Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/**
 * How long the dashboard keeps media, and which kinds. Saving something that deletes more than
 * today asks first, since it can't be undone; a refusal shows the dashboard's own words.
 */
@Composable
fun RetentionSettingsSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val retention = ui.state.retention
    val preview = LocalPreview.current.retentionDraft
    // A save that lands starts the form again from what the dashboard stored.
    var form by remember(retention.setting, retention.saves) { mutableStateOf(preview ?: RetentionForm.of(retention.setting)) }
    LaunchedEffect(retention.loaded) { if (!retention.loaded) ui.emit(FeedEvent.ReadMediaRetention) }
    val close = { ui.retentionOpen = false }
    val ready = retention.loaded && !retention.saving

    SettingsSheet("Keep media", safeArea, onClose = close) {
        // The choices scroll; the save button or the confirm step below them always stays in view.
        Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            SettingsHeading("Keep media for")
            Column(Modifier.selectableGroup()) {
                RadioRow("Forever", form.period == RetentionPeriod.Forever, ready) { form = form.choose(RetentionPeriod.Forever) }
                MediaRetention.Presets.forEach { days ->
                    val preset = RetentionPeriod.Preset(days)
                    RadioRow(MediaRetention.label(days), form.period == preset, ready) { form = form.choose(preset) }
                }
                RadioRow("Custom…", form.period == RetentionPeriod.Custom, ready) { form = form.choose(RetentionPeriod.Custom) }
            }
            if (form.period == RetentionPeriod.Custom) CustomDays(form, ready) { form = form.typeCustom(it) }

            SettingsHeading("Which media")
            kindLabels.forEach { (kind, label) ->
                CheckRow(label, kind in form.kinds, ready && form.canToggle(kind)) { form = form.toggle(kind) }
            }

            val problem = retention.problem ?: form.problem
            val note = problem ?: when {
                !retention.loaded -> "Reading the dashboard's settings…"
                retention.saves > 0 && !form.changed -> "Saved."
                else -> "Checked once a night. Pinned cards and open requests always keep their media."
            }
            Text(
                note,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (problem != null) colors.error else colors.textMuted,
                style = AgentTheme.type.body,
            )
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 1.dp).background(colors.border))
        val chosen = form.chosen
        val warning = form.warning
        if (form.confirming && chosen != null && warning != null) {
            ConfirmDelete(
                warning,
                saving = retention.saving,
                onCancel = { form = form.copy(confirming = false) },
                onConfirm = { ui.emit(FeedEvent.SetMediaRetention(chosen)) },
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                AppButton(
                    if (retention.saving) "Saving…" else "Save",
                    ButtonTone.Primary,
                    enabled = ready && form.changed,
                ) {
                    if (chosen == null) return@AppButton
                    if (form.deletesMore) form = form.copy(confirming = true) else ui.emit(FeedEvent.SetMediaRetention(chosen))
                }
            }
        }
    }
}

/** The step before media starts going: what will happen, plainly, and a clear way out. */
@Composable
private fun ConfirmDelete(warning: String, saving: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(shape)
            .background(colors.error.copy(alpha = 0.08f))
            .border(1.dp, colors.error.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Trash, null, tint = colors.error, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(warning, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.text, style = AgentTheme.type.body)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            AppButton("Cancel", ButtonTone.Secondary, enabled = !saving, onClick = onCancel)
            AppButton(if (saving) "Saving…" else "Delete older media", ButtonTone.Destructive, enabled = !saving, onClick = onConfirm)
        }
    }
}

/** One of several choices, only one of which is on. */
@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) colors.text else colors.textMuted, style = AgentTheme.type.body)
    }
}

/** The number box under "Custom…": digits only, from 1 to 3650. */
@Composable
private fun CustomDays(form: RetentionForm, enabled: Boolean, onChange: (String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val box = rememberTextFieldState(form.customDays)
    LaunchedEffect(box) {
        snapshotFlow { box.text.toString() }.collect { typed ->
            val kept = typed.filter { it.isDigit() }.take(4)
            if (kept != typed) box.setTextAndPlaceCursorAtEnd(kept)
            onChange(kept)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(
            box,
            label = "Number of days, from ${MediaRetention.MinDays} to ${MediaRetention.MaxDays}",
            placeholder = "30",
            modifier = Modifier
                .width(96.dp)
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, if (form.problem != null && form.period == RetentionPeriod.Custom) colors.error else colors.border, shape)
                .padding(horizontal = 10.dp),
            enabled = enabled,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.width(10.dp))
        Text("days", color = colors.textMuted, style = AgentTheme.type.body)
    }
}
