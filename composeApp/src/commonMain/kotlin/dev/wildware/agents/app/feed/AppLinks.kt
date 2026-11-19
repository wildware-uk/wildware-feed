package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/**
 * The apps on a card, one row each, in place of the frame the website ran them in. An app that is a
 * page of its own opens in the browser; one the dashboard hosts says it can't be shown here yet.
 */
@Composable
fun AppLinks(apps: List<CardApp>, modifier: Modifier = Modifier) {
    if (apps.isEmpty()) return
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        apps.forEach { AppLink(it) }
    }
}

@Composable
private fun AppLink(app: CardApp) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val uris = LocalUriHandler.current
    val name = app.title ?: app.name
    val url = app.url
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .then(
                if (url != null) Modifier.pressable("Open the app $name", shape) { uris.openUri(url) }
                else Modifier.clearAndSetSemantics { contentDescription = "App $name. It can't be shown in the app yet." },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Link, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(name, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val note = if (url == null) "Can't be shown in the app yet" else app.description
            note?.let { MetaText(it, color = colors.textMuted) }
        }
        if (url != null) Text("Open", Modifier.padding(start = 10.dp), color = colors.accent, style = AgentTheme.type.label)
    }
}
