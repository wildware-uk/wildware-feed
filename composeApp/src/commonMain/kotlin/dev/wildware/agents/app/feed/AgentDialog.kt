package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/**
 * One agent, opened from its row in the agent list: who it is, what its session says, and its name
 * to change, as the website's rail lets the owner do. A rename waits for the dashboard's say, as a
 * name can be refused, and then shows on every card the agent posted.
 */
@Composable
fun AgentDialog(ui: FeedUi, agentId: String) {
    val agent = ui.state.agents.firstOrNull { it.id == agentId } ?: return run { ui.agentOpen = null }
    val colors = AgentTheme.colors
    val name = remember(agentId) { TextFieldState(agent.name) }
    var problem by remember(agentId) { mutableStateOf<String?>(null) }
    var busy by remember(agentId) { mutableStateOf(false) }
    val close = { ui.agentOpen = null }
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
            }
        },
        dismissButton = { AppButton("Cancel", ButtonTone.Secondary, onClick = close) },
        confirmButton = { AppButton(if (busy) "Saving…" else "Rename", ButtonTone.Primary, enabled = !busy, onClick = save) },
    )
}
