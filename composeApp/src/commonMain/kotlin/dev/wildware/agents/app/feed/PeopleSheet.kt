package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/** The way into People from the account sheet: a row saying how many people are in the team. */
@Composable
internal fun PeopleEntry(ui: FeedUi) {
    val team = ui.state.team
    if (!team.supported) return
    val here = team.people.count { !it.removed }
    SettingsLinkRow(Icons.People, "People", if (team.loaded) "$here" else "") {
        ui.alertsOpen = false
        ui.peopleOpen = true
    }
}

/**
 * Who is in the team (docs/TEAMS.md): everyone with their role, you first. Owners and admins
 * invite people by a link or a code, and remove them; owners change roles. Anyone can rename
 * themselves or leave. Removing someone signs out every device of theirs at once.
 */
@Composable
fun PeopleSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val team = ui.state.team
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadPeople) }
    var open by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    SettingsSheet("People", safeArea, onClose = { ui.peopleOpen = false }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            val here = team.people.filter { !it.removed }.sortedWith(compareByDescending<PersonRow> { it.you }.thenBy { rank(it.role) })
            here.forEach { person ->
                PersonLine(person, team, expanded = open == person.id, onToggle = { open = if (open == person.id) null else person.id })
                if (open == person.id) PersonActions(ui, person, team, onDone = { open = null })
            }
            team.created?.let { CreatedInvite(ui, it) }
            if (team.canManage) {
                SettingsHeading("Invites")
                team.invites.forEach { InviteLine(ui, it, team) }
                if (inviting) {
                    InviteForm(ui, team, onDone = { inviting = false })
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        AppButton("Invite someone", ButtonTone.Primary, icon = Icons.Plus, enabled = team.busy == null, onClick = { inviting = true })
                    }
                }
            }
            val note = team.problem ?: when {
                !team.loaded -> "Reading the team…"
                team.canManage -> "Invite people by a link or a code. Each person signs in with their own email and password, and gets alerts for what they follow."
                else -> "Ask an owner or an admin to invite someone."
            }
            Text(
                note,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (team.problem != null) colors.error else colors.textMuted,
                style = AgentTheme.type.body,
            )
        }
    }
}

private fun rank(role: String) = when (role) {
    "owner" -> 0
    "admin" -> 1
    else -> 2
}

private fun roleLabel(role: String) = role.replaceFirstChar { it.uppercase() }

@Composable
private fun PersonLine(person: PersonRow, team: TeamState, expanded: Boolean, onToggle: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(if (person.you) Author.Owner(person.name) else Author.Person(person.name, person.handle, dev.wildware.agents.app.engine.hue(person.id)), 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, Modifier.weight(1f, fill = false), color = colors.text, style = AgentTheme.type.body, maxLines = 1)
                if (person.you) MetaText("  You", color = colors.accent, style = AgentTheme.type.label)
            }
            MetaText(listOfNotNull(roleLabel(person.role), "@${person.handle}", person.email).joinToString(" · "), color = colors.textMuted)
        }
        val canAct = person.you || team.isOwner || (team.canManage && person.role == "member")
        if (canAct) AppButton(if (expanded) "Close" else "Change", ButtonTone.Quiet, enabled = team.busy == null, onClick = onToggle)
    }
}

/** What can be done to [person] from here: a role, rename yourself, remove, or leave. */
@Composable
private fun PersonActions(ui: FeedUi, person: PersonRow, team: TeamState, onDone: () -> Unit) {
    val colors = AgentTheme.colors
    var confirming by remember(person.id) { mutableStateOf(false) }
    val name = remember(person.id) { TextFieldState(person.name) }
    Panel {
        if (person.you) {
            BoxedField("Your name", name, "Your name", "Your name", onSubmit = { ui.emit(FeedEvent.Rename(name.text.toString())); onDone() })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton("Save name", ButtonTone.Secondary, enabled = team.busy == null) {
                    ui.emit(FeedEvent.Rename(name.text.toString()))
                    onDone()
                }
            }
        } else if (team.isOwner) {
            MetaText("ROLE", style = AgentTheme.type.overline, color = colors.textMuted)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("member", "admin", "owner").forEach { role ->
                    AppButton(
                        roleLabel(role),
                        if (role == person.role) ButtonTone.Primary else ButtonTone.Secondary,
                        enabled = team.busy == null && role != person.role,
                    ) { ui.emit(FeedEvent.SetRole(person.id, role)) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!confirming) {
            AppButton(if (person.you) "Leave the team" else "Remove ${person.name}", ButtonTone.Destructive, enabled = team.busy == null) { confirming = true }
        } else {
            Text(
                if (person.you) "Leave the team? This device and your others are signed out, and you need a new invite to come back."
                else "Remove ${person.name}? Their devices are signed out at once. What they wrote stays, under their name.",
                color = colors.text,
                style = AgentTheme.type.body,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                AppButton("Keep", ButtonTone.Secondary) { confirming = false }
                Spacer(Modifier.width(8.dp))
                AppButton(if (person.you) "Leave" else "Remove", ButtonTone.Destructive) {
                    ui.emit(FeedEvent.RemovePerson(person.id))
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun InviteLine(ui: FeedUi, invite: InviteRow, team: TeamState) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(invite.email ?: "Anyone with the link", color = colors.text, style = AgentTheme.type.body, maxLines = 1)
            MetaText("${roleLabel(invite.role)} · ${invite.expires}", color = colors.textMuted)
        }
        AppButton(if (team.busy == invite.id) "Revoking…" else "Revoke", ButtonTone.Quiet, enabled = team.busy == null) {
            ui.emit(FeedEvent.RevokeInvite(invite.id))
        }
    }
}

/** A new invite: who it is for and what they will be. Owners pick any role; admins invite members. */
@Composable
private fun InviteForm(ui: FeedUi, team: TeamState, onDone: () -> Unit) {
    val colors = AgentTheme.colors
    val email = remember { TextFieldState() }
    var role by remember { mutableStateOf("member") }
    Panel {
        BoxedField("Email (optional)", email, "Their email", "name@example.com")
        MetaText("With an email, only that address can use it.", Modifier.padding(top = 4.dp), color = colors.textFaint)
        if (team.isOwner) {
            Spacer(Modifier.height(10.dp))
            MetaText("THEY JOIN AS", style = AgentTheme.type.overline, color = colors.textMuted)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("member", "admin", "owner").forEach { r ->
                    AppButton(roleLabel(r), if (r == role) ButtonTone.Primary else ButtonTone.Secondary) { role = r }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Cancel", ButtonTone.Secondary, onClick = onDone)
            Spacer(Modifier.width(8.dp))
            AppButton(if (team.busy == "invite") "Making…" else "Make invite", ButtonTone.Primary, enabled = team.busy == null) {
                ui.emit(FeedEvent.InvitePerson(role, email.text.toString().trim().ifEmpty { null }))
                onDone()
            }
        }
    }
}

/** The invite just made: its link and its code, to copy and send however you like. Shown once. */
@Composable
private fun CreatedInvite(ui: FeedUi, created: CreatedInviteRow) {
    val colors = AgentTheme.colors
    val clipboard = LocalClipboardManager.current
    Panel(tint = true) {
        Text(
            "Send this to ${created.email ?: "them"}. It works once, for 7 days, and they join as ${if (created.role == "member") "a" else "an"} ${created.role}.",
            color = colors.text,
            style = AgentTheme.type.body,
        )
        Spacer(Modifier.height(8.dp))
        MetaText("LINK", style = AgentTheme.type.overline, color = colors.textMuted)
        Text(created.link, Modifier.padding(top = 2.dp), color = colors.text, style = AgentTheme.type.code)
        Spacer(Modifier.height(6.dp))
        MetaText("OR THE CODE", style = AgentTheme.type.overline, color = colors.textMuted)
        Text(created.code, Modifier.padding(top = 2.dp), color = colors.text, style = AgentTheme.type.code)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Copy code", ButtonTone.Secondary) { clipboard.setText(AnnotatedString(created.code)) }
            Spacer(Modifier.width(8.dp))
            AppButton("Copy link", ButtonTone.Primary, icon = Icons.Link) { clipboard.setText(AnnotatedString(created.link)) }
            Spacer(Modifier.width(8.dp))
            AppButton("Done", ButtonTone.Quiet) { ui.emit(FeedEvent.DismissInvite) }
        }
    }
}

/** A boxed area under a row, for what it opens. */
@Composable
private fun Panel(tint: Boolean = false, content: @Composable () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (tint) colors.accent.copy(alpha = 0.08f) else colors.surfaceSunken)
            .border(1.dp, if (tint) colors.accent.copy(alpha = 0.4f) else colors.border, shape)
            .padding(12.dp),
    ) { content() }
}
