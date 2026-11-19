package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/**
 * Who is working right now, down the right of a wide window, as the website's right rail shows it:
 * each agent online, what it says it is doing, and on which machine.
 */
@Composable
fun AgentsRail(ui: FeedUi, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val agents = ui.state.agents
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        Row(Modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Agents")
            MetaText("  ${agents.count { it.presence == Presence.Online }} online", color = colors.textMuted)
        }
        Spacer(Modifier.height(12.dp))
        if (agents.isEmpty()) {
            MetaText("Nobody is working right now.", color = colors.textMuted)
        }
        agents.forEach { agent -> AgentRow(agent) { ui.agentOpen = agent.id } }
    }
}

@Composable
private fun AgentRow(agent: Agent, onOpen: () -> Unit) {
    val colors = AgentTheme.colors
    val details = agent.details
    val shape = AgentTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(agentSpoken(agent), shape, onClick = onOpen)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(Author.AgentAuthor(agent), 30.dp, showPresence = true)
        Column(Modifier.padding(start = 10.dp).weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.name, Modifier.weight(1f, fill = false), color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details != null && details.sessions > 1) MetaText("  ${details.sessions} sessions", color = colors.textMuted)
            }
            if (details == null) {
                MetaText(agent.doing ?: agent.role, color = colors.textMuted)
            } else {
                AgentFacts(details)
            }
        }
    }
}

/** Model, machine, folder and last heartbeat, a line each, as the website's rail lists them. */
@Composable
internal fun AgentFacts(details: AgentDetails) {
    val colors = AgentTheme.colors
    details.model?.let { MetaText(it, color = colors.textMuted) }
    details.host?.let { MetaText(it, color = colors.textMuted) }
    // Folders are long; the end is the part that says which project it is.
    details.cwd?.let {
        Text(
            it.takeLast(48).let { tail -> if (tail.length < it.length) "…$tail" else tail },
            color = colors.textFaint,
            style = AgentTheme.type.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    MetaText(details.lastSeen, color = colors.textFaint)
}

/** An agent as a screen reader says it: name, whether it is online, then what its session says. */
internal fun agentSpoken(agent: Agent): String =
    listOfNotNull(agent.name, agent.presence.label.lowercase(), agent.details?.spoken ?: agent.doing).joinToString(", ")
