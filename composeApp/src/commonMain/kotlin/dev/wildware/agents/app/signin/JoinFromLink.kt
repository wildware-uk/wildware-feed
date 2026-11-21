package dev.wildware.agents.app.signin

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.InviteLink

/**
 * An invite link opened while this device is signed in. The invite makes a new person: into
 * another team, that team is added beside the open one; into the open team, this device's sign-in
 * there is replaced, so it signs out of that team first. Said plainly, with a clear way out.
 */
@Composable
fun JoinFromLinkDialog(invite: InviteLink, signedInTo: String?, onCancel: () -> Unit, onJoin: () -> Unit) {
    val colors = AgentTheme.colors
    val where = invite.server?.let { " on ${it.removePrefix("https://")}" }.orEmpty()
    val leaving = signedInTo?.let { "This device is signed in to ${it.removePrefix("https://")}. " }.orEmpty()
    val sameTeam = signedInTo != null && invite.server != null && dev.wildware.agents.app.session.TeamList.sameServer(invite.server, signedInTo)
    if (!sameTeam && signedInTo != null) return AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text("Join a team?", style = AgentTheme.type.title) },
        text = {
            Text(
                "This invite makes you a new account$where. ${leaving}It stays signed in there too: switch teams at the top of the sidebar.",
                style = AgentTheme.type.body,
            )
        },
        confirmButton = { AppButton("Join this team too", ButtonTone.Primary, onClick = onJoin) },
        dismissButton = { AppButton("Not yet", ButtonTone.Quiet, onClick = onCancel) },
    )
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = colors.surfaceRaised,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = AgentTheme.shapes.large,
        title = { Text("Join a team?", style = AgentTheme.type.title) },
        text = {
            Text(
                "This invite makes you a new account$where. ${leaving}To join with it, this device signs out first.",
                style = AgentTheme.type.body,
            )
        },
        confirmButton = { AppButton("Sign out and join", ButtonTone.Primary, onClick = onJoin) },
        dismissButton = { AppButton("Stay signed in", ButtonTone.Quiet, onClick = onCancel) },
    )
}
