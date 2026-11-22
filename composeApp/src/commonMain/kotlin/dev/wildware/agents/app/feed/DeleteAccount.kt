package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput

/** What the person types to confirm, as the server checks it (DELETE /api/me). */
internal const val DeleteAccountWord = "DELETE"

/**
 * "Delete my account" (App Store guideline 5.1.1(v)), at the foot of the settings sheet. Folded until
 * asked for. An owner is told to hand the team on first, and gets no button: deleting an account
 * never deletes a team other people use. Anyone else confirms with their password and by typing
 * DELETE; the dashboard then deletes the account in every team on that server and this device signs
 * out. Hidden on a server from before teams.
 */
@Composable
internal fun DeleteAccountGroup(ui: FeedUi) {
    val team = ui.state.team
    LaunchedEffect(Unit) { if (!team.loaded) ui.emit(FeedEvent.ReadPeople) }
    if (!team.supported || !team.loaded || team.me == null) return
    val colors = AgentTheme.colors
    var open by remember { mutableStateOf(false) }
    SettingsHeading("Your account")
    if (!open) {
        SettingsLinkRow(Icons.Trash, "Delete my account", "") { open = true }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (team.isOwner) {
            Text(
                "You own this team, so your account can't be deleted yet. Make someone else an owner in People, " +
                    "or delete the team from your account on the website, then come back here. " +
                    "Deleting an account never deletes a team other people use.",
                color = colors.text,
                style = AgentTheme.type.body,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                AppButton("Not now", ButtonTone.Secondary) { open = false }
                Spacer(Modifier.width(8.dp))
                AppButton("Open People", ButtonTone.Primary) {
                    ui.alertsOpen = false
                    ui.peopleOpen = true
                }
            }
            return@Column
        }
        val password = remember { TextFieldState() }
        val typed = remember { TextFieldState() }
        Text(
            "This deletes your account on this dashboard: your name, email and password, in every team here. " +
                "All your devices are signed out. What you wrote stays, shown as \"Deleted person\". This can't be undone.",
            color = colors.text,
            style = AgentTheme.type.body,
        )
        Spacer(Modifier.height(12.dp))
        DeleteField("Your password", password, "Your password", secret = true)
        Spacer(Modifier.height(10.dp))
        DeleteField("Type $DeleteAccountWord to confirm", typed, DeleteAccountWord, secret = false)
        team.problem?.let {
            Text(
                it,
                Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.error,
                style = AgentTheme.type.body,
            )
        }
        val confirmed = typed.text.toString().trim() == DeleteAccountWord
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            AppButton("Keep my account", ButtonTone.Secondary) { open = false }
            Spacer(Modifier.width(8.dp))
            AppButton(
                if (team.busy == DeleteAccountBusy) "Deleting…" else "Delete my account",
                ButtonTone.Destructive,
                enabled = confirmed && team.busy == null,
            ) { ui.emit(FeedEvent.DeleteAccount(password.text.toString().ifEmpty { null })) }
        }
    }
}

/** TeamState.busy while the account is being deleted. */
internal const val DeleteAccountBusy = "delete-account"

@Composable
private fun DeleteField(label: String, state: TextFieldState, placeholder: String, secret: Boolean) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column {
        MetaText(label.uppercase(), style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 12.dp),
        ) {
            TextInput(state, label, placeholder, Modifier.fillMaxWidth(), secret = secret)
        }
    }
}
