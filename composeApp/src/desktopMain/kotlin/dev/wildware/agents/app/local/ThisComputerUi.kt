package dev.wildware.agents.app.local

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.IconAction
import dev.wildware.agents.app.feed.MetaText
import dev.wildware.agents.app.feed.lift

/** The "This computer" dialog over the window, while it is open. */
@Composable
fun ThisComputerLayer(settings: ThisComputer) {
    val view by settings.view.collectAsState()
    val server by settings.server.collectAsState()
    val step = view.step ?: return
    ThisComputerDialog(
        step = step,
        server = server,
        busy = view.busy,
        problem = view.problem,
        actions = ThisComputerActions(
            close = settings::close,
            back = settings::back,
            setPhoneAccess = settings::setPhoneAccess,
            acceptWarning = settings::acceptWarning,
            choosePassword = settings::choosePassword,
            connectAgent = settings::connectAgent,
        ),
    )
}

/** What the dialog's controls do. */
class ThisComputerActions(
    val close: () -> Unit = {},
    val back: () -> Unit = {},
    val setPhoneAccess: (Boolean) -> Unit = {},
    val acceptWarning: () -> Unit = {},
    val choosePassword: (first: String, second: String) -> Unit = { _, _ -> },
    val connectAgent: () -> Unit = {},
)

/**
 * Settings for the server this app runs: the phone switch (warning, then a password) and
 * "Connect Claude Code" (the plugin's two settings, shown once). Escape or a click outside closes
 * it, except while something is changing.
 */
@Composable
fun ThisComputerDialog(
    step: ThisComputerStep,
    server: LocalServerState,
    busy: Boolean,
    problem: String?,
    actions: ThisComputerActions,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val close = { if (!busy) actions.close() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "This computer" }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false
                }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (step) {
                ThisComputerStep.Overview -> Overview(server, busy, problem, actions)
                ThisComputerStep.PhoneWarning -> PhoneWarning(actions)
                ThisComputerStep.PhonePassword -> PhonePassword(busy, problem, actions)
                is ThisComputerStep.AgentConnected -> AgentConnected(step.connection, actions)
            }
        }
    }
}

@Composable
private fun Overview(server: LocalServerState, busy: Boolean, problem: String?, actions: ThisComputerActions) {
    val colors = AgentTheme.colors
    Title("This computer")
    Text(
        when {
            server.localUrl != null -> "Your feed runs here, at ${server.localUrl}."
            server.problem != null -> server.problem
            else -> "Your feed runs here, on this computer."
        },
        Modifier.padding(top = 6.dp),
        color = colors.textMuted,
        style = AgentTheme.type.body,
    )
    Divider()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AgentTheme.shapes.medium)
            .toggleable(server.phoneAccess, enabled = !busy, role = Role.Switch) { actions.setPhoneAccess(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Let my phone connect", color = colors.text, style = AgentTheme.type.label)
            Text(
                if (server.phoneAccess) "Phones on this network use the address below." else "Only this computer can reach your feed.",
                Modifier.padding(top = 2.dp),
                color = colors.textMuted,
                style = AgentTheme.type.meta,
            )
        }
        Switch(
            checked = server.phoneAccess,
            onCheckedChange = null,
            enabled = !busy,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = colors.onAccent,
                checkedBorderColor = colors.accent,
                uncheckedTrackColor = colors.surfaceSunken,
                uncheckedThumbColor = colors.textMuted,
                uncheckedBorderColor = colors.borderStrong,
            ),
        )
    }
    if (server.phoneAccess && server.phoneUrl != null) {
        Spacer(Modifier.height(10.dp))
        CopyRow("Phone address", server.phoneUrl)
    }
    Divider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Connect Claude Code", color = colors.text, style = AgentTheme.type.label)
            Text("Give Claude Code on this computer its own key.", Modifier.padding(top = 2.dp), color = colors.textMuted, style = AgentTheme.type.meta)
        }
        AppButton(if (busy) "Working…" else "Connect", ButtonTone.Secondary, enabled = !busy, onClick = actions.connectAgent)
    }
    if (problem != null) ProblemText(problem, Modifier.padding(top = 14.dp))
    Buttons {
        AppButton("Done", ButtonTone.Primary, enabled = !busy, onClick = actions.close)
    }
}

@Composable
private fun PhoneWarning(actions: ThisComputerActions) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Title("Let my phone connect?")
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.warning.copy(alpha = 0.10f))
            .border(1.dp, colors.warning.copy(alpha = 0.4f), shape)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Bell, null, tint = colors.warning, modifier = Modifier.padding(top = 2.dp).size(15.dp))
        Text(PHONE_ACCESS_WARNING, Modifier.padding(start = 10.dp), color = colors.text, style = AgentTheme.type.body)
    }
    Buttons {
        AppButton("Cancel", ButtonTone.Quiet, onClick = actions.back)
        AppButton("Continue", ButtonTone.Primary, onClick = actions.acceptWarning)
    }
}

@Composable
private fun PhonePassword(busy: Boolean, problem: String?, actions: ThisComputerActions) {
    val colors = AgentTheme.colors
    val first = rememberTextFieldState()
    val second = rememberTextFieldState()
    val submit = { actions.choosePassword(first.text.toString(), second.text.toString()) }
    Title("Choose a password for your phone")
    Text(
        "Your phone signs in with it once, then keeps its own key.",
        Modifier.padding(top = 6.dp),
        color = colors.textMuted,
        style = AgentTheme.type.body,
    )
    Spacer(Modifier.height(16.dp))
    PasswordField("Password", first, isError = false, imeAction = ImeAction.Next)
    Spacer(Modifier.height(12.dp))
    PasswordField("Type it again", second, isError = problem != null, imeAction = ImeAction.Go, onSubmit = submit)
    if (problem != null) ProblemText(problem, Modifier.padding(top = 8.dp))
    Buttons {
        AppButton("Cancel", ButtonTone.Quiet, enabled = !busy, onClick = actions.back)
        AppButton(
            if (busy) "Turning on…" else "Let my phone connect",
            ButtonTone.Primary,
            icon = if (busy) Icons.Clock else null,
            enabled = !busy,
            onClick = submit,
        )
    }
}

@Composable
private fun AgentConnected(connection: AgentConnection, actions: ThisComputerActions) {
    val colors = AgentTheme.colors
    Title("Connect Claude Code")
    Text(
        "Put these in the plugin's settings in Claude Code.",
        Modifier.padding(top = 6.dp),
        color = colors.textMuted,
        style = AgentTheme.type.body,
    )
    Spacer(Modifier.height(16.dp))
    CopyRow("dashboard_url", connection.dashboardUrl)
    Spacer(Modifier.height(12.dp))
    CopyRow("agent_token", connection.agentToken)
    Text(
        "The token is shown only once. Copy it now; you can always make another.",
        Modifier.padding(top = 12.dp),
        color = colors.textMuted,
        style = AgentTheme.type.label,
    )
    Buttons {
        AppButton("Done", ButtonTone.Primary, onClick = actions.back)
    }
}

/** A labelled value in a sunken box, with a button that copies it. */
@Composable
private fun CopyRow(label: String, value: String) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val clipboard = LocalClipboardManager.current
    Column {
        MetaText(label, style = AgentTheme.type.overline, color = colors.textMuted)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, shape)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                Modifier.weight(1f).padding(vertical = 10.dp),
                color = colors.text,
                style = AgentTheme.type.label.copy(fontFamily = FontFamily.Monospace),
            )
            IconAction(Icons.Copy, "Copy $label") { clipboard.setText(AnnotatedString(value)) }
        }
    }
}

@Composable
private fun PasswordField(label: String, state: TextFieldState, isError: Boolean, imeAction: ImeAction, onSubmit: (() -> Unit)? = null) {
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
                .border(1.dp, if (isError) colors.error else colors.border, shape)
                .padding(horizontal = 12.dp),
        ) {
            TextInput(
                state,
                label = label,
                placeholder = "",
                secret = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
                onSubmit = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProblemText(text: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Row(
        modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Close, null, tint = colors.error, modifier = Modifier.size(13.dp))
        Text(text, color = colors.error, style = AgentTheme.type.label)
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = AgentTheme.colors.text, style = AgentTheme.type.title)
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(AgentTheme.colors.border))
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
