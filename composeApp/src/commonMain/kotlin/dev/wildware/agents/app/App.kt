package dev.wildware.agents.app

import dev.wildware.agents.app.feed.LocalCardFolds
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
import dev.wildware.agents.app.feed.LocalOpenSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.agentsImageLoader
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.signin.ConnectState
import dev.wildware.agents.app.signin.SignInScreen
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

    AgentTheme(isDark, accessibility) {
        Box(Modifier.fillMaxSize()) {
            when (val screen = model.screen.collectAsState().value) {
                is AppScreen.SigningIn -> {
                    val hostProblem = platform.localServer?.state?.collectAsState()?.value?.problem
                    // Served by the dashboard itself (the web app): that is the host, so step 1 is done.
                    val home = platform.homeServer
                    val connect = remember(home) {
                        if (home == null) ConnectState() else ConnectState(home, HostCheck.Feed(home, listOf(SignInMethod.Password)))
                    }
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
                        tryDemo = model::enterDemo,
                        connect = connect,
                        hostFixed = home != null,
                    )
                }
                is AppScreen.Signed -> {
                    val session = screen.session
                    val state = session.state.collectAsState()
                    val folds = remember(session) { CardFolds(platform.cardFolds) }
                    CompositionLocalProvider(
                        LocalCardFolds provides folds,
                        LocalMediaAuth provides MediaAuth(session.client.serverUrl, session.client.authorizationHeader),
                        LocalFilePicker provides platform.filePicker,
                        LocalMediaHandler provides (platform.media ?: rememberPlatformMediaHandler(platform.httpEngine)),
                        LocalPushAlerts provides platform.pushAlerts,
                        LocalVoiceInput provides platform.voiceInput,
                        LocalAppCommands provides commands,
                        LocalDemo provides screen.demo,
                        LocalOpenSettings provides openSettings,
                        LocalCrashReports provides platform.crashReports,
                        LocalLiveActivities provides platform.liveActivities,
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
            overlay()
        }
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
