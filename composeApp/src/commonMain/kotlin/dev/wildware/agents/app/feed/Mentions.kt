package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.pressable

/**
 * The @mention being typed, if the cursor is in one. The dashboard matches `@` and an agent's name
 * not run on from a word, so an email address is not a mention, and a space ends one.
 */
object MentionText {
    /** "@" at [start], and what has been typed after it. */
    data class Typing(val start: Int, val query: String)

    private fun Char.inName() = isLetterOrDigit() || this == '-' || this == '_' || this == '.'

    fun at(text: String, cursor: Int): Typing? {
        if (cursor < 1 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0 && text[i] != '@') {
            if (!text[i].inName()) return null
            i--
        }
        if (i < 0) return null
        if (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '@')) return null
        return Typing(i, text.substring(i + 1, cursor))
    }

    /** [text] with the typed mention replaced by "@[name] ", and where the cursor goes after it. */
    fun insert(text: String, typing: Typing, name: String, cursor: Int = typing.start + 1 + typing.query.length): Pair<String, Int> {
        val before = text.substring(0, typing.start)
        val after = text.substring(cursor).removePrefix(" ")
        val inserted = "@$name "
        return (before + inserted + after) to (before.length + inserted.length)
    }

    /** Who matches [query]: names that start with it first, then names that contain it, in [all]'s order. */
    fun matches(all: List<Mentionable>, query: String, max: Int = 8): List<Mentionable> {
        val (starts, contains) = all.filter { it.name.contains(query, ignoreCase = true) }
            .partition { it.name.startsWith(query, ignoreCase = true) }
        return (starts + contains).take(max)
    }
}

/**
 * The @ list above a message box: who can be mentioned, filtered by what was typed after the @.
 * Arrows move, Enter or Tab picks, Escape closes; a tap picks on a phone.
 */
@Composable
fun MentionList(choices: List<Mentionable>, selected: Int, onPick: (Mentionable) -> Unit, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val count = choices.size
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.borderStrong, shape)
            .padding(vertical = 4.dp)
            .semantics {
                collectionInfo = CollectionInfo(count, 1)
                contentDescription = "$count to mention"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        choices.forEachIndexed { index, choice ->
            val on = index == selected
            val spoken = "Mention ${choice.name}" + listOfNotNull(choice.presence?.label?.lowercase(), choice.hint).joinToString("") { ", $it" }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) colors.accentSoft else colors.surfaceRaised)
                    // The cursor stays in the box: a tap here must not take focus and close the list.
                    .focusProperties { canFocus = false }
                    .pressable(spoken, shape, onClick = { onPick(choice) })
                    .semantics { this.selected = on; contentDescription = spoken }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColour(choice)))
                Text("@${choice.name}", Modifier.padding(start = 10.dp), color = colors.text, style = AgentTheme.type.label.copy(fontWeight = FontWeight.SemiBold))
                choice.hint?.let { MetaText(it, Modifier.padding(start = 8.dp), color = colors.textMuted) }
            }
        }
    }
}

@Composable
private fun dotColour(choice: Mentionable) = AgentTheme.colors.let { colors ->
    when (choice.presence) {
        Presence.Online -> colors.online
        Presence.Idle -> colors.waiting
        Presence.Offline -> colors.borderStrong
        null -> colors.agent(choice.hue)
    }
}
