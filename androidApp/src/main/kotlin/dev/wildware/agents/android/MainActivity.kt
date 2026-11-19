package dev.wildware.agents.android

import dev.wildware.agents.app.feed.LocalCriticalAlertSettings
import androidx.compose.runtime.CompositionLocalProvider
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.wildware.agents.app.AgentsApp
import dev.wildware.agents.app.AgentsPreview
import dev.wildware.agents.app.design.resolveDark
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.feed.AppCommand
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.rememberSystemSafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.design.AgentTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one screen: the Compose app, edge to edge. It also takes what Android hands the app (links,
 * shares, tapped notifications) and does the things only an activity can (the photo picker,
 * permission prompts) for the engine that lives in [AgentsApplication].
 */
class MainActivity : ComponentActivity() {
    private val app: AgentsApplication get() = application as AgentsApplication

    private var onPicked: ((List<Uri>) -> Unit)? = null
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MaxPick)) { uris ->
        onPicked?.invoke(uris)
        onPicked = null
    }

    private var onPermission: ((Boolean) -> Unit)? = null
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermission?.invoke(granted)
        onPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.hooks.pickMedia = { picked ->
            onPicked = picked
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        app.hooks.askPermission = { permission, result ->
            onPermission = result
            askPermission.launch(permission)
        }
        // A restored activity was handed its intent already.
        if (savedInstanceState == null) handle(intent)
        // Debug builds only: `adb shell am start -n dev.wildware.agents.debug/dev.wildware.agents.android.MainActivity --ez sample true`
        // shows the feed on made-up data, as the iPhone's screenshot mode does. Nothing signs in.
        // Add `--ez thread true` to start with a thread open.
        val sample = BuildConfig.DEBUG && intent.getBooleanExtra("sample", false)
        val sampleThread = intent.getBooleanExtra("thread", false)
        setContent { if (sample) Sample(sampleThread) else Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Android only lets a foreground service start from the screen, so "Stay connected" restarts here.
        if (app.stayConnected.value && app.signedIn) ConnectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // The owner is looking at the feed now; what the notices said is on screen.
        app.notifier.clearAll()
    }

    override fun onDestroy() {
        app.hooks.pickMedia = null
        app.hooks.askPermission = null
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val choice by app.model.theme.collectAsState()
        val dark = resolveDark(choice, isSystemInDarkTheme())
        // The bar icons follow the app's own light or dark, which the owner may have set apart from the phone's.
        LaunchedEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        val screen by app.model.screen.collectAsState()
        val signed = screen is AppScreen.Signed
        LaunchedEffect(signed) { if (signed) askForNotificationsOnce() }
        val stay by app.stayConnected.collectAsState()

        // The status bar above; the navigation bar or the keyboard, whichever is taller, below.
        // One live object, so the keyboard sliding moves the composer without redrawing the app.
        val safeArea = rememberSystemSafeArea()
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            // The alert settings lead to the "Critical" channel, where Do Not Disturb can be overridden.
            CompositionLocalProvider(LocalCriticalAlertSettings provides { Channels.openCriticalSettings(this@MainActivity) }) {
                AgentsApp(
                    app.platform,
                    app.model,
                    safeArea = safeArea,
                    navigator = app.navigator,
                    commands = if (signed) commands(stay) else emptyList(),
                    overlay = { StatusBarScrim(safeArea) },
                )
            }
        }
    }

    @Composable
    private fun Sample(thread: Boolean) {
        val safeArea = rememberSystemSafeArea()
        AgentsPreview(
            dark = isSystemInDarkTheme(),
            safeArea = safeArea,
            preview = ScreenPreview(threadOpen = thread),
            overlay = { StatusBarScrim(safeArea) },
        )
    }

    /** Content scrolls under the see-through status bar; this keeps the clock and icons readable over it. */
    @Composable
    private fun StatusBarScrim(safeArea: SafeArea) {
        Box(Modifier.fillMaxWidth().height(safeArea.top).background(AgentTheme.colors.background.copy(alpha = 0.9f)))
    }

    private fun commands(stay: Boolean): List<AppCommand> = listOf(
        if (stay) {
            AppCommand("Stop staying connected", "Notify only while the app is open", listOf("background", "connection", "notifications")) {
                app.setStayConnected(this, false)
            }
        } else {
            AppCommand(
                "Stay connected in the background",
                "Keeps notifications coming with the app closed",
                listOf("background", "connection", "notifications"),
            ) { app.setStayConnected(this, true) }
        },
        AppCommand("Notification settings", "Choose which alerts sound", listOf("notifications", "alerts", "sound")) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        },
    )

    /** Android 13+ asks before an app may notify. Asked once, after signing in. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (app.askedNotifications.read() != null) return
        runCatching { app.askedNotifications.write("asked") }
        onPermission = {}
        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ActionOpenCard -> intent.getStringExtra(ExtraCardId)?.let(app::openCard)
            Intent.ACTION_VIEW -> intent.dataString?.let(Links::pathOf)?.let(app.navigator::openPath)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> receiveShare(intent)
        }
    }

    /** Text, a link or pictures from another app's share sheet, into a project's composer. */
    private fun receiveShare(intent: Intent) {
        val text = Links.sharedText(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { readPending(contentResolver, it) } }
            if (text.isNotEmpty() || files.isNotEmpty()) app.navigator.share(text, files)
        }
    }

    companion object {
        private const val MaxPick = 10
        private const val ActionOpenCard = "dev.wildware.agents.OPEN_CARD"
        private const val ExtraCardId = "card"

        /** Opens the app, at [cardId] when there is one, for a notification's tap. */
        fun openCard(context: Context, cardId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ActionOpenCard)
                .putExtra(ExtraCardId, cardId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
