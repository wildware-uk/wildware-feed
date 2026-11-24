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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FieldInput
import dev.wildware.agents.FieldType
import dev.wildware.agents.FormFieldView
import dev.wildware.agents.FormFill
import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.focusRing
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.touchTarget
import kotlinx.serialization.json.JsonObject

/** The types typed into a box; the rest are picked or ticked. */
private val Typed = setOf(FieldType.Text, FieldType.TextArea, FieldType.Number, FieldType.Email, FieldType.Url, FieldType.Date)

/**
 * A form an agent asked to be filled in: each field drawn for its type, named, with its hint and a
 * mark when it is required, then the agent's own actions. A field says what is wrong with it once
 * something is in it; the actions stay off until every field would be taken, and a line under them
 * says which fields are still to do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldsFormAnswer(questionId: String, form: FieldsForm, enabled: Boolean, onAnswer: (JsonObject) -> Unit) {
    val colors = AgentTheme.colors
    if (!form.understood) {
        Note("This form has a field this version of $ProductName can't show. Update the app to fill it in.", color = colors.textMuted)
        return
    }
    // Typed fields keep their words in a box's own state; picks and ticks in one map.
    val boxes = remember(questionId, form.fields) {
        form.fields.filter { it.fieldType in Typed }.associate { f -> f.id to TextFieldState((FormFill.start(f) as FieldInput.Typed).text) }
    }
    val chosen = remember(questionId, form.fields) {
        mutableStateMapOf<String, FieldInput>().apply { form.fields.filter { it.fieldType !in Typed }.forEach { put(it.id, FormFill.start(it)) } }
    }
    val inputs: Map<String, FieldInput> = form.fields.associate { f -> f.id to (boxes[f.id]?.let { FieldInput.Typed(it.text.toString()) } ?: chosen[f.id] ?: FormFill.start(f)) }
    val blocking = FormFill.blocking(form.fields, inputs)
    val ready = blocking.isEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        form.fields.forEach { field ->
            val input = inputs[field.id]
            FormFieldRow(field, problem = FormFill.problem(field, input)?.takeIf { !it.empty }?.text) {
                when (field.fieldType) {
                    FieldType.Text, FieldType.TextArea, FieldType.Number, FieldType.Email, FieldType.Url, FieldType.Date ->
                        TypedField(field, boxes.getValue(field.id), enabled)
                    FieldType.Select -> SelectField(field, (input as? FieldInput.Typed)?.text.orEmpty(), enabled) { chosen[field.id] = FieldInput.Typed(it) }
                    FieldType.Radio -> RadioField(field, (input as? FieldInput.Typed)?.text.orEmpty(), enabled) { chosen[field.id] = FieldInput.Typed(it) }
                    FieldType.Checkbox, FieldType.Toggle -> TickField(field, (input as? FieldInput.Ticked)?.on == true, enabled) { chosen[field.id] = FieldInput.Ticked(it) }
                    FieldType.Checkboxes -> {
                        val picked = (input as? FieldInput.Picked)?.options.orEmpty()
                        ManyField(field, picked, enabled) { option -> chosen[field.id] = FieldInput.Picked(if (option in picked) picked - option else picked + option) }
                    }
                    null -> Unit
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            form.actions.forEachIndexed { i, action ->
                AppButton(
                    action,
                    if (i == 0) ButtonTone.Waiting else ButtonTone.Secondary,
                    icon = if (i == 0) Icons.Check else null,
                    enabled = enabled && ready,
                ) { onAnswer(FormFill.answer(form.fields, action, inputs)) }
            }
        }
        if (!ready) {
            val names = blocking.map { it.label }
            Note(
                "Still to do: " + names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else "",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.textMuted,
            )
        }
    }
}

/** A field's label (with a required mark), its control, then its hint or what is wrong. */
@Composable
private fun FormFieldRow(field: FormFieldView, problem: String?, control: @Composable () -> Unit) {
    val colors = AgentTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A tick carries its label beside the box, so it isn't said twice.
        if (field.fieldType != FieldType.Checkbox && field.fieldType != FieldType.Toggle) {
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, color = colors.text, style = AgentTheme.type.label.copy(fontWeight = FontWeight.SemiBold))
                if (field.required) Text(" *", color = colors.waiting, style = AgentTheme.type.label)
            }
        }
        control()
        when {
            problem != null -> Note(problem, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
            field.hint != null -> Note(field.hint!!, color = colors.textMuted)
        }
    }
}

/** What a screen reader hears for a field: its label, and that it is required. */
private fun spoken(field: FormFieldView) = field.label + if (field.required) ", required" else ""

@Composable
private fun TypedField(field: FormFieldView, state: TextFieldState, enabled: Boolean) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val type = field.fieldType
    val multiline = type == FieldType.TextArea
    val keyboard = when (type) {
        FieldType.Number -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
        FieldType.Email -> KeyboardOptions(keyboardType = KeyboardType.Email)
        FieldType.Url -> KeyboardOptions(keyboardType = KeyboardType.Uri)
        else -> KeyboardOptions.Default
    }
    val placeholder = field.placeholder ?: when (type) {
        FieldType.Date -> "YYYY-MM-DD"
        FieldType.Email -> "name@example.com"
        FieldType.Url -> "https://"
        else -> ""
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f))
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 14.dp, vertical = 2.dp),
    ) {
        TextInput(
            state,
            label = spoken(field) + if (type == FieldType.Date) ", a date as year-month-day" else "",
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            multiline = multiline,
            minLines = if (multiline) 3 else 1,
            enabled = enabled,
            keyboard = keyboard,
        )
    }
}

/** A dropdown: the pick so far, or "Choose…", opening the options in a menu. */
@Composable
private fun SelectField(field: FormFieldView, picked: String, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f))
                .border(1.dp, if (open) colors.borderStrong else colors.border, shape)
                .then(if (enabled) Modifier.pressable(spoken(field), shape, role = Role.DropdownList) { open = true } else Modifier.alpha(0.5f))
                .semantics { stateDescription = picked.ifEmpty { "Nothing chosen" } }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(picked.ifEmpty { "Choose…" }, Modifier.weight(1f), color = if (picked.isEmpty()) colors.textFaint else colors.text, style = AgentTheme.type.body)
            Icon(Icons.ChevronDown, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surfaceRaised,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = colors.text, style = AgentTheme.type.body) },
                    trailingIcon = if (option == picked) ({ Icon(Icons.Check, "Chosen", tint = colors.accent, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = { onPick(option); open = false },
                )
            }
        }
    }
}

/** Every option shown, one of which is picked. */
@Composable
private fun RadioField(field: FormFieldView, picked: String, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = AgentTheme.colors
    Column(Modifier.selectableGroup().semantics { contentDescription = spoken(field) }) {
        field.options.forEach { option ->
            val on = option == picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .touchTarget()
                    .focusRing(AgentTheme.shapes.small)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onPick(option) }
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (on) colors.accent else colors.borderStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
                }
                Spacer(Modifier.width(10.dp))
                Text(option, color = colors.text, style = AgentTheme.type.body)
            }
        }
    }
}

/** One yes or no: a tick box, or a switch for a toggle, with the label beside it. */
@Composable
private fun TickField(field: FormFieldView, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = AgentTheme.colors
    val switch = field.fieldType == FieldType.Toggle
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .focusRing(AgentTheme.shapes.small)
            .toggleable(value = on, enabled = enabled, role = if (switch) Role.Switch else Role.Checkbox) { onChange(it) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (switch) SwitchMark(on) else TickMark(on)
        Spacer(Modifier.width(10.dp))
        Text(field.label, color = colors.text, style = AgentTheme.type.body)
        if (field.required) Text(" *", color = colors.waiting, style = AgentTheme.type.body)
    }
}

/** Several options, any number ticked within the field's bounds. */
@Composable
private fun ManyField(field: FormFieldView, picked: Set<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    val colors = AgentTheme.colors
    Column(Modifier.semantics { contentDescription = spoken(field) }) {
        field.options.forEach { option ->
            val on = option in picked
            Row(
                Modifier
                    .fillMaxWidth()
                    .touchTarget()
                    .focusRing(AgentTheme.shapes.small)
                    .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { onToggle(option) }
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TickMark(on)
                Spacer(Modifier.width(10.dp))
                Text(option, color = colors.text, style = AgentTheme.type.body)
            }
        }
    }
}

@Composable
private fun TickMark(on: Boolean) {
    val colors = AgentTheme.colors
    Box(
        Modifier
            .size(20.dp)
            .clip(AgentTheme.shapes.small)
            .background(if (on) colors.accent else Color.Transparent)
            .border(1.5.dp, if (on) colors.accent else colors.borderStrong, AgentTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SwitchMark(on: Boolean) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Box(
        Modifier
            .size(36.dp, 20.dp)
            .clip(shape)
            .background(if (on) colors.accent else colors.surfaceSunken)
            .border(1.dp, if (on) colors.accent else colors.borderStrong, shape)
            .padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (on) colors.onAccent else colors.textMuted))
    }
}

/** What was sent on a form with fields, read-only: each field's label over its value. */
@Composable
internal fun AnsweredValues(values: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { (label, value) ->
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Note(label, color = colors.textMuted)
                Text(value, color = colors.text, style = AgentTheme.type.body)
            }
        }
    }
}

/** A line of small print that wraps, unlike [MetaText]: a hint or a problem can run long. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.textMuted) {
    Text(text, modifier, color = color, style = AgentTheme.type.meta)
}
