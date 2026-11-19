package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/**
 * One agent, opened from its row in the agent list: who it is, what its session says, and its name
 * to change, as the website's rail lets the owner do. A rename waits for the dashboard's say, as a
 * name can be refused, and then shows on every card the agent posted. Below, the MCP clients signed
 * in as it over OAuth, each with a way to sign it out, and a way to remove the agent itself.
 */
@Composable
fun AgentDialog(ui: FeedUi, agentId: String) {
    val agent = ui.state.agents.firstOrNull { it.id == agentId } ?: return run { ui.agentOpen = null }
    val colors = AgentTheme.colors
    val name = remember(agentId) { TextFieldState(agent.name) }
    var problem by remember(agentId) { mutableStateOf<String?>(null) }
    var busy by remember(agentId) { mutableStateOf(false) }
    val close = { ui.agentOpen = null }
    LaunchedEffect(agentId) { ui.emit(FeedEvent.ReadConnections(agentId)) }
    val save = {
        val typed = name.text.toString().trim()
        when {
            typed.isEmpty() -> problem = "An agent needs a name."
            typed == agent.name -> close()
            !busy -> {
                busy = true
                problem = null
                ui.emit(FeedEvent.RenameAgent(agentId, typed) { refused ->
                    busy = false
                    if (refused == null) close() else problem = refused
                })
            }
        }
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(Author.AgentAuthor(agent), 30.dp, showPresence = true)
                Text(agent.name, Modifier.padding(start = 12.dp), style = AgentTheme.type.title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MetaText(agent.doing ?: agent.presence.label, color = colors.textMuted)
                agent.details?.let { AgentFacts(it) }
                Spacer(Modifier.height(16.dp))
                BoxedField("Name", name, "Agent name", agent.name, onSubmit = save)
                problem?.let { Text(it, Modifier.padding(top = 10.dp), color = colors.error, style = AgentTheme.type.label) }
                Connections(ui, agentId)
                RemoveAgent(ui, agentId, agent.name, onRemoved = close)
            }
        },
        dismissButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
        confirmButton = { AppButton(if (busy) "Saving…" else "Rename", ButtonTone.Primary, enabled = !busy, onClick = save) },
    )
}

/**
 * The apps signed in as this agent through the browser (OAuth). Each can be signed out on its own:
 * the agent, and anything else using it, carries on. Nothing shows for an agent that has none.
 */
@Composable
private fun Connections(ui: FeedUi, agentId: String) {
    val colors = AgentTheme.colors
    val list = ui.state.connections.takeIf { it.agentId == agentId } ?: return
    if (list.connections.isEmpty() && list.problem == null) return
    var asking by remember(agentId) { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(18.dp))
    Text("Connected apps", Modifier.semantics { heading() }, color = colors.text, style = AgentTheme.type.label)
    list.connections.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.client, color = colors.text, style = AgentTheme.type.body, maxLines = 1)
                MetaText(c.where, color = colors.textMuted)
                MetaText(c.used, color = colors.textFaint)
            }
            AppButton(
                if (list.removing == c.id) "Revoking…" else "Revoke",
                ButtonTone.Quiet,
                enabled = list.removing == null,
                onClick = { asking = c.id },
            )
        }
        if (asking == c.id) {
            val shape = AgentTheme.shapes.medium
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(shape)
                    .background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), shape)
                    .padding(12.dp),
            ) {
                Text("Sign ${c.client} out? It can't act as this agent any more until you allow it again.", color = colors.text, style = AgentTheme.type.body)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    AppButton("Keep it", ButtonTone.Secondary, onClick = { asking = null })
                    Spacer(Modifier.width(8.dp))
                    AppButton("Revoke", ButtonTone.Destructive, onClick = {
                        asking = null
                        ui.emit(FeedEvent.RevokeConnection(agentId, c.id))
                    })
                }
            }
        }
    }
    list.problem?.let {
        Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = AgentTheme.type.label)
    }
}

/**
 * Removing the agent: it is revoked, so its token and every app signed in as it stop working at
 * once, and it leaves the agent lists. What it posted stays. It asks first, and says plainly that
 * there is no undo.
 */
@Composable
private fun RemoveAgent(ui: FeedUi, agentId: String, name: String, onRemoved: () -> Unit) {
    val colors = AgentTheme.colors
    var asking by remember(agentId) { mutableStateOf(false) }
    var busy by remember(agentId) { mutableStateOf(false) }
    var problem by remember(agentId) { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(18.dp))
    if (!asking) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            AppButton("Remove agent", ButtonTone.Quiet, onClick = { asking = true; problem = null })
        }
        return
    }
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.error.copy(alpha = 0.08f))
            .border(1.dp, colors.error.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    ) {
        Text(
            "Remove $name? Its token and every app signed in as it stop working at once, and it leaves the agent list. " +
                "Its posts stay. You can't undo this.",
            color = colors.text,
            style = AgentTheme.type.body,
        )
        problem?.let {
            Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error, style = AgentTheme.type.label)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Keep it", ButtonTone.Secondary, enabled = !busy, onClick = { asking = false })
            Spacer(Modifier.width(8.dp))
            AppButton(if (busy) "Removing…" else "Remove agent", ButtonTone.Destructive, enabled = !busy, onClick = {
                busy = true
                problem = null
                ui.emit(FeedEvent.RemoveAgent(agentId) { refused ->
                    busy = false
                    if (refused == null) onRemoved() else problem = refused
                })
            })
        }
    }
}
