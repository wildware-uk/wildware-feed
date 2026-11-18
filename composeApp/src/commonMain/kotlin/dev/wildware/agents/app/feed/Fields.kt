package dev.wildware.agents.app.feed

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable

/** A value that is a web address, opened as a link; anything else is plain text. Agents write these, so only http and https. */
internal fun linkOf(value: String): String? {
    val text = value.trim()
    if (!text.startsWith("http://") && !text.startsWith("https://")) return null
    return text.takeIf { ' ' !in it }
}

/**
 * What is noted on a card ("branch", "PR", …), out of the way until it is wanted: one line saying
 * how many, opened with a tap. Open, each field can be changed or removed, and new ones added.
 * Nothing at all on a card with none, until "Details…" in its menu opens it.
 */
@Composable
fun CardDetails(cardId: String, fields: List<CardField>, modifier: Modifier = Modifier) {
    val ui = LocalFeedUi.current
    val open = ui?.detailsOpen?.contains(cardId) == true
    if (fields.isEmpty() && !open) return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val label = if (fields.isEmpty()) "Details" else "Details (${fields.size})"
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(shape)
                .pressable(label, shape, labelIsShown = true) {
                    if (open) ui?.detailsOpen?.remove(cardId) else ui?.detailsOpen?.add(cardId)
                }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
            Text(label, Modifier.padding(start = 4.dp), color = colors.textMuted, style = AgentTheme.type.label)
        }
        if (!open) return@Column
        Spacer(Modifier.height(4.dp))
        if (fields.isEmpty()) MetaText("Nothing noted on this card yet.", Modifier.padding(start = 4.dp, bottom = 6.dp), color = colors.textMuted)
        fields.forEach { field -> FieldRow(cardId, field) }
        AddField(cardId)
    }
}

@Composable
private fun FieldRow(cardId: String, field: CardField) {
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    var editing by remember(field.name) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(field.name, Modifier.widthIn(min = 64.dp, max = 140.dp).padding(end = 10.dp), color = colors.textMuted, style = AgentTheme.type.meta)
        if (editing) {
            val value = rememberTextFieldState(field.value)
            val save = {
                val v = value.text.toString().trim()
                if (v.isNotEmpty()) ui?.emit(FeedEvent.SetField(cardId, field.name, v))
                editing = false
            }
            TextInput(value, label = "Value for ${field.name}", placeholder = "Value", modifier = Modifier.weight(1f).boxed(), onSubmit = save)
            AppButton("Save", ButtonTone.Primary, Modifier.padding(start = 6.dp), onClick = save)
            AppButton("Cancel", ButtonTone.Quiet) { editing = false }
        } else {
            val link = linkOf(field.value)
            val uris = LocalUriHandler.current
            if (link != null) {
                val shape = AgentTheme.shapes.small
                Text(
                    field.value,
                    Modifier.weight(1f).clip(shape).pressable("Open ${field.name}", shape, labelIsShown = true) {
                        runCatching { uris.openUri(link) }
                    },
                    color = colors.accent,
                    style = AgentTheme.type.body.copy(textDecoration = TextDecoration.Underline),
                )
            } else {
                Text(field.value, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
            }
            IconAction(Icons.Pencil, "Change ${field.name}", iconSize = 15.dp) { editing = true }
            IconAction(Icons.Trash, "Remove ${field.name}", iconSize = 15.dp) { ui?.emit(FeedEvent.SetField(cardId, field.name, null)) }
        }
    }
}

@Composable
private fun AddField(cardId: String) {
    val ui = LocalFeedUi.current
    val name = rememberTextFieldState()
    val value = rememberTextFieldState()
    val add = {
        val n = name.text.toString().trim()
        val v = value.text.toString().trim()
        if (n.isNotEmpty() && v.isNotEmpty()) {
            ui?.emit(FeedEvent.SetField(cardId, n, v))
            name.clearText()
            value.clearText()
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(name, label = "Field name", placeholder = "Name", modifier = Modifier.weight(0.4f).boxed())
        Spacer(Modifier.size(6.dp))
        TextInput(value, label = "Field value", placeholder = "Value", modifier = Modifier.weight(0.6f).boxed(), onSubmit = add)
        AppButton("Add", ButtonTone.Secondary, Modifier.padding(start = 6.dp), enabled = name.text.isNotBlank() && value.text.isNotBlank(), onClick = add)
    }
}

/** A thin outline, so an empty box reads as somewhere to type. */
@Composable
private fun Modifier.boxed(): Modifier {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    return this.clip(shape).border(1.dp, colors.border, shape).padding(horizontal = 10.dp)
}
