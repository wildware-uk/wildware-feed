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
import androidx.compose.ui.semantics.contentDescription
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
        agents.forEach { agent -> AgentRow(agent) }
    }
}

@Composable
private fun AgentRow(agent: Agent) {
    val colors = AgentTheme.colors
    val doing = agent.doing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(agent.name, agent.role, doing).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(Author.AgentAuthor(agent), 30.dp, showPresence = true)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(agent.name, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText(doing ?: agent.role, color = colors.textMuted)
        }
    }
}
