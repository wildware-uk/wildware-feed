package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.removedMediaLabel

/**
 * Where a picture or video was before the owner's media retention removed it: a quiet tile filling
 * the slot its thumbnail took, saying so. It has no address, so nothing tries to load. A screen
 * reader hears the same words.
 */
@Composable
fun RemovedMediaTile(afterDays: Int, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val label = removedMediaLabel(afterDays)
    BoxWithConstraints(
        modifier
            .background(colors.surfaceSunken)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        // A small tile, such as a picture option, has room for the clock alone; the words are still heard.
        val roomy = maxWidth >= 96.dp && maxHeight >= 56.dp
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Clock, null, tint = colors.textFaint, modifier = Modifier.size(if (roomy) 20.dp else 16.dp))
            if (roomy) {
                Spacer(Modifier.height(6.dp))
                Text(label, color = colors.textMuted, style = AgentTheme.type.meta, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A removed sound or file, in the row its player or download chip took. */
@Composable
fun RemovedMediaRow(afterDays: Int, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val label = removedMediaLabel(afterDays)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .clearAndSetSemantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Clock, null, tint = colors.textFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = colors.textMuted, style = AgentTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
