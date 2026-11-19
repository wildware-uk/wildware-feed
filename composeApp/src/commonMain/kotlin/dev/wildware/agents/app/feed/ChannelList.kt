package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.ProjectColours

/**
 * The channel list split for the sidebar and the drawer: each active project (a named group of
 * channels) with its active channels, then the channels in no project ("Channels"), then what is
 * archived: archived channels, and archived projects with every channel in them.
 */
data class ChannelSections(
    val groups: List<Pair<ProjectGroup, List<Project>>>,
    val loose: List<Project>,
    val archivedChannels: List<Project>,
    val archivedGroups: List<Pair<ProjectGroup, List<Project>>>,
) {
    companion object {
        /** [channels] in the order the list shows them; a channel naming a project that is gone counts as loose. */
        fun of(channels: List<Project>, groups: List<ProjectGroup>): ChannelSections {
            val byId = groups.associateBy { it.id }
            fun groupOf(p: Project) = p.groupId?.let(byId::get)
            val inArchivedGroup = { p: Project -> groupOf(p)?.archived == true }
            return ChannelSections(
                groups = groups.filter { !it.archived }.map { g -> g to channels.filter { it.groupId == g.id && !it.archived } },
                loose = channels.filter { groupOf(it) == null && !it.archived },
                archivedChannels = channels.filter { it.archived && !inArchivedGroup(it) },
                archivedGroups = groups.filter { it.archived }.map { g -> g to channels.filter { it.groupId == g.id } },
            )
        }
    }
}

val FeedUi.channelSections: ChannelSections get() = ChannelSections.of(projects, state.groups)

/** Where a project's folded state is kept: with the folded cards, on this device. */
internal fun groupFoldKey(groupId: String) = "group:$groupId"

/**
 * Each active project as a header that folds its channels away, remembered on this device, with
 * its channels under it drawn by [channel]. A folded project still shows what waits in it.
 */
@Composable
internal fun ProjectGroups(ui: FeedUi, channel: @Composable (Project) -> Unit) {
    val folds = LocalCardFolds.current
    val colors = AgentTheme.colors
    for ((group, channels) in ui.channelSections.groups) {
        val folded = folds.isFolded(groupFoldKey(group.id))
        val holdsCurrent = channels.any { it.id == ui.state.current.id }
        NavRow(
            group.name,
            selected = folded && holdsCurrent,
            leading = { GroupGlyph(group, folded) },
            trailing = {
                if (folded) {
                    WaitingBadge(channels.sumOf { it.waiting }, Modifier.padding(end = 6.dp))
                    UnreadBadge(channels.sumOf { it.unread })
                }
                IconAction(Icons.Settings, "${group.name} settings", iconSize = 15.dp, tint = colors.textFaint) {
                    ui.projectDialog = ProjectDialog.GroupSettings(group.id)
                }
            },
            spoken = "${group.name}, project, ${channels.size} channel${if (channels.size == 1) "" else "s"}, " + if (folded) "folded" else "open",
            onClick = { folds.set(groupFoldKey(group.id), !folded) },
        )
        if (!folded) {
            Column(Modifier.padding(start = 14.dp)) {
                channels.forEach { channel(it) }
                if (channels.isEmpty()) MetaText("No channels yet", Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp))
            }
        }
    }
}

/** A project's mark: the fold arrow, in the project's own colour when it has one. */
@Composable
private fun GroupGlyph(group: ProjectGroup, folded: Boolean) {
    val colors = AgentTheme.colors
    val tint = ProjectColours.parse(group.accent) ?: colors.textFaint
    Icon(if (folded) Icons.ChevronRight else Icons.ChevronDown, null, tint = tint, modifier = Modifier.size(16.dp))
}

/** "+ New project", under the list: a named group of channels. */
@Composable
internal fun NewGroupRow(ui: FeedUi) {
    val colors = AgentTheme.colors
    NavRow(
        "New project",
        selected = false,
        leading = { Icon(Icons.Plus, null, tint = colors.textMuted, modifier = Modifier.size(18.dp)) },
        trailing = {},
        spoken = "New project, a group of channels",
        onClick = { ui.projectDialog = ProjectDialog.NewGroup },
    )
}
