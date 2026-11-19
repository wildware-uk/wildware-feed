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

/**
 * Where a picture or video will be once the dashboard has made it ("Processing…"), or where it
 * would have been had it been readable ("Media unavailable"). Same shape as [RemovedMediaTile], so
 * the card doesn't jump when the picture arrives.
 */
@Composable
fun NotReadyMediaTile(why: NotReady, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    BoxWithConstraints(
        modifier
            .background(colors.surfaceSunken)
            .clearAndSetSemantics {
                contentDescription = why.words
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        val roomy = maxWidth >= 96.dp && maxHeight >= 56.dp
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            when (why) {
                NotReady.Processing -> Icon(Icons.Image, null, tint = colors.textFaint, modifier = Modifier.size(if (roomy) 20.dp else 16.dp))
                NotReady.Failed -> Icon(Icons.Warning, null, tint = colors.error, modifier = Modifier.size(if (roomy) 20.dp else 16.dp))
            }
            if (roomy) {
                Spacer(Modifier.height(6.dp))
                Text(why.words, color = colors.textMuted, style = AgentTheme.type.meta, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A removed sound or file, in the row its player or download chip took. */
@Composable
fun RemovedMediaRow(afterDays: Int, modifier: Modifier = Modifier) {
    val label = removedMediaLabel(afterDays)
    MediaNoteRow(label, null, label, modifier) { Icon(Icons.Clock, null, tint = AgentTheme.colors.textFaint, modifier = Modifier.size(18.dp)) }
}

/**
 * A sound or file the dashboard is still making ("Processing…") or couldn't read ("Media
 * unavailable"), in the row its player or download chip will take, under its [name] when it has one.
 */
@Composable
fun NotReadyMediaRow(why: NotReady, name: String?, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    MediaNoteRow(name ?: why.words, why.words.takeIf { name != null }, listOfNotNull(name, why.words).joinToString(", "), modifier) {
        when (why) {
            NotReady.Processing -> Icon(Icons.Clock, null, tint = colors.textFaint, modifier = Modifier.size(18.dp))
            NotReady.Failed -> Icon(Icons.Warning, null, tint = colors.error, modifier = Modifier.size(18.dp))
        }
    }
}

/** A quiet row in place of a sound or file: an icon, a line, and a smaller line under it when there is one. */
@Composable
private fun MediaNoteRow(title: String, detail: String?, spoken: String, modifier: Modifier, icon: @Composable () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (detail != null) colors.text else colors.textMuted, style = if (detail != null) AgentTheme.type.label else AgentTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = colors.textMuted, style = AgentTheme.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
