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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectableGroup
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInException
import dev.wildware.agents.SignInMethod
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
private enum class Field { Server, Password, Device, General, Email, Invite, Name }

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
    SignInFailure.BadInvite -> Problem(Field.Invite, message ?: "That invite has expired, was used already, or isn't right. Ask for a new one.")
    SignInFailure.BadTicket -> Problem(Field.General, message ?: "That sign-in ran out. Try again.")
}

/** Where to sign in: the hosted service, a server this app runs itself, or someone's own server. */
enum class SignInChoice(val label: String) {
    LogIn("Log in"),
    ThisComputer("This computer"),
    Host("Connect to a host"),
}

/**
 * The choices this app offers, in the order the tabs show them. With only one there are no tabs.
 * [hostedLogin]: the build has an account service and this isn't the web app.
 */
fun signInChoices(canHostLocally: Boolean, hostedLogin: Boolean): List<SignInChoice> = buildList {
    if (hostedLogin) add(SignInChoice.LogIn)
    if (canHostLocally) add(SignInChoice.ThisComputer)
    add(SignInChoice.Host)
}

/** The first choice: "Log in" where there is one, then "This computer" where the app can run a server itself; otherwise a host. */
fun defaultSignInChoice(canHostLocally: Boolean, hostedLogin: Boolean): SignInChoice = signInChoices(canHostLocally, hostedLogin).first()

/**
 * "Connect to a host": the address as typed, and once it checks out, the host found there, which
 * is step 2. Going back to step 1 keeps the address.
 */
@Stable
class ConnectState(address: String = "", host: HostCheck.Feed? = null, problem: String? = null) {
    val address = TextFieldState(address)

    /** The host the address led to; null while still at step 1. */
    var host: HostCheck.Feed? by mutableStateOf(host)

    /** Why the address didn't check out, shown under it. */
    var problem: String? by mutableStateOf(problem)

    /**
     * The web app starts at step 2 with the password alone, since its host is the one that served
     * it. This asks that host how it signs people in, so a team's server adds the Email box for its
     * members. If the host can't say, the password alone stays.
     */
    suspend fun learnSignIn(check: suspend (String) -> HostCheck) {
        val server = host?.server ?: return
        val found = try {
            check(server)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            return
        }
        if (found is HostCheck.Feed && host?.server == server) host = found
    }
}

/** What step 1 says about an address that didn't check out. */
private fun HostCheck.problem(): String? = when (this) {
    is HostCheck.Feed -> null
    is HostCheck.NotFeed -> NotAFeedServer
    is HostCheck.Unreachable -> CouldNotReach
}

internal const val NotAFeedServer = "That address isn't a Wildware Feed server. Check the address, or that it starts with https://"
internal const val CouldNotReach = "Couldn't reach that address."

private fun minutes(seconds: Long): String {
    val m = (seconds + 59) / 60
    return if (m <= 1) "about a minute" else "$m minutes"
}

/**
 * Sign in. "Connect to a host" takes two steps: the address, which is checked before anything
 * else is asked, then that host's own login (today the owner password) and a name for this
 * device. The password is sent once and never kept; the device key that comes back is.
 *
 * Where the app can run a server itself ([signInHere] is set), it first offers "This computer",
 * which needs only the device's name.
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
    /** Which choice shows first; null for the first there is. */
    startWith: SignInChoice? = null,
    /** Where "Connect to a host" starts; screenshots set it to show step 2 or a problem. */
    connect: ConnectState = remember { ConnectState() },
    /** The host can't be changed: the web app signs in to the server that served it. */
    hostFixed: Boolean = false,
    /** Step 1: whether an address is a Wildware Feed server, and how it signs in. */
    checkHost: suspend (address: String) -> HostCheck,
    /** A person's sign-in on a team server, with their email (migration 055); null where it isn't wired. */
    signInWithEmail: (suspend (server: String, email: String, password: String, device: String) -> Unit)? = null,
    /** Joins a team with an invite's link or code; null where it isn't wired. */
    acceptInvite: (suspend (server: String, invite: String, name: String, email: String, password: String, device: String) -> Unit)? = null,
    /** Opens on "I have an invite", with this link or code filled in. */
    startInvite: String? = null,
    /** "Log in", the hosted service: email, code, team. Null where there is none (the web app). */
    hosted: dev.wildware.agents.HostedLoginFlow? = null,
    /** Signs in to the team "Log in" picked and opens it. */
    joinHosted: (suspend (team: dev.wildware.agents.HostedTeam, device: String) -> Unit)? = null,
    /** Whether "Log in" may link to the website that makes hosted teams; false in the Apple apps. */
    linksToSignUp: Boolean = true,
    /** Where "Log in" starts; screenshots set it to show a later step or a problem. */
    hostedState: HostedState = remember { HostedState() },
    signIn: suspend (server: String, password: String, device: String) -> Unit,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val scope = rememberCoroutineScope()
    val password = rememberTextFieldState()
    val email = rememberTextFieldState()
    val inviteCode = rememberTextFieldState(startInvite ?: "")
    val personName = rememberTextFieldState()
    var inviting by remember { mutableStateOf(startInvite != null && acceptInvite != null) }
    val device = rememberTextFieldState(deviceName)
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Problem?>(null) }
    val choices = signInChoices(signInHere != null, hostedLogin = hosted != null && joinHosted != null)
    var choice by remember { mutableStateOf(startWith?.takeIf { it in choices } ?: choices.first()) }
    val here = choice == SignInChoice.ThisComputer && signInHere != null
    val host = connect.host

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

    val submitAddress: () -> Unit = submit@{
        if (busy) return@submit
        if (connect.address.text.isBlank()) {
            connect.problem = "Type the host's address."
            return@submit
        }
        busy = true
        connect.problem = null
        scope.launch {
            try {
                val found = checkHost(connect.address.text.toString())
                connect.problem = found.problem()
                if (found is HostCheck.Feed) {
                    problem = null
                    connect.host = found
                }
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                connect.problem = CouldNotReach
            } finally {
                busy = false
            }
        }
    }

    val submitPassword: () -> Unit = submit@{
        val server = host?.server
        if (busy || server == null) return@submit
        val typedEmail = email.text.toString().trim()
        if (password.text.isEmpty()) {
            problem = Problem(Field.Password, if (typedEmail.isEmpty()) "Type the owner password." else "Type your password.")
            return@submit
        }
        busy = true
        problem = null
        scope.launch {
            try {
                if (typedEmail.isNotEmpty() && signInWithEmail != null) signInWithEmail(server, typedEmail, password.text.toString(), device.text.toString())
                else signIn(server, password.text.toString(), device.text.toString())
            } catch (failed: SignInException) {
                problem = failed.problem()
            } finally {
                busy = false
            }
        }
    }

    val submitInvite: () -> Unit = submit@{
        val join = acceptInvite ?: return@submit
        if (busy) return@submit
        val code = inviteCode.text.toString().trim()
        // The host already chosen wins: a link that points somewhere else is refused, never followed,
        // so a password is only ever sent to the server the screen shows.
        val linked = dev.wildware.agents.SignIn.serverOfInvite(code)?.let(dev.wildware.agents.SignIn::normalise)
        val server = host?.server ?: linked
            ?: connect.address.text.toString().trim().takeIf { it.isNotEmpty() }?.let(dev.wildware.agents.SignIn::normalise)
        problem = when {
            code.isEmpty() -> Problem(Field.Invite, "Paste the invite link, or its code.")
            host != null && linked != null && linked != host.server -> Problem(Field.Invite, "That invite is for $linked, not ${host.server}.")
            server == null -> Problem(Field.Invite, "That's a code without an address: type the host's address first, or paste the whole link.")
            personName.text.isBlank() -> Problem(Field.Name, "Type your name, as the team will see it.")
            email.text.isBlank() -> Problem(Field.Email, "Type your email. You sign in with it.")
            password.text.length < 8 -> Problem(Field.Password, "Choose a password of at least 8 characters.")
            else -> null
        }
        if (problem != null || server == null) return@submit
        busy = true
        scope.launch {
            try {
                join(server, code, personName.text.toString(), email.text.toString(), password.text.toString(), device.text.toString())
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
            val joining = inviting && acceptInvite != null && !here && choice != SignInChoice.LogIn
            Text(if (joining) "Join a team" else "Sign in", Modifier.semantics { heading() }, color = colors.text, style = type.display)
            if (choices.size > 1) {
                Spacer(Modifier.height(16.dp))
                ChoiceTabs(choices, choice) {
                    if (!busy && !hostedState.busy) {
                        choice = it
                        problem = null
                    }
                }
                Spacer(Modifier.height(14.dp))
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Text(
                when {
                    joining -> "Paste the invite you were sent and make your account. This device keeps its own key."
                    choice == SignInChoice.LogIn -> when (hostedState.step) {
                        HostedStep.Email -> "Sign in to your team on ${dev.wildware.agents.app.ProductName} with your email."
                        HostedStep.Code -> "We emailed you a 6-digit code. Type it here, or paste it."
                        HostedStep.Teams -> if (hostedState.teams?.teams.isNullOrEmpty()) "You're signed in, but there's no team to open yet."
                        else "Pick the team to open on this device. You can sign out and pick another later."
                    }
                    here -> "Your feed runs right here, on this computer. No address or password needed."
                    host == null -> "Type the address of your ${dev.wildware.agents.app.ProductName} server."
                    hostFixed -> "The password is used once; this browser then stays signed in."
                    else -> "The password is used once; this device keeps its own key."
                },
                color = colors.textMuted,
                style = type.body,
            )
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Banner(notice, isError = false)
            }
            Spacer(Modifier.height(24.dp))
            when {
                // Joining a team with an invite: its link or code, and a new account (migration 055).
                inviting && acceptInvite != null && !here && choice != SignInChoice.LogIn -> {
                    // Where the account is made: the host chosen, else the one the pasted link names.
                    (host?.server ?: dev.wildware.agents.SignIn.serverOfInvite(inviteCode.text.toString())?.let(dev.wildware.agents.SignIn::normalise))?.let {
                        HostCard(it, enabled = false, onChange = null)
                        Spacer(Modifier.height(18.dp))
                    }
                    LabelledField(
                        "Invite link or code", inviteCode, problem.takeIf { it?.field == Field.Invite }?.message,
                        placeholder = "https://…/join/…",
                        hint = "The link you were sent, or the code from it.",
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        focusFirst = startInvite == null,
                    )
                    Spacer(Modifier.height(14.dp))
                    LabelledField("Your name", personName, problem.takeIf { it?.field == Field.Name }?.message, hint = "How the team sees you.", keyboard = KeyboardOptions(imeAction = ImeAction.Next))
                    Spacer(Modifier.height(14.dp))
                    LabelledField("Email", email, problem.takeIf { it?.field == Field.Email }?.message, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    Spacer(Modifier.height(14.dp))
                    LabelledField(
                        "Choose a password", password, problem.takeIf { it?.field == Field.Password }?.message, secret = true,
                        hint = "At least 8 characters. You sign in with your email and this.",
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(14.dp))
                    LabelledField(
                        "Name for this device", device, null,
                        keyboard = KeyboardOptions(imeAction = ImeAction.Go),
                        onSubmit = submitInvite,
                    )
                    // What the server refused (an email the invite isn't for, say) isn't about one box here.
                    problem?.takeIf { it.field == Field.General || it.field == Field.Server || it.field == Field.Device }?.let {
                        Spacer(Modifier.height(16.dp))
                        Banner(it.message, isError = true)
                    }
                    Spacer(Modifier.height(22.dp))
                    AppButton(
                        if (busy) "Joining…" else "Join the team",
                        ButtonTone.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        icon = if (busy) Icons.Clock else null,
                        enabled = !busy,
                        onClick = submitInvite,
                    )
                    Spacer(Modifier.height(10.dp))
                    AppButton("Back to sign in", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        inviting = false
                        problem = null
                    })
                }
                choice == SignInChoice.LogIn && hosted != null && joinHosted != null ->
                    HostedLoginForm(hosted, hostedState, device.text.toString(), joinHosted, tryDemo, linksToSignUp)
                here -> {
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
                }
                host == null -> {
                    LabelledField(
                        "Host address", connect.address, connect.problem,
                        placeholder = "https://dashboard.example.com",
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        onSubmit = submitAddress,
                    )
                    Spacer(Modifier.height(22.dp))
                    AppButton(
                        if (busy) "Checking…" else "Continue",
                        ButtonTone.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        icon = if (busy) Icons.Clock else null,
                        enabled = !busy,
                        onClick = submitAddress,
                    )
                    InviteLink(acceptInvite != null, !busy) {
                        inviting = true
                        problem = null
                    }
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
                else -> {
                    HostCard(host.server, enabled = !busy, onChange = if (hostFixed) null else {
                        {
                            connect.host = null
                            problem = null
                        }
                    })
                    Spacer(Modifier.height(18.dp))
                    // Only what this host offers. A team server will add email and password here.
                    if (SignInMethod.Password in host.signIn) {
                        // A team server also takes a person's email and password (migration 055).
                        val withEmail = SignInMethod.EmailPassword in host.signIn && signInWithEmail != null
                        if (withEmail) {
                            LabelledField(
                                "Email", email, problem.takeIf { it?.field == Field.Email }?.message,
                                hint = "Leave it empty to sign in with the owner password.",
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                focusFirst = hostFixed,
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        LabelledField(
                            if (withEmail) "Password" else "Owner password", password, problem.takeIf { it?.field == Field.Password }?.message,
                            secret = true,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (hostFixed) ImeAction.Go else ImeAction.Next),
                            // In a browser tab, where the host is fixed, the password is all there is to
                            // type: it has the keyboard from the start, and Enter signs in.
                            onSubmit = if (hostFixed) submitPassword else null,
                            focusFirst = hostFixed && !withEmail,
                        )
                        Spacer(Modifier.height(14.dp))
                        LabelledField(
                            "Name for this device", device, problem.takeIf { it?.field == Field.Device }?.message,
                            hint = "Shown in the dashboard's device list, so you can revoke it later.",
                            keyboard = KeyboardOptions(imeAction = ImeAction.Go),
                            onSubmit = submitPassword,
                        )
                        // Step 2 has no address box, so a problem with the address shows here.
                        problem?.takeIf { it.field == Field.General || it.field == Field.Server }?.let {
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
                            onClick = submitPassword,
                        )
                        InviteLink(acceptInvite != null, !busy) {
                            inviting = true
                            problem = null
                        }
                    } else {
                        Banner("This host signs in a way this app doesn't know yet. Update the app, then try again.", isError = true)
                    }
                }
            }
        }
    }
    }
}

/** "I have an invite": the way to join a team instead of signing in. */
@Composable
private fun InviteLink(shown: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!shown) return
    Spacer(Modifier.height(10.dp))
    AppButton("I have an invite", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
}

/** Step 2's reminder of which host this is, with the way back to step 1 unless [onChange] is null. */
@Composable
private fun HostCard(server: String, enabled: Boolean, onChange: (() -> Unit)?) {
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
            MetaText("SIGNING IN TO", style = AgentTheme.type.overline, color = colors.textMuted)
            Spacer(Modifier.height(2.dp))
            Text(
                server.removePrefix("https://"), color = colors.text, style = AgentTheme.type.body,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (onChange != null) AppButton("Change", ButtonTone.Quiet, enabled = enabled, onClick = onChange)
    }
}

/** The ways in, side by side, at the top of the screen. */
@Composable
private fun ChoiceTabs(choices: List<SignInChoice>, choice: SignInChoice, onChoose: (SignInChoice) -> Unit) {
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
        for (option in choices) {
            val label = option.label
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
internal fun LabelledField(
    label: String,
    state: TextFieldState,
    error: String?,
    secret: Boolean = false,
    placeholder: String = "",
    hint: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
    /** Takes the keyboard as soon as it shows. */
    focusFirst: Boolean = false,
    /** What the box holds, so the system can fill it in (an email address, a code from a message). */
    contentType: androidx.compose.ui.autofill.ContentType? = null,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val focus = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    if (error != null) error(error)
                    if (contentType != null) this.contentType = contentType
                },
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
internal fun Banner(text: String, isError: Boolean) {
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
