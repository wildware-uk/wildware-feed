package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme

/**
 * An agent asking to join, over the feed on phone and desktop alike, until the owner decides:
 * "laptop-personal wants to join", the code to check against what the agent says, where it is
 * asking from, and the name it will get, which the owner may change. Allow lets it in; Deny turns
 * it away; Later puts this one aside for this run of the app (it expires by itself after ten
 * minutes). Nothing joins without this prompt.
 */
@Composable
fun JoinPrompt(ui: FeedUi, safeArea: SafeArea) {
    val later = remember { mutableStateListOf<String>() }
    val waiting = ui.state.joining.filter { it.id !in later }
    val ask = waiting.firstOrNull() ?: return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val name = remember(ask.id) { TextFieldState(ask.name) }
    // The name the server settled on wins over a stale one, should another device change it.
    LaunchedEffect(ask.id, ask.name) { if (name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(ask.name) }
    val allow = { ui.emit(FeedEvent.AllowAgent(ask.id, name.text.toString().trim().ifEmpty { ask.name })) }
    val title = "${ask.name} wants to join"
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // A tap outside does nothing: letting an agent in is never one stray tap away.
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = null) {},
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 72.dp, start = 16.dp, end = 16.dp, bottom = safeArea.bottom + 16.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = title; liveRegion = LiveRegionMode.Polite }
                .padding(20.dp),
        ) {
            MetaText("AN AGENT WANTS TO JOIN", color = colors.textMuted, style = AgentTheme.type.overline)
            Text(title, Modifier.padding(top = 6.dp).semantics { heading() }, color = colors.text, style = AgentTheme.type.title)

            val code = ask.code
            Row(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(AgentTheme.shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, colors.border, AgentTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Code ${code.toList().joinToString(" ")}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MetaText("CODE", color = colors.textMuted, style = AgentTheme.type.overline)
                    Text("Check it matches what the agent told you.", color = colors.textMuted, style = AgentTheme.type.meta)
                }
                Text(
                    code,
                    color = colors.text,
                    style = AgentTheme.type.code.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em),
                )
            }

            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ask.host?.let { Fact("From", it) }
                ask.cwd?.let { Fact("Folder", it) }
                ask.address?.let { Fact("Address", it) }
                ask.askedBy?.let { Fact("Asked by", "$it, with its own token") }
            }
            AgentsFullNote(ui, Modifier.padding(top = 12.dp))

            Spacer(Modifier.height(16.dp))
            BoxedField("Name", name, "Agent name", ask.name, onSubmit = allow)
            ask.error?.let {
                Text(it, Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive }, color = colors.error, style = AgentTheme.type.meta)
            }

            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TimeLeft(ask.expiresAt, "10 min", Modifier.weight(1f))
                if (waiting.size > 1) MetaText("${waiting.size - 1} more waiting", color = colors.textMuted)
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppButton("Deny", ButtonTone.Destructive, enabled = !ask.busy) { ui.emit(FeedEvent.DenyAgent(ask.id)) }
                Spacer(Modifier.width(8.dp))
                AppButton("Later", ButtonTone.Quiet, enabled = !ask.busy) { later += ask.id }
                Spacer(Modifier.weight(1f))
                AppButton("Allow", ButtonTone.Primary, enabled = !ask.busy, onClick = allow)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = AgentTheme.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        MetaText(label, Modifier.width(76.dp), color = colors.textMuted)
        Text(value, color = colors.text, style = AgentTheme.type.meta, maxLines = 2)
    }
}
