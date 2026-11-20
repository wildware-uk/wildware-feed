package dev.wildware.agents.app.feed

import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentColors
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable

/** The longest a new tag may be, as on the website. */
private const val TagMaxLength = 32

/** A tag's colour by its palette name. The name is stored, not a colour, so it reads in both themes. */
fun AgentColors.tag(colour: String?): Color = when (colour) {
    "rose" -> Color(0xFFE11D48)
    "amber" -> waiting
    "green" -> success
    "blue" -> Color(0xFF0EA5E9)
    "violet" -> Color(0xFF8B5CF6)
    else -> textMuted
}

/** The tags on a card. Tapping one shows only the cards wearing it. Nothing at all when there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<CardTag>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    val ui = LocalFeedUi.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            val shape = AgentTheme.shapes.small
            Box(
                Modifier.clip(shape).pressable("Show only cards tagged ${tag.name}", shape) { ui?.tagFilter = tag.name },
                contentAlignment = Alignment.Center,
            ) { TagLabel(tag) }
        }
    }
}

@Composable
private fun TagLabel(tag: CardTag, trailing: (@Composable () -> Unit)? = null) {
    val colors = AgentTheme.colors
    val c = colors.tag(tag.colour)
    val shape = AgentTheme.shapes.small
    Row(
        Modifier
            .clip(shape)
            .background(c.copy(alpha = if (colors.isDark) 0.16f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.35f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${tag.name}", color = if (tag.colour == null || tag.colour == "grey") colors.textMuted else c, style = AgentTheme.type.meta)
        trailing?.invoke()
    }
}

/**
 * The tags the next post will wear, above the message box, each with a cross to take it off; and a
 * way to add more. Nothing when the post has none: the box's "#" button adds the first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewPostTagChips(ui: FeedUi, projectId: String, modifier: Modifier = Modifier) {
    val picked = ui.postTags[projectId].orEmpty()
    if (picked.isEmpty()) return
    val shape = AgentTheme.shapes.small
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        picked.forEach { tag ->
            Box(
                Modifier.clip(shape).pressable("Take the tag ${tag.name} off this post", shape) { ui.tagNewPost(projectId, tag.id, on = false, create = false) },
                contentAlignment = Alignment.Center,
            ) {
                TagLabel(tag) {
                    Icon(Icons.Close, null, tint = AgentTheme.colors.textMuted, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                }
            }
        }
    }
}

/** "#bug ✕" above the feed while it shows only one tag. Tapping it shows everything again. */
@Composable
fun TagFilterChip(modifier: Modifier = Modifier) {
    val ui = LocalFeedUi.current ?: return
    val name = ui.tagFilter ?: return
    val tag = ui.state.tags.firstOrNull { it.name == name } ?: CardTag(name, name)
    val shape = AgentTheme.shapes.small
    Box(modifier.clip(shape).pressable("Stop showing only $name", shape) { ui.tagFilter = null }, contentAlignment = Alignment.Center) {
        TagLabel(tag) {
            Icon(Icons.Close, null, tint = AgentTheme.colors.textMuted, modifier = Modifier.padding(start = 4.dp).size(12.dp))
        }
    }
}

/** What [FeedUi.tagging] holds while the picker chooses tags for a post not yet written: this, then the project's id. */
const val NewPostTags = "new-post:"

/**
 * The tags a card can wear, ticked where it wears them. One tap puts a tag on or takes it off, at
 * once; a new word is typed at the bottom and goes straight on the card. Opened on [NewPostTags], it
 * chooses the tags the next post goes up wearing instead, and sends nothing.
 */
@Composable
fun TagPicker(ui: FeedUi, cardId: String) {
    val colors = AgentTheme.colors
    val newPost = cardId.takeIf { it.startsWith(NewPostTags) }?.removePrefix(NewPostTags)
    val item = ui.state.item(cardId)
    val (project, base) = when {
        newPost != null -> newPost to ui.postTags[newPost].orEmpty()
        item is FeedItem.Update -> item.project.id to item.tags
        item is FeedItem.Post -> item.project.id to item.tags
        else -> null to emptyList()
    }
    val wearing = if (newPost != null) base else ui.tagsFor(cardId, base)
    val toggle = { tag: String, on: Boolean, create: Boolean ->
        if (newPost != null) ui.tagNewPost(newPost, tag, on, create) else ui.emit(FeedEvent.Tag(cardId, tag, on, create))
    }
    val words = ui.vocabularyFor(project)
    val typed = rememberTextFieldState()
    val add = {
        val name = typed.text.toString().trim().take(TagMaxLength)
        if (name.isNotEmpty()) {
            val known = words.firstOrNull { it.name.equals(name, ignoreCase = true) && !it.id.startsWith("new:") }
            if (known != null) toggle(known.id, true, false) else toggle(name, true, true)
            typed.clearText()
        }
    }
    AlertDialog(
        onDismissRequest = { ui.tagging = null },
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        shape = AgentTheme.shapes.large,
        title = { Text("Tags", style = AgentTheme.type.title) },
        text = {
            Column {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (words.isEmpty()) {
                        MetaText("This channel has no tags yet. Make the first one below.", color = colors.textMuted)
                    }
                    words.forEach { tag ->
                        val on = wearing.any { it.id == tag.id || it.name.equals(tag.name, ignoreCase = true) }
                        TagRow(tag, on) {
                            val made = tag.id.startsWith("new:")
                            toggle(if (made) tag.name else tag.id, !on, made && !on)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(typed, label = "New tag", placeholder = "New tag…", modifier = Modifier.weight(1f), onSubmit = add)
                    AppButton("Add", ButtonTone.Secondary, Modifier.padding(start = 8.dp), enabled = typed.text.isNotBlank(), onClick = add)
                }
            }
        },
        confirmButton = { AppButton("Done", ButtonTone.Primary) { ui.tagging = null } },
    )
}

@Composable
private fun TagRow(tag: CardTag, on: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics { toggleableState = ToggleableState(on) }
            .pressable("${tag.name}, ${if (on) "on" else "off"}", shape, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.tag(tag.colour)))
        Text(tag.name, Modifier.padding(start = 10.dp).weight(1f), color = colors.text, style = AgentTheme.type.label)
        if (on) Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
    }
}
