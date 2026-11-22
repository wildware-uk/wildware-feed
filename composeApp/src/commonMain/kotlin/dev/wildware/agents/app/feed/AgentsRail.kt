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
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
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
 * Who is in the team, down the right of a wide window: the people, then the agents, each with
 * whether they are online. On a channel the agents are split into those that hear it and those that
 * don't, and an owner or admin can add or remove each one there (see [MembersList]).
 */
@Composable
fun AgentsRail(ui: FeedUi, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.sidebar)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
    ) {
        MembersList(ui)
    }
}

/**
 * The team's people and agents, for the right rail, the narrow sidebar and the phone's drawer.
 * People are shown only. On a channel, agents that hear it come first; the rest follow under "Not
 * in this channel". Removing one keeps it out (posting there does not bring it back) until it is
 * added again, and it still hears a message that @mentions it. [inset] pads labels and rows.
 */
@Composable
internal fun MembersList(ui: FeedUi, inset: Dp = 0.dp, onOpenAgent: () -> Unit = {}) {
    val colors = AgentTheme.colors
    val state = ui.state
    val slug = state.current.slug
    val target = slug?.let { memberTarget(it, group = false) }
    // The session reads the open channel's list itself (DashboardSession), so showing it sends nothing.
    val list = target?.let { state.members[it] }?.takeIf { it.loaded }
    val people = state.team.people.filter { !it.removed }
    val canChange = state.team.me?.role?.let { it != "member" } ?: true
    val agents = state.agents
    val online = agents.count { it.presence == Presence.Online } + people.count { it.online }
    Row(Modifier.padding(horizontal = inset).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Members", Modifier.weight(1f, fill = false))
        MetaText("  $online online", color = colors.textMuted)
    }
    if (people.size > 1) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("People", Modifier.padding(horizontal = inset))
        Spacer(Modifier.height(4.dp))
        people.forEach { PersonMemberRow(it, inset) }
    }
    Spacer(Modifier.height(12.dp))
    val open = { agent: Agent -> ui.agentOpen = agent.id; onOpenAgent() }
    if (slug == null || list == null) {
        SectionLabel("Agents", Modifier.padding(horizontal = inset))
        Spacer(Modifier.height(4.dp))
        if (agents.isEmpty()) MetaText("No agents yet.", Modifier.padding(horizontal = inset), color = colors.textMuted)
        agents.forEach { agent -> AgentMenu(ui, agent) { AgentRow(agent, inset) { open(agent) } } }
        ConnectAgentButton(ui, inset)
        target?.let { state.members[it]?.problem }?.let { MetaText(it, Modifier.padding(horizontal = inset), color = colors.error) }
        return
    }
    val byId = agents.associateBy { it.id }
    val (hearing, others) = list.members.filter { it.agentId in byId }.partition { it.hears }
    SectionLabel("Agents in this channel", Modifier.padding(horizontal = inset))
    Spacer(Modifier.height(4.dp))
    if (hearing.isEmpty()) MetaText("No agent hears it. An @mention still reaches one.", Modifier.padding(horizontal = inset), color = colors.textMuted)
    hearing.forEach { m ->
        val agent = byId.getValue(m.agentId)
        AgentMenu(ui, agent) {
            AgentRow(agent, inset, action = MemberAction("Remove", "Remove ${agent.name} from this channel", canChange && list.busy == null) {
                ui.emit(FeedEvent.SetMember(slug, false, m.agentId, hears = false))
            }) { open(agent) }
        }
    }
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("Not in this channel", Modifier.padding(horizontal = inset))
        Spacer(Modifier.height(4.dp))
        others.forEach { m ->
            val agent = byId.getValue(m.agentId)
            AgentMenu(ui, agent) {
                AgentRow(agent, inset, note = m.note, action = MemberAction("Add", "Add ${agent.name} to this channel", canChange && list.busy == null) {
                    ui.emit(FeedEvent.SetMember(slug, false, m.agentId, hears = true))
                }) { open(agent) }
            }
        }
    }
    ConnectAgentButton(ui, inset)
    list.problem?.let { MetaText(it, Modifier.padding(horizontal = inset), color = colors.error) }
}

/** An agent's row with its menu: right-click, a hold, Shift+F10 or the Menu key. */
@Composable
private fun AgentMenu(ui: FeedUi, agent: Agent, row: @Composable () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val menu = { at: androidx.compose.ui.geometry.Offset -> ui.sideMenu = SideMenu(SideTarget.Agent(agent.id), at) }
    SideMenuAnchor(
        ui, SideTarget.Agent(agent.id),
        Modifier.sidebarGestures(agent.name, canDrag = false, openMenu = menu, onStart = {}, onMove = {}, onEnd = {}, nudge = null, focus = focus),
        row,
    )
}

/** A button at the end of an agent's row: Add or Remove. */
private class MemberAction(val label: String, val spoken: String, val enabled: Boolean, val run: () -> Unit)

@Composable
private fun PersonMemberRow(person: PersonRow, inset: Dp) {
    val colors = AgentTheme.colors
    val presence = if (person.online) Presence.Online else Presence.Offline
    val author = if (person.you) Author.Owner(person.name) else Author.Person(person.name, person.handle, dev.wildware.agents.app.engine.hue(person.id))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = 7.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(author, 30.dp, presence = presence)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(person.name + if (person.you) " (you)" else "", color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText(presence.label, color = colors.textMuted)
        }
    }
}

@Composable
private fun AgentRow(agent: Agent, inset: Dp = 0.dp, note: String? = null, action: MemberAction? = null, onOpen: () -> Unit) {
    val colors = AgentTheme.colors
    val details = agent.details
    val shape = AgentTheme.shapes.small
    Row(Modifier.fillMaxWidth().padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
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
                when {
                    note != null -> MetaText(note, color = colors.textMuted)
                    details == null -> MetaText(agent.doing ?: agent.role, color = colors.textMuted)
                    else -> AgentFacts(details)
                }
            }
        }
        if (action != null) {
            AppButton(
                action.label, ButtonTone.Quiet,
                Modifier.semantics { contentDescription = action.spoken },
                enabled = action.enabled, onClick = action.run,
            )
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
