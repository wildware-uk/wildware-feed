package dev.wildware.agents.app.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectableGroup
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wildware.agents.SignInException
import dev.wildware.agents.SignInFailure
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.design.AppMark
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.MetaText
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.safePadding
import dev.wildware.agents.app.feed.windowBackground
import kotlinx.coroutines.launch

/** Which box a sign-in problem belongs under, so the message sits where the fix is. */
private enum class Field { Server, Password, Device, General }

private data class Problem(val field: Field, val message: String)

/** What each failure says, in words that tell the owner what to do next. */
private fun SignInException.problem(): Problem = when (failure) {
    SignInFailure.Unreachable -> Problem(Field.Server, message ?: "Couldn't reach that address. Check it and your connection.")
    SignInFailure.NotADashboard -> Problem(Field.Server, message ?: "That address isn't a Wildware Feed server.")
    SignInFailure.WrongPassword -> Problem(Field.Password, "That password isn't right. Try again.")
    SignInFailure.TooManyAttempts -> Problem(
        Field.General,
        retryAfterSeconds?.let { "Too many tries. Sign-in is locked for ${minutes(it)}." } ?: "Too many tries. Wait a few minutes, then try again.",
    )
    SignInFailure.NotSetUp -> Problem(Field.General, "This dashboard has no owner password yet. Set one on the server first.")
    SignInFailure.Refused -> Problem(Field.Device, message ?: "The dashboard turned that down.")
}

/** Where to sign in: a server this app runs itself, or one somewhere else. */
enum class SignInChoice { ThisComputer, Server }

/** "This computer" where the app can run a server itself (the desktop app); otherwise a server. */
fun defaultSignInChoice(canHostLocally: Boolean): SignInChoice =
    if (canHostLocally) SignInChoice.ThisComputer else SignInChoice.Server

private fun minutes(seconds: Long): String {
    val m = (seconds + 59) / 60
    return if (m <= 1) "about a minute" else "$m minutes"
}

/**
 * Sign in with the dashboard's address, the owner password and a name for this device. The
 * password is sent once and never kept; the device key that comes back is.
 *
 * Where the app can run a server itself ([signInHere] is set), it first offers "This computer",
 * which needs only the device's name, or "A server", the form above.
 */
@Composable
fun SignInScreen(
    deviceName: String,
    notice: String?,
    safeArea: SafeArea,
    /** Opens the app on built-in sample data, for a look round without a dashboard. */
    tryDemo: (() -> Unit)? = null,
    /** Starts the server on this computer and signs in to it; null where the app can't run one (phones). */
    signInHere: (suspend (device: String) -> Unit)? = null,
    /** Why the server on this computer isn't running, when it tried and couldn't. */
    hostProblem: String? = null,
    /** Which choice shows first. */
    startWith: SignInChoice = defaultSignInChoice(signInHere != null),
    signIn: suspend (server: String, password: String, device: String) -> Unit,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val scope = rememberCoroutineScope()
    val server = rememberTextFieldState()
    val password = rememberTextFieldState()
    val device = rememberTextFieldState(deviceName)
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Problem?>(null) }
    var choice by remember { mutableStateOf(if (signInHere == null) SignInChoice.Server else startWith) }
    val here = choice == SignInChoice.ThisComputer && signInHere != null

    val submitHere: () -> Unit = submit@{
        if (busy || signInHere == null) return@submit
        busy = true
        problem = null
        scope.launch {
            try {
                signInHere(device.text.toString())
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                problem = Problem(Field.General, failed.message ?: "The server on this computer didn't start. Try again.")
            } finally {
                busy = false
            }
        }
    }

    val submitServer: () -> Unit = submit@{
        if (busy) return@submit
        if (server.text.isBlank()) {
            problem = Problem(Field.Server, "Type your dashboard's address.")
            return@submit
        }
        if (password.text.isEmpty()) {
            problem = Problem(Field.Password, "Type the owner password.")
            return@submit
        }
        busy = true
        problem = null
        scope.launch {
            try {
                signIn(server.text.toString(), password.text.toString(), device.text.toString())
            } catch (failed: SignInException) {
                problem = failed.problem()
            } finally {
                busy = false
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowBackground(colors)) {
    // Sits a little above the middle on tall windows, and at the top on short ones.
    val lift = ((maxHeight - 660.dp) / 2).coerceAtLeast(48.dp)
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // The safe area's bottom already includes the keyboard; it is read while laying out, so
            // the form follows the keyboard without redrawing.
            .safePadding(safeArea, top = 24.dp, bottom = 24.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = lift)) {
            AppMark(52.dp)
            Spacer(Modifier.height(20.dp))
            Text("Sign in", Modifier.semantics { heading() }, color = colors.text, style = type.display)
            if (signInHere != null) {
                Spacer(Modifier.height(16.dp))
                ChoiceTabs(choice) {
                    if (!busy) {
                        choice = it
                        problem = null
                    }
                }
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (here) "Your feed runs right here, on this computer. No address or password needed."
                else "Connect ${dev.wildware.agents.app.ProductName} to your server. The password is used once; the device keeps its own key.",
                color = colors.textMuted,
                style = type.body,
            )
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Banner(notice, isError = false)
            }
            Spacer(Modifier.height(24.dp))
            if (here) {
                LabelledField(
                    "Name for this device", device, problem.takeIf { it?.field == Field.Device }?.message,
                    hint = "Shown in the dashboard's device list, so you can revoke it later.",
                    keyboard = KeyboardOptions(imeAction = ImeAction.Go),
                    onSubmit = submitHere,
                )
                (problem?.takeIf { it.field == Field.General }?.message ?: hostProblem)?.let {
                    Spacer(Modifier.height(16.dp))
                    Banner(it, isError = true)
                }
                Spacer(Modifier.height(22.dp))
                AppButton(
                    if (busy) "Starting…" else "Start on this computer",
                    ButtonTone.Primary,
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (busy) Icons.Clock else null,
                    enabled = !busy,
                    onClick = submitHere,
                )
            } else {
            LabelledField(
                "Dashboard address", server, problem.takeIf { it?.field == Field.Server }?.message,
                placeholder = "https://dashboard.example.com",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(14.dp))
            LabelledField(
                "Owner password", password, problem.takeIf { it?.field == Field.Password }?.message,
                secret = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(14.dp))
            LabelledField(
                "Name for this device", device, problem.takeIf { it?.field == Field.Device }?.message,
                hint = "Shown in the dashboard's device list, so you can revoke it later.",
                keyboard = KeyboardOptions(imeAction = ImeAction.Go),
                onSubmit = submitServer,
            )
            problem?.takeIf { it.field == Field.General }?.let {
                Spacer(Modifier.height(16.dp))
                Banner(it.message, isError = true)
            }
            Spacer(Modifier.height(22.dp))
            AppButton(
                if (busy) "Signing in…" else "Sign in",
                ButtonTone.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = if (busy) Icons.Clock else null,
                enabled = !busy,
                onClick = submitServer,
            )
            if (tryDemo != null) {
                Spacer(Modifier.height(18.dp))
                AppButton(
                    "Try a demo",
                    ButtonTone.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = tryDemo,
                )
                Text(
                    "No dashboard yet? Look round on sample projects. Nothing leaves this device.",
                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                    color = colors.textFaint,
                    style = AgentTheme.type.meta,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            }
        }
    }
    }
}

/** "This computer" or "A server", side by side, at the top of the screen. */
@Composable
private fun ChoiceTabs(choice: SignInChoice, onChoose: (SignInChoice) -> Unit) {
    val colors = AgentTheme.colors
    val outer = AgentTheme.shapes.medium
    val inner = AgentTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, outer)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        for ((option, label) in listOf(SignInChoice.ThisComputer to "This computer", SignInChoice.Server to "A server")) {
            val selected = option == choice
            Box(
                Modifier
                    .weight(1f)
                    .clip(inner)
                    .background(if (selected) colors.surfaceRaised else Color.Transparent)
                    .border(1.dp, if (selected) colors.borderStrong else Color.Transparent, inner)
                    .pressable(label, inner, role = Role.Tab, labelIsShown = true) { onChoose(option) }
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) colors.text else colors.textMuted,
                    style = AgentTheme.type.label,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    state: TextFieldState,
    error: String?,
    secret: Boolean = false,
    placeholder: String = "",
    hint: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
) {
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
                .border(1.dp, if (error != null) colors.error else colors.border, shape)
                .padding(horizontal = 12.dp),
        ) {
            TextInput(
                state,
                label = label,
                placeholder = placeholder,
                secret = secret,
                keyboard = keyboard,
                onSubmit = onSubmit,
                modifier = Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
            )
        }
        if (error != null) {
            Row(
                Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Close, null, tint = colors.error, modifier = Modifier.size(13.dp))
                Text(error, color = colors.error, style = AgentTheme.type.label)
            }
        } else if (hint != null) {
            Text(hint, Modifier.padding(top = 6.dp), color = colors.textFaint, style = AgentTheme.type.meta)
        }
    }
}

/** A boxed message: why the app signed out, or a problem that isn't about one box. */
@Composable
private fun Banner(text: String, isError: Boolean) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val tint = if (isError) colors.error else colors.warning
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.4f), shape)
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (isError) Icons.Close else Icons.Bell, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(15.dp))
        Text(text, Modifier.padding(start = 10.dp), color = colors.text, style = AgentTheme.type.label)
    }
}
