package dev.wildware.agents.app

import dev.wildware.agents.app.feed.LocalCardFolds
import dev.wildware.agents.app.feed.LocalDashboardAddress
import dev.wildware.agents.app.feed.CardFolds
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.wildware.agents.app.feed.AppCommand
import dev.wildware.agents.app.feed.LocalAppCommands
import dev.wildware.agents.app.feed.LocalDemo
import dev.wildware.agents.app.feed.LocalCrashReports
import dev.wildware.agents.app.feed.LocalSounds
import dev.wildware.agents.app.feed.LocalTeams
import dev.wildware.agents.app.feed.LocalTourSeen
import dev.wildware.agents.app.feed.TourSeen
import dev.wildware.agents.app.feed.OpenTeamTabDialog
import dev.wildware.agents.app.feed.TeamsUi
import dev.wildware.agents.app.feed.safePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.sound.tracksUse
import dev.wildware.agents.app.feed.LocalOpenSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import coil3.compose.setSingletonImageLoaderFactory
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.systemAccessibility
import dev.wildware.agents.app.design.systemDarkPreference
import dev.wildware.agents.app.design.resolveDark
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.LocalMediaHandler
import dev.wildware.agents.app.feed.rememberPlatformMediaHandler
import dev.wildware.agents.app.feed.LocalPushAlerts
import dev.wildware.agents.app.feed.LocalLiveActivities
import dev.wildware.agents.app.feed.LocalFilePicker
import dev.wildware.agents.app.feed.LocalVoiceInput
import dev.wildware.agents.app.feed.LocalVoiceReview
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.agentsImageLoader
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.feed.InviteLink
import dev.wildware.agents.app.signin.ConnectState
import dev.wildware.agents.app.signin.JoinFromLinkDialog
import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.SignInScreen
import kotlinx.coroutines.flow.filterNotNull
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The app's name, wherever people see it. Hidden identifiers (bundle ids, folders) keep "agents". */
const val ProductName = "Wildware Feed"

/**
 * The whole app: sign-in until there is a device key, then the live feed. The engine runs on its
 * own scope off the main thread; the screens only collect what it publishes.
 */
@Composable
fun AgentsApp(
    platform: AppPlatform,
    /** Forces light or dark, for tests; otherwise the owner's pick or the device's decides. */
    dark: Boolean? = null,
    accessibility: AccessibilityPrefs = systemAccessibility(),
    safeArea: SafeArea = SafeArea(),
    /** Where notifications, widgets and search ask the feed to go. */
    navigator: AppNavigator? = null,
) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val model = remember { AppModel(platform, scope) }
    AgentsApp(platform, model, dark, accessibility, safeArea, navigator)
}

/**
 * The same app over a [model] that lives outside the window: the desktop keeps it running in the
 * tray while the window is closed.
 */
@Composable
fun AgentsApp(
    platform: AppPlatform,
    model: AppModel,
    /** Forces light or dark, for tests; otherwise the owner's pick or the device's decides. */
    dark: Boolean? = null,
    accessibility: AccessibilityPrefs = systemAccessibility(),
    safeArea: SafeArea = SafeArea(),
    /** Where notifications, widgets and search ask the feed to go. */
    navigator: AppNavigator? = null,
    /** Extra actions for the command bar (the desktop's update check). */
    commands: List<AppCommand> = emptyList(),
    /** Drawn over every screen, in the app's theme (the desktop's update button and dialog). */
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Opens the settings for the server this app runs, when signed in to it (desktop only). */
    openSettings: (() -> Unit)? = null,
) {
    setSingletonImageLoaderFactory { context -> agentsImageLoader(context, platform.httpEngine, platform.imageCacheDir) }

    val choice by model.theme.collectAsState()
    val system = systemDarkPreference()
    val isDark = dark ?: resolveDark(choice, system)

    // An invite link the app was opened with: the join screen, filled in. Signed in, it asks first,
    // since joining makes a new person and this device signs out to be them.
    var joinWith by remember { mutableStateOf<InviteLink?>(null) }
    var confirmJoin by remember { mutableStateOf<InviteLink?>(null) }
    LaunchedEffect(navigator) {
        navigator?.invites?.filterNotNull()?.collect { invite ->
            navigator.handled(invite)
            if (model.screen.value is AppScreen.SigningIn) joinWith = invite else confirmJoin = invite
        }
    }

    // A notification from a team that isn't open opens that team first; its feed then takes the request.
    LaunchedEffect(navigator) {
        navigator?.requests?.filterNotNull()?.collect { request ->
            val server = request.server ?: return@collect
            when {
                model.isOpen(server) -> Unit
                model.knowsTeam(server) -> model.switchTeamAt(server)
                else -> navigator.anyTeam(request)
            }
        }
    }
    val teamEntries by model.teams.collectAsState()
    var addingTab by remember { mutableStateOf(false) }
    val uris = androidx.compose.ui.platform.LocalUriHandler.current
    val teamsUi = remember(teamEntries, uris) {
        TeamsUi(
            teamEntries,
            // A team the web app holds no sign-in for is another tab: opened again, with its own cookie.
            switchTo = { team -> if (team.link) runCatching { uris.openUri(team.server) } else model.switchTeam(team.key) },
            add = { if (model.webApp) addingTab = true else model.startAddingTeam() },
            signOutOf = { team -> model.signOutOf(team.key) },
            web = model.webApp,
            openTab = model::rememberTeamTab,
        )
    }

    AgentTheme(isDark, accessibility) {
        // While the owner is using the app, it makes no sound but a critical one.
        Box(Modifier.fillMaxSize().tracksUse(platform.sounds.activity)) {
            val current = model.screen.collectAsState().value
            // Joined or signed in: the link has done its job.
            LaunchedEffect(current is AppScreen.Signed) { if (current is AppScreen.Signed) joinWith = null }
            when (val screen = current) {
                is AppScreen.SigningIn -> key(joinWith?.serial) {
                    val hostProblem = platform.localServer?.state?.collectAsState()?.value?.problem
                    // Served by the dashboard itself (the web app): that is the host, so step 1 is done.
                    val home = platform.homeServer
                    val connect = remember(home) {
                        if (home == null) ConnectState() else ConnectState(home, HostCheck.Feed(home, listOf(SignInMethod.Password)))
                    }
                    // The password box shows at once; a team's server then adds the Email box.
                    LaunchedEffect(connect) { if (home != null) connect.learnSignIn(model::checkHost) }
                    SignInScreen(
                        deviceName = model.deviceName,
                        notice = screen.notice,
                        safeArea = safeArea,
                        signInHere = if (model.canHostLocally) model::signInHere else null,
                        hostProblem = hostProblem,
                        checkHost = model::checkHost,
                        signIn = model::signIn,
                        signInWithEmail = model::signInWithEmail,
                        acceptInvite = model::acceptInvite,
                        // Adding a team: the demo is for a first look, not another team.
                        tryDemo = if (screen.adding) null else model::enterDemo,
                        connect = connect,
                        hostFixed = home != null,
                        startInvite = joinWith?.link,
                        startWith = if (joinWith != null) SignInChoice.Host else null,
                        hosted = model.hostedLogin,
                        joinHosted = model::joinHostedTeam,
                        linksToSignUp = platform.linksToSignUp,
                    )
                    if (screen.adding) AddingTeamBack(safeArea, model::cancelAddingTeam)
                }
                is AppScreen.LocalServerDown -> dev.wildware.agents.app.signin.LocalServerDownScreen(
                    screen.problem, safeArea, onRetry = model::retryLocal, onSignOut = { model.signOut() },
                )
                // Each team its own screen: nothing open in one (a thread, a dialog, the scroll) carries into another.
                is AppScreen.Signed -> key(screen.session) {
                    val session = screen.session
                    val state = session.state.collectAsState()
                    val folds = remember(session) { CardFolds(session.cardFolds) }
                    // Read once per team opened: the tour starts by itself only on a first sign-in.
                    val tour = remember(session) { TourSeen(model.tourDue(screen.demo)) { model.tourDone(screen.demo) } }
                    CompositionLocalProvider(
                        LocalCardFolds provides folds,
                        LocalMediaAuth provides MediaAuth(session.client.serverUrl, session.client.authorizationHeader),
                        LocalDashboardAddress provides session.client.serverUrl,
                        LocalFilePicker provides platform.filePicker,
                        LocalMediaHandler provides (platform.media ?: rememberPlatformMediaHandler(platform.httpEngine)),
                        LocalPushAlerts provides platform.pushAlerts,
                        LocalVoiceInput provides platform.voiceInput,
                        LocalVoiceReview provides platform.voiceReview,
                        LocalAppCommands provides commands,
                        LocalDemo provides screen.demo,
                        LocalOpenSettings provides openSettings,
                        LocalCrashReports provides platform.crashReports,
                        LocalLiveActivities provides platform.liveActivities,
                        LocalSounds provides platform.sounds.takeIf { it.available },
                        LocalTeams provides teamsUi.takeIf { !screen.demo },
                        LocalTourSeen provides tour,
                    ) {
                        FeedScreen(state, safeArea, navigator = navigator, onAwayFromTop = session::holdArrivals) { event ->
                            when (event) {
                                is FeedEvent.SignOut -> model.signOut()
                                is FeedEvent.ToggleTheme -> model.toggleTheme(showingDark = isDark)
                                else -> session.handle(event)
                            }
                        }
                    }
                }
            }
            confirmJoin?.let { invite ->
                val signed = current as? AppScreen.Signed
                JoinFromLinkDialog(
                    invite,
                    signedInTo = signed?.takeIf { !it.demo }?.session?.saved?.server,
                    onCancel = { confirmJoin = null },
                    onJoin = {
                        confirmJoin = null
                        // Joining adds the team; into the open team, it takes that sign-in's place.
                        model.startAddingTeam()
                        joinWith = invite
                    },
                )
            }
            if (addingTab) OpenTeamTabDialog(teamsUi, onClose = { addingTab = false })
            overlay()
        }
    }
}

/** "Back" while adding a team, to the team that is open: over the sign-in screen's top corner. */
@Composable
private fun AddingTeamBack(safeArea: SafeArea, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().safePadding(safeArea, top = 8.dp).padding(start = 12.dp)) {
        dev.wildware.agents.app.feed.AppButton(
            "Back",
            dev.wildware.agents.app.feed.ButtonTone.Quiet,
            icon = dev.wildware.agents.app.design.Icons.Back,
            onClick = onBack,
        )
    }
}

/** The feed on made-up data, for screenshots and previews. Nothing is sent anywhere. */
@Composable
fun AgentsPreview(
    dark: Boolean = isSystemInDarkTheme(),
    accessibility: AccessibilityPrefs = AccessibilityPrefs(),
    state: FeedState = FakeFeed.state,
    safeArea: SafeArea = SafeArea(),
    startAt: String? = null,
    preview: ScreenPreview = ScreenPreview(),
    /** Drawn over the feed, as [AgentsApp]'s overlay is. */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    AgentTheme(dark, accessibility) {
        // A picker that picks nothing, so previews show the paperclip as the app does.
        CompositionLocalProvider(LocalFilePicker provides FilePicker { it(emptyList()) }) {
            Box(Modifier.fillMaxSize()) {
                FeedScreen(state, safeArea, startAt, preview)
                overlay()
            }
        }
    }
}
