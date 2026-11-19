package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.launch

/**
 * The cards a card points at, each drawn small: who wrote it and what it is, its headline or first
 * line, and its first picture or video, which opens or plays as it does on its own card. A tap
 * anywhere else on it opens that card's thread, at it. One removed since says so and opens nothing.
 */
@Composable
internal fun LinkedCards(refs: List<LinkedCard>, modifier: Modifier = Modifier) {
    if (refs.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (link in refs) key(link.kind, link.id) { LinkedCardView(link) }
    }
}

@Composable
private fun LinkedCardView(link: LinkedCard) {
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val shape = AgentTheme.shapes.medium
    val scope = rememberCoroutineScope()
    val folds = LocalCardFolds.current
    val open: (() -> Unit)? = if (link.removed || ui == null) null else {
        { scope.launch { ui.openLinked(link) { id -> if (folds.isFolded(id)) folds.set(id, false) } } }
    }
    val frame = Modifier
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .clip(shape)
        .background(if (colors.isDark) colors.surfaceRaised else colors.surfaceSunken)
        .border(1.dp, colors.border, shape)
    Column(
        frame
            .then(if (open != null) Modifier.pressable("Open ${link.spoken}", shape, onClick = open) else Modifier.semantics { contentDescription = link.spoken })
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Link, null, tint = colors.textFaint, modifier = Modifier.size(13.dp))
            MetaText(heading(link), Modifier.padding(start = 6.dp))
        }
        if (link.removed) {
            Text("This was removed.", Modifier.padding(top = 2.dp), color = colors.textMuted, style = type.meta)
            return@Column
        }
        link.title?.let {
            Text(
                it,
                Modifier.padding(top = 3.dp),
                color = colors.text,
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        link.text?.takeIf { it != link.title }?.let {
            Text(
                it,
                Modifier.padding(top = 2.dp),
                color = if (link.title == null) colors.text else colors.textMuted,
                style = type.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        link.media?.let { tile ->
            Spacer(Modifier.height(6.dp))
            val tiles = listOf(tile)
            MediaGrid(tiles, Modifier.widthIn(max = LinkedTileWidth), onOpen = rememberTileOpener(tiles))
        }
        link.mediaNote?.let { MetaText(it, Modifier.padding(top = 4.dp)) }
    }
}

/** "forge's reply", "Your post in Web", "A removed reply". */
private fun heading(link: LinkedCard): String {
    if (link.removed) return "A removed ${link.what}"
    val whose = when (link.who) {
        "" -> link.what.replaceFirstChar { it.uppercase() }
        "You" -> "Your ${link.what}"
        else -> "${link.who}'s ${link.what}"
    }
    return link.where?.let { "$whose in $it" } ?: whose
}

/** How wide a linked card's picture or video gets: enough to see, small enough to read as a preview. */
private val LinkedTileWidth = 280.dp
