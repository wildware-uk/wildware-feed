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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import kotlinx.coroutines.delay

/** The address of the dashboard this screen is signed in to, e.g. `https://team.example.com`. */
val LocalDashboardAddress = staticCompositionLocalOf { "" }

/**
 * The words the owner pastes into an agent so it joins this dashboard: install the plugin, ask to
 * join, and wait for the owner to tap Allow. It holds the dashboard's address and nothing secret:
 * anyone can already ask to join at that address, and nobody gets in without Allow, so the prompt
 * is safe to lose, share or paste twice.
 */
object ConnectPrompt {
    const val MARKETPLACE = "wildware-uk/wildware-feed-agents"
    const val PLUGIN = "wildware-feed@wildware-feed"
    const val OTHER_CLIENTS = "https://github.com/wildware-uk/wildware-feed-agents/tree/main/integrations"

    /** [address] is the dashboard's origin; [channel], by slug, is where the agent should post, if anywhere. */
    fun text(address: String, channel: String? = null): String {
        val base = address.trim().trimEnd('/')
        val feed = "node \"<installPath>/bin/feed.mjs\""
        val post = channel?.takeIf { it.isNotBlank() }?.let { "\nOnce you are connected, call subscribe({channel: \"$it\"}) and post your updates in that channel.\n" }.orEmpty()
        return """
            |Please connect yourself to my Wildware Feed dashboard at $base. You can then post updates there, and I can answer you from my phone.
            |
            |If you are Claude Code, run these from this project's folder:
            |
            |1. Install the plugin:
            |   claude plugin marketplace add $MARKETPLACE
            |   claude plugin install $PLUGIN --config dashboard_url=$base
            |   Already installed? `claude plugin configure $PLUGIN --json` shows its dashboard_url under inputs. If it is empty, set it with
            |   echo '{"dashboard_url":"$base"}' | claude plugin configure $PLUGIN --values-stdin
            |   If it is a different dashboard, ask me before you change it.
            |
            |2. Ask to join. The plugin's folder is the installPath of $PLUGIN in `claude plugin list --json`. Pick a short name for this machine and project, like laptop-myrepo, then run:
            |   $feed pair start --url $base --name "<name>"
            |   Tell me the code it prints. I will check it and tap Allow in the app.
            |
            |3. Wait for me:
            |   $feed pair wait
            |   When it says "Paired", ask me to type /reload-plugins (or restart Claude Code). Then call register_session.
            |
            |Any other agent (Codex, Cursor, Gemini and more): follow the setup for yours at $OTHER_CLIENTS with $base as the dashboard URL. The MCP server is $base/mcp (Streamable HTTP). You get its token by pairing: read $base/api/pair and follow its steps, and I will tap Allow in the app.
            |$post
            |I never need to give you a password or a token for this. If something asks for one, stop and ask me.
        """.trimMargin()
    }
}

/** The prompt's text on screen, for tests. */
internal const val PromptTag = "connect-prompt"

/** The way into "Connect an agent" from the account sheet. */
@Composable
internal fun ConnectAgentEntry(ui: FeedUi) {
    SettingsHeading("Agents")
    SettingsLinkRow(Icons.Plus, "Connect an agent", "") {
        ui.alertsOpen = false
        ui.connectOpen = true
    }
}

/** "Connect an agent", under the agents in the members list. */
@Composable
internal fun ConnectAgentButton(ui: FeedUi, inset: Dp) {
    Spacer(Modifier.height(6.dp))
    AppButton("Connect an agent", ButtonTone.Quiet, Modifier.padding(horizontal = inset), icon = Icons.Plus) { ui.connectOpen = true }
}

/**
 * A ready prompt for any agent, with one big Copy. The agent installs the plugin, asks to join
 * this dashboard and says a code; the owner checks it and taps Allow when [JoinPrompt] shows it.
 */
@Composable
fun ConnectAgentSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val clipboard = LocalClipboardManager.current
    val prompt = ConnectPrompt.text(LocalDashboardAddress.current, ui.state.current.slug)
    // Counts each copy, so "Copied" shows again for a second copy.
    var copied by remember { mutableIntStateOf(0) }
    LaunchedEffect(copied) {
        if (copied > 0) {
            delay(4_000)
            copied = 0
        }
    }
    SettingsSheet("Connect an agent", safeArea, onClose = { ui.connectOpen = false }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Paste this into Claude Code or any other agent. It installs Wildware Feed and asks to join this dashboard. " +
                    "When it does, check the code it tells you and tap Allow.",
                color = colors.text,
                style = AgentTheme.type.body,
            )
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    prompt,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(shape)
                        .background(colors.surfaceSunken)
                        .border(1.dp, colors.border, shape)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                        .testTag(PromptTag),
                    color = colors.text,
                    style = AgentTheme.type.code,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (copied > 0) {
                    Text(
                        "Copied. Now paste it into your agent.",
                        Modifier.weight(1f).padding(end = 8.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.accent,
                        style = AgentTheme.type.label,
                    )
                }
                AppButton("Copy prompt", ButtonTone.Primary, icon = Icons.Copy) {
                    clipboard.setText(AnnotatedString(prompt))
                    copied++
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "It holds only this dashboard's address, never a password or a key. Nothing joins until you tap Allow.",
                color = colors.textMuted,
                style = AgentTheme.type.label,
            )
        }
    }
}
