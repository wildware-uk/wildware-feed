package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/**
 * Who may see a channel or a project (server spec/15), read when its Permissions panel opens, by
 * [accessTarget]. Levels are the server's words: "none", "read", "post", "manage".
 */
@Immutable
data class AccessView(
    val target: String? = null,
    val loaded: Boolean = false,
    val isPrivate: Boolean = false,
    /** A channel whose project is private: hidden from anyone not listed, whatever its own switch says. */
    val groupPrivate: Boolean = false,
    val canManage: Boolean = false,
    val people: List<AccessRow> = emptyList(),
    val agents: List<AccessRow> = emptyList(),
    /** The person or agent being changed ("member:<id>", "agent:<id>", or "private"), while the dashboard is asked. */
    val busy: String? = null,
    val problem: String? = null,
)

/** One person or agent on a Permissions panel. */
@Immutable
data class AccessRow(
    val kind: String,
    val id: String,
    val name: String,
    val level: String,
    /** Where the level comes from, in words: "Set here", "From the project", "Team default"… */
    val source: String,
    /** The setting on this channel or project itself; null when it inherits. */
    val own: String?,
    val editable: Boolean,
) {
    val key: String get() = "$kind:$id"
}

/** What one person or agent can reach, for their page, by [reachTarget]. */
@Immutable
data class ReachView(val target: String? = null, val loaded: Boolean = false, val lines: List<ReachLine> = emptyList(), val problem: String? = null)

@Immutable
data class ReachLine(val channel: String, val level: String)

/** Every Permissions panel and reach summary read so far. */
@Immutable
data class AccessState(
    val panels: Map<String, AccessView> = emptyMap(),
    val reach: Map<String, ReachView> = emptyMap(),
)

/** The key an [AccessView] is for: a channel's or project's id. */
fun accessTarget(id: String, group: Boolean): String = (if (group) "group:" else "channel:") + id

/** The key a [ReachView] is for: a person's membership id or an agent's id. */
fun reachTarget(id: String, agent: Boolean): String = (if (agent) "agent:" else "member:") + id

/** The levels a person can be given, in order, with what each is called. */
internal val PersonLevels = listOf("none" to "No access", "read" to "Read", "post" to "Post", "manage" to "Manage")

/** An agent's: Manage is limited, as an agent never changes permissions. */
internal val AgentLevels = listOf("none" to "No access", "read" to "Read", "post" to "Post", "manage" to "Manage (limited)")

/** A level as the panel says it. */
internal fun levelName(level: String, agent: Boolean): String =
    (if (agent) AgentLevels else PersonLevels).firstOrNull { it.first == level }?.second ?: level

/** Where a level comes from, in words, from the server's `via`. */
internal fun levelSource(via: String, inherited: String?, agent: Boolean): String = when (via) {
    "owner" -> "Team owner: always manages"
    "channel" -> "Set here"
    "group" -> "From the project" + (inherited?.let { ": ${levelName(it, agent)}" } ?: "")
    "private" -> "Not listed, and this is private"
    else -> "Team default"
}

/**
 * A channel's or a project's Permissions panel: the private switch, then every person and agent of
 * the team with their level and where it comes from, each changeable by someone who manages it.
 * "Inherit" takes a setting away, so the channel follows its project (or the team's default) again.
 * Everyone who can see it can read the panel; only people who manage it change it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionsDialogView(ui: FeedUi, id: String, group: Boolean) {
    val colors = AgentTheme.colors
    val target = accessTarget(id, group)
    LaunchedEffect(target) { ui.emit(FeedEvent.ReadAccess(id, group)) }
    val view = ui.state.access.panels[target]
    val name = if (group) ui.state.groups.firstOrNull { it.id == id }?.name else ui.projects.firstOrNull { it.id == id }?.name
    val close = { ui.projectDialog = null }
    AlertDialog(
        onDismissRequest = close,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text(if (name != null) "Permissions · $name" else "Permissions", style = AgentTheme.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    view == null || (!view.loaded && view.problem == null) -> MetaText("Reading…")
                    !view.loaded -> Unit
                    else -> {
                        val canChange = view.canManage && view.busy == null
                        CheckRow(
                            "Only people and agents listed can see this",
                            view.isPrivate,
                            enabled = canChange,
                            detail = if (group) "Every channel in it is hidden from anyone not listed." else null,
                        ) { ui.emit(FeedEvent.SetAccessPrivate(id, group, !view.isPrivate)) }
                        if (!group && view.groupPrivate) {
                            MetaText("Its project is private, so it is hidden from anyone not listed here or on the project.", Modifier.padding(horizontal = 16.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("People", color = colors.text, style = AgentTheme.type.label)
                        for (row in view.people) AccessRowView(ui, id, group, row, agent = false, canChange = canChange)
                        Spacer(Modifier.height(14.dp))
                        Text("Agents", color = colors.text, style = AgentTheme.type.label)
                        if (view.agents.isEmpty()) MetaText("No agents yet.")
                        for (row in view.agents) AccessRowView(ui, id, group, row, agent = true, canChange = canChange)
                        Text(
                            (if (group) "A project's setting applies to every channel in it, unless a channel sets its own." else "A setting here wins over the project's.") +
                                " Read can read and follow; Post can also post, react and answer; Manage can also change settings and permissions." +
                                (if (view.canManage) "" else " Only people who manage this can change it."),
                            Modifier.padding(top = 10.dp),
                            color = colors.textFaint,
                            style = AgentTheme.type.meta,
                        )
                    }
                }
                view?.problem?.let { Text(it, Modifier.padding(top = 8.dp), color = colors.error, style = AgentTheme.type.label) }
            }
        },
        confirmButton = { AppButton("Done", ButtonTone.Primary, onClick = close) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessRowView(ui: FeedUi, id: String, group: Boolean, row: AccessRow, agent: Boolean, canChange: Boolean) {
    val colors = AgentTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = "${row.name}: ${levelName(row.level, agent)}, ${row.source}" }) {
        Text(row.name, color = colors.text, style = AgentTheme.type.body)
        MetaText("${levelName(row.level, agent)} · ${row.source}")
        if (canChange && row.editable) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((level, label) in if (agent) AgentLevels else PersonLevels) {
                    Chip(label, selected = row.own == level) { ui.emit(FeedEvent.SetAccessLevel(id, group, row.kind, row.id, level)) }
                }
                if (row.own != null) Chip("Inherit", selected = false) { ui.emit(FeedEvent.SetAccessLevel(id, group, row.kind, row.id, null)) }
            }
        }
    }
}

/**
 * What one person or agent can reach, for their page: each channel the viewer can see too, with
 * their level there. Read when shown.
 */
@Composable
fun ReachSummary(ui: FeedUi, id: String, agent: Boolean) {
    val target = reachTarget(id, agent)
    LaunchedEffect(target) { ui.emit(FeedEvent.ReadReach(id, agent)) }
    val view = ui.state.access.reach[target]
    Column {
        Text("Can reach", color = AgentTheme.colors.text, style = AgentTheme.type.label)
        when {
            view == null || (!view.loaded && view.problem == null) -> MetaText("Reading…")
            view.problem != null -> MetaText(view.problem, color = AgentTheme.colors.error)
            view.lines.none { it.level != "none" } -> MetaText("No channels.")
            else -> {
                val reached = view.lines.filter { it.level != "none" }
                Text(
                    reached.joinToString(", ") { "#${it.channel} (${levelName(it.level, agent)})" },
                    color = AgentTheme.colors.textMuted,
                    style = AgentTheme.type.meta,
                )
                val hidden = view.lines.count { it.level == "none" }
                if (hidden > 0) MetaText("No access to ${view.lines.filter { it.level == "none" }.joinToString(", ") { "#" + it.channel }}.")
            }
        }
    }
}
