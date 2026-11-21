package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.engine.TeamEntry

/**
 * The team switcher's view of the app: the teams this device is in, and what it can do with them.
 * [web]: the web app, where each team is its own tab, so adding one opens its address in a new tab
 * ([OpenTeamTabDialog], which [add] shows there).
 */
@Immutable
class TeamsUi(
    val teams: List<TeamEntry>,
    val switchTo: (TeamEntry) -> Unit,
    val add: () -> Unit,
    val signOutOf: (TeamEntry) -> Unit,
    val web: Boolean = false,
    /** The web app's "Add a team": remembers the address, then the screen opens it in a new tab. */
    val openTab: (String) -> Unit = {},
) {
    val active: TeamEntry? get() = teams.firstOrNull { it.active }

    /** Questions waiting in the teams that aren't open. */
    val waitingElsewhere: Int get() = teams.filter { !it.active }.sumOf { it.waiting ?: 0 }

    /** The [n]th team (from 1), as Ctrl/⌘+Shift+[n] picks it. */
    fun numbered(n: Int): TeamEntry? = teams.getOrNull(n - 1)
}

/** The teams, for the sidebar, the drawer, the command bar and the keyboard. Null outside a signed-in app. */
val LocalTeams = staticCompositionLocalOf<TeamsUi?> { null }

/** A team's first letter in a rounded square: its mark in the switcher. */
@Composable
private fun TeamMark(name: String, active: Boolean) {
    val colors = AgentTheme.colors
    Box(
        Modifier
            .size(22.dp)
            .clip(AgentTheme.shapes.small)
            .background(if (active) colors.accent else colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = if (active) colors.onAccent else colors.textMuted,
            style = AgentTheme.type.label.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * The open team, at the top of the sidebar and the drawer; tapped, every team folds out under it
 * with how many questions wait in each, and "Add a team" and "Sign out of" this one.
 * [close] runs after a choice, for the drawer to shut itself.
 */
@Composable
internal fun TeamSwitcher(teams: TeamsUi, close: () -> Unit = {}) {
    val colors = AgentTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val active = teams.active
    val elsewhere = teams.waitingElsewhere
    val name = active?.name ?: "Teams"
    NavRow(
        name,
        selected = false,
        leading = { TeamMark(name, active = true) },
        trailing = {
            WaitingBadge(elsewhere, Modifier.padding(end = 6.dp))
            Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.size(15.dp))
        },
        spoken = buildString {
            append("Team $name")
            if (elsewhere > 0) append(", ${questions(elsewhere)} in other teams")
            append(if (open) ", hide teams" else ", switch team")
        },
        onClick = { open = !open },
    )
    if (!open) return
    Column(Modifier.padding(start = 12.dp)) {
        teams.teams.forEachIndexed { i, team ->
            NavRow(
                team.name,
                selected = team.active,
                leading = { TeamMark(team.name, team.active) },
                trailing = {
                    when {
                        team.active -> Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(15.dp))
                        team.link -> MetaText("New tab")
                        else -> WaitingBadge(team.waiting ?: 0)
                    }
                },
                spoken = buildString {
                    append(team.name)
                    when {
                        team.active -> append(", open")
                        team.link -> append(", opens in a new tab")
                        (team.waiting ?: 0) > 0 -> append(", ${questions(team.waiting ?: 0)}")
                    }
                    if (i < 9 && !teams.web) append(", shortcut ${shortcutHint(i + 1)}")
                },
                onClick = {
                    open = false
                    teams.switchTo(team)
                    close()
                },
            )
        }
        NavRow(
            "Add a team",
            selected = false,
            leading = { Icon(Icons.Plus, null, tint = colors.textMuted, modifier = Modifier.size(16.dp)) },
            spoken = if (teams.web) "Add a team, opens in a new tab" else "Add a team",
            onClick = {
                open = false
                teams.add()
                close()
            },
        )
        if (active != null && teams.teams.size > 1) {
            NavRow(
                "Sign out of ${active.name}",
                selected = false,
                leading = { Icon(Icons.SignOut, null, tint = colors.textMuted, modifier = Modifier.size(16.dp)) },
                onClick = {
                    open = false
                    teams.signOutOf(active)
                    close()
                },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** The keys that open the [n]th team: ⌘⇧2 on a Mac, Ctrl+Shift+2 elsewhere. */
internal fun shortcutHint(n: Int): String = if (shortcutsUseCommand) "⌘⇧$n" else "Ctrl+Shift+$n"

/**
 * The web app's "Add a team": a team's address, opened in a new tab. A browser keeps one cookie per
 * address, so each team signs in, and stays signed in, in its own tab.
 */
@Composable
internal fun OpenTeamTabDialog(teams: TeamsUi, onClose: () -> Unit) {
    val colors = AgentTheme.colors
    val uris = LocalUriHandler.current
    val address = remember { TextFieldState() }
    var problem by remember { mutableStateOf<String?>(null) }
    val go = {
        val typed = address.text.toString().trim()
        if (typed.isEmpty()) problem = "Type the team's address."
        else {
            val url = if ("://" in typed) typed else "https://$typed"
            teams.openTab(url)
            runCatching { uris.openUri(url) }
            onClose()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text("Add a team", style = AgentTheme.type.title) },
        text = {
            Column {
                Text(
                    "Each team opens in its own tab, signed in on its own. Type the team's address.",
                    color = colors.textMuted,
                    style = AgentTheme.type.body,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                BoxedField("Team address", address, "Team address", "acme.example.com", onSubmit = go)
                problem?.let { Text(it, Modifier.padding(top = 10.dp), color = colors.error, style = AgentTheme.type.label) }
            }
        },
        confirmButton = { AppButton("Open in a new tab", ButtonTone.Primary, onClick = go) },
        dismissButton = { AppButton("Cancel", ButtonTone.Quiet, onClick = onClose) },
    )
}

/** Row of the phone drawer's foot: "Sign out", or with several teams "Sign out of <team>". */
internal fun signOutLabel(teams: TeamsUi?, demo: Boolean): String = when {
    demo -> "Leave demo"
    teams != null && teams.teams.count { !it.link } > 1 -> "Sign out of ${teams.active?.name ?: "this team"}"
    else -> "Sign out"
}
