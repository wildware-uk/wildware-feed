package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * The way into GitHub from Settings: a row saying which accounts are connected. Shown only where
 * the dashboard has GitHub set up (server migration 080).
 */
@Composable
internal fun GitHubEntry(ui: FeedUi) {
    val github = ui.state.github
    if (!github.status.available) return
    val connected = github.status.installations
    val now = when (connected.size) {
        0 -> "Not connected"
        1 -> connected.single().account
        else -> "${connected.size} accounts"
    }
    SettingsLinkRow(Icons.PullRequest, "GitHub", now) {
        ui.alertsOpen = false
        ui.githubOpen = true
    }
}

/**
 * GitHub: pull requests on cards. An owner connects the team's GitHub accounts (the GitHub App's
 * install page opens in the browser); everyone sees which are connected.
 */
@Composable
fun GitHubSettingsSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val github = ui.state.github
    val status = github.status
    val uris = LocalUriHandler.current
    val close = { ui.githubOpen = false }
    // Coming back from the browser: read it again, as the install may have finished there.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) ui.emit(FeedEvent.ReadGitHub) }
    LaunchedEffect(github.connectUrl) {
        val url = github.connectUrl ?: return@LaunchedEffect
        runCatching { uris.openUri(url) }
        ui.emit(FeedEvent.GitHubPageOpened)
    }

    SettingsSheet("GitHub", safeArea, onClose = close) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(
                "Agents link their pull requests to cards. Each card then shows its CI and reviews, live, " +
                    "and the agent is told in the thread when CI fails or a reviewer asks for changes.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = colors.textMuted,
                style = AgentTheme.type.body,
            )
            SettingsHeading("Connected")
            if (status.installations.isEmpty()) {
                MetaText(if (github.loaded) "No GitHub account yet." else "Reading…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.textMuted)
            }
            status.installations.forEach { install ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(install.account, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.label)
                    if (status.canManage) {
                        AppButton("Disconnect", ButtonTone.Quiet, enabled = !github.busy) { ui.emit(FeedEvent.DisconnectGitHub(install.installationId)) }
                    }
                }
            }
            if (status.canManage) {
                AppButton(
                    if (status.installations.isEmpty()) "Connect GitHub" else "Add or change repos",
                    if (status.installations.isEmpty()) ButtonTone.Primary else ButtonTone.Secondary,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    icon = Icons.External,
                    enabled = !github.busy,
                ) { ui.emit(FeedEvent.ConnectGitHub) }
                MetaText(
                    "GitHub opens in your browser. Pick the account and repos, then come back here.",
                    Modifier.padding(horizontal = 16.dp),
                    color = colors.textMuted,
                )
            } else {
                MetaText("A team owner connects GitHub.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.textMuted)
            }
            github.problem?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.error,
                    style = AgentTheme.type.body,
                )
            }
        }
    }
}
