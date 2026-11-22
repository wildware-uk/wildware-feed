package dev.wildware.agents.app.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.HostedFailure
import dev.wildware.agents.HostedLoginException
import dev.wildware.agents.HostedLoginFlow
import dev.wildware.agents.HostedTeam
import dev.wildware.agents.HostedTeams
import dev.wildware.agents.SignInException
import dev.wildware.agents.SignInFailure
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.MetaText
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Where the hosted "Log in" is: the email, the code from it, or the teams that email is in. */
enum class HostedStep { Email, Code, Teams }

/** Which part of the form a problem belongs under. */
enum class HostedField { Email, Code, General }

/** A problem to show, and where. */
data class HostedProblem(val field: HostedField, val message: String)

/**
 * The hosted "Log in"'s screen state: which step, what was typed, the teams found. Screenshots
 * start it at any step; nothing here reaches a network.
 */
@Stable
class HostedState(
    email: String = "",
    step: HostedStep = HostedStep.Email,
    teams: HostedTeams? = null,
    problem: HostedProblem? = null,
    code: String = "",
) {
    val email = TextFieldState(email)
    val code = TextFieldState(code)
    var step: HostedStep by mutableStateOf(step)
    var teams: HostedTeams? by mutableStateOf(teams)
    var problem: HostedProblem? by mutableStateOf(problem)

    /** Waiting on the service. */
    var busy: Boolean by mutableStateOf(false)

    /** The team being signed in to, by slug. */
    var joining: String? by mutableStateOf(null)
}

/** The 6 digits of a code, from whatever was typed or pasted ("123 456", "Your code is 123456"). */
internal fun codeDigits(text: CharSequence): String = text.filter(Char::isDigit).take(6).toString()

private fun minutesText(seconds: Long?): String {
    if (seconds == null) return "a few minutes"
    val m = (seconds + 59) / 60
    return when {
        m <= 1 -> "about a minute"
        m < 90 -> "$m minutes"
        else -> "about ${(m + 30) / 60} hours"
    }
}

/** What each failure says, in words that say what to do next. */
internal fun HostedLoginException.problem(): HostedProblem = when (failure) {
    HostedFailure.Unreachable -> HostedProblem(HostedField.General, "Couldn't reach the sign-in service. Check your connection and try again.")
    HostedFailure.BadEmail -> HostedProblem(HostedField.Email, "That doesn't look like an email address.")
    HostedFailure.WrongCode -> HostedProblem(HostedField.Code, "That code isn't right. Check the email and try again.")
    HostedFailure.Locked -> HostedProblem(HostedField.Code, "Too many wrong codes. Ask for a new code, or try again tomorrow.")
    HostedFailure.CodeExpired -> HostedProblem(HostedField.Code, "That code has run out or was used already. Ask for a new one.")
    HostedFailure.TooManyTries -> HostedProblem(HostedField.General, "Too many tries. Wait ${minutesText(retryAfterSeconds)}, then try again.")
    HostedFailure.SignedOut -> HostedProblem(HostedField.General, "Your sign-in ran out. Start again with your email.")
    HostedFailure.NoSuchTeam -> HostedProblem(HostedField.General, "That team isn't there any more, or you're no longer in it.")
    HostedFailure.ServerUnavailable -> HostedProblem(HostedField.General, "That team's server isn't answering. Try again in a minute.")
    HostedFailure.Refused -> HostedProblem(HostedField.General, message ?: "The sign-in service turned that down.")
}

private fun SignInException.hostedProblem(): HostedProblem = when (failure) {
    SignInFailure.BadTicket -> HostedProblem(HostedField.General, "That sign-in ran out before the team got it. Pick the team again.")
    SignInFailure.TooManyAttempts -> HostedProblem(HostedField.General, "Too many tries. Wait ${minutesText(retryAfterSeconds)}, then pick the team again.")
    else -> HostedProblem(HostedField.General, message ?: "The team's server turned the sign-in down.")
}

/**
 * "Log in": an email, the 6-digit code sent to it, then the team (a list when there are several).
 * The device key that comes back is saved as "Connect to a host" saves one; the account service's
 * own sign-in is never kept.
 */
@Composable
internal fun HostedLoginForm(
    flow: HostedLoginFlow,
    state: HostedState,
    deviceName: String,
    /** Signs this device in to the team and opens its feed. */
    join: suspend (team: HostedTeam, device: String) -> Unit,
    tryDemo: (() -> Unit)?,
    /** Whether to offer the website that makes hosted teams. False in the Apple apps (guideline 3.1.1). */
    linksToSignUp: Boolean = true,
) {
    val colors = AgentTheme.colors
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun fail(failed: HostedLoginException) {
        val problem = failed.problem()
        state.problem = problem
        if (failed.failure == HostedFailure.SignedOut) {
            state.step = HostedStep.Email
            state.code.clearText()
        }
    }

    val pickTeam: (HostedTeam) -> Unit = pick@{ team ->
        if (state.busy) return@pick
        state.busy = true
        state.joining = team.slug
        state.problem = null
        scope.launch {
            try {
                join(team, deviceName)
            } catch (failed: HostedLoginException) {
                fail(failed)
                // The team may have gone: show the list as it is now.
                if (failed.failure == HostedFailure.NoSuchTeam) runCatching { state.teams = flow.teams() }
            } catch (failed: SignInException) {
                state.problem = failed.hostedProblem()
            } finally {
                state.busy = false
                state.joining = null
            }
        }
    }

    val sendCode: () -> Unit = send@{
        if (state.busy) return@send
        val email = state.email.text.toString().trim()
        if (email.isEmpty() || '@' !in email) {
            state.problem = HostedProblem(HostedField.Email, if (email.isEmpty()) "Type your email." else "That doesn't look like an email address.")
            return@send
        }
        state.busy = true
        state.problem = null
        scope.launch {
            try {
                flow.sendCode(email)
                state.code.clearText()
                state.step = HostedStep.Code
            } catch (failed: HostedLoginException) {
                fail(failed)
            } finally {
                state.busy = false
            }
        }
    }

    val verify: () -> Unit = verify@{
        if (state.busy) return@verify
        val code = codeDigits(state.code.text)
        if (!flow.awaitingCode) {
            state.problem = HostedProblem(HostedField.Code, "That code can't be used now. Ask for a new one.")
            return@verify
        }
        if (code.length != 6) {
            state.problem = HostedProblem(HostedField.Code, "Type the 6-digit code from the email.")
            return@verify
        }
        state.busy = true
        state.problem = null
        scope.launch {
            var only: HostedTeam? = null
            try {
                val teams = flow.verify(code, deviceName)
                state.teams = teams
                state.step = HostedStep.Teams
                // One team that's open: no need to ask which.
                only = teams.teams.singleOrNull()?.takeIf { !it.readOnly }
            } catch (failed: HostedLoginException) {
                fail(failed)
                if (failed.failure == HostedFailure.WrongCode) state.code.clearText()
            } finally {
                state.busy = false
            }
            only?.let(pickTeam)
        }
    }

    val startOver: () -> Unit = {
        if (!state.busy) {
            state.problem = null
            state.code.clearText()
            state.teams = null
            state.step = HostedStep.Email
            flow.startOver()
        }
    }

    when (state.step) {
        HostedStep.Email -> {
            LabelledField(
                "Email", state.email, state.problem.takeIf { it?.field == HostedField.Email }?.message,
                placeholder = "you@example.com",
                hint = "We'll email you a 6-digit code. No password needed.",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                onSubmit = sendCode,
                contentType = ContentType.EmailAddress,
            )
            GeneralProblem(state)
            Spacer(Modifier.height(22.dp))
            AppButton(
                if (state.busy) "Sending…" else "Email me a code",
                ButtonTone.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = if (state.busy) Icons.Clock else null,
                enabled = !state.busy,
                onClick = sendCode,
            )
            Spacer(Modifier.height(10.dp))
            if (linksToSignUp) {
                AppButton("New here? Make a team", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    runCatching { uri.openUri(flow.signUpPage) }
                }
            }
            if (tryDemo != null) {
                Spacer(Modifier.height(18.dp))
                AppButton("Try a demo", ButtonTone.Secondary, modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = tryDemo)
                Text(
                    "No team yet? Look round on sample projects. Nothing leaves this device.",
                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                    color = colors.textFaint,
                    style = AgentTheme.type.meta,
                    textAlign = TextAlign.Center,
                )
            }
        }
        HostedStep.Code -> {
            // A pasted "123 456" or a whole sentence becomes the 6 digits, and the sixth one sends it.
            LaunchedEffect(state) {
                // Only what is typed from here on: coming back to this step doesn't send the code again.
                snapshotFlow { state.code.text.toString() }.drop(1).collect { typed ->
                    val digits = codeDigits(typed)
                    if (digits != typed) state.code.edit { replace(0, length, digits) }
                    // Typing again clears what was wrong with the last code.
                    if (digits.isNotEmpty() && state.problem?.field == HostedField.Code) state.problem = null
                    if (digits.length == 6) verify()
                }
            }
            SentTo(flow.email ?: state.email.text.toString(), enabled = !state.busy, onChange = startOver)
            Spacer(Modifier.height(18.dp))
            LabelledField(
                "Code", state.code, state.problem.takeIf { it?.field == HostedField.Code }?.message,
                placeholder = "123456",
                hint = "The 6 digits in the email. It works for 15 minutes.",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                onSubmit = verify,
                focusFirst = true,
                contentType = ContentType.SmsOtpCode,
            )
            GeneralProblem(state)
            Spacer(Modifier.height(22.dp))
            AppButton(
                if (state.busy) "Checking…" else "Continue",
                ButtonTone.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = if (state.busy) Icons.Clock else null,
                enabled = !state.busy,
                onClick = verify,
            )
            Spacer(Modifier.height(10.dp))
            AppButton("Send a new code", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                state.problem = null
                sendCode()
            }
        }
        HostedStep.Teams -> {
            val teams = state.teams?.teams.orEmpty()
            SentTo(flow.email ?: state.email.text.toString(), enabled = !state.busy, onChange = startOver, label = "SIGNED IN AS")
            Spacer(Modifier.height(18.dp))
            if (teams.isEmpty()) {
                if (linksToSignUp) {
                    Banner("This email isn't in a team yet. Make one on the website, or ask a team's owner to invite you.", isError = false)
                    Spacer(Modifier.height(22.dp))
                    AppButton("Make a team on the website", ButtonTone.Primary, modifier = Modifier.fillMaxWidth()) {
                        runCatching { uri.openUri(flow.signUpPage) }
                    }
                } else {
                    Banner("This email isn't in a team yet. Ask a team's owner to invite you.", isError = false)
                }
                Spacer(Modifier.height(10.dp))
                AppButton("Use a different email", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), onClick = startOver)
            } else {
                MetaText(if (teams.size == 1) "YOUR TEAM" else "CHOOSE A TEAM", style = AgentTheme.type.overline, color = colors.textMuted)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (team in teams) TeamRow(team, joining = state.joining == team.slug, enabled = !state.busy) { pickTeam(team) }
                }
                if (state.teams?.partial == true) {
                    Text(
                        "Some teams may be missing: only the ones you own could be listed just now.",
                        Modifier.padding(top = 8.dp),
                        color = colors.textFaint,
                        style = AgentTheme.type.meta,
                    )
                }
                GeneralProblem(state)
            }
        }
    }
}

@Composable
private fun GeneralProblem(state: HostedState) {
    state.problem?.takeIf { it.field == HostedField.General }?.let {
        Spacer(Modifier.height(16.dp))
        Banner(it.message, isError = true)
    }
}

/** Which email the code went to, with the way back to change it. */
@Composable
private fun SentTo(email: String, enabled: Boolean, onChange: () -> Unit, label: String = "CODE SENT TO") {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            MetaText(label, style = AgentTheme.type.overline, color = colors.textMuted)
            Spacer(Modifier.height(2.dp))
            Text(email, color = colors.text, style = AgentTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppButton("Change", ButtonTone.Quiet, enabled = enabled, onClick = onChange)
    }
}

/** One team to sign in to: its name, its address, and a note when it is read-only. */
@Composable
private fun TeamRow(team: HostedTeam, joining: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val note = if (team.readOnly) "Read-only: the subscription has lapsed. You can still sign in and read." else null
    val label = buildString {
        append(team.name).append(", ").append(team.address.removePrefix("https://"))
        if (joining) append(", signing in")
        if (note != null) append(". ").append(note)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, if (joining) colors.accent else colors.border, shape)
            // Clicks while another team is signing in are dropped by the caller.
            .pressable(label, shape, role = Role.Button) { if (enabled) onClick() }
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(team.name, color = colors.text, style = AgentTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                team.address.removePrefix("https://"),
                color = colors.textMuted, style = AgentTheme.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(note, color = colors.warning, style = AgentTheme.type.meta)
            }
        }
        Text(
            if (joining) "Signing in…" else "Open",
            Modifier.padding(start = 12.dp),
            color = if (joining) colors.textMuted else colors.accent,
            style = AgentTheme.type.label,
        )
    }
}
