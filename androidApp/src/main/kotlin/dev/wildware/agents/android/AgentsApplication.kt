package dev.wildware.agents.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.wildware.agents.app.crash.CrashReporting
import dev.wildware.agents.app.design.AndroidAppIcon
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.engine.DashboardSession
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.sound.AndroidSoundPlayer
import dev.wildware.agents.app.sound.SoundBoard
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The app's life outside any one screen. The engine (signed-in session, live connection,
 * outbox) lives here rather than in the activity, so turning the phone, or the activity closing
 * and coming back, never reconnects or re-reads the dashboard; notifications keep coming while
 * the app is in the background, for as long as Android keeps it running.
 */
class AgentsApplication : Application() {
    val hooks = ActivityHooks()
    val navigator = AppNavigator()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var platform: AppPlatform
        private set
    lateinit var model: AppModel
        private set
    lateinit var notifier: AndroidNotifier
        private set

    private val mutableWaiting = MutableStateFlow<Int?>(null)

    /** Questions waiting on the owner; null when signed out. */
    val waiting: StateFlow<Int?> = mutableWaiting.asStateFlow()

    private lateinit var stayConnectedStore: FileTextStore
    private val mutableStayConnected = MutableStateFlow(false)

    /** Whether "Stay connected" is on: a foreground service keeps the app running in the background. */
    val stayConnected: StateFlow<Boolean> = mutableStayConnected.asStateFlow()

    /** Whether the owner has been asked about notifications yet. */
    lateinit var askedNotifications: FileTextStore
        private set

    private var watchingProjects: Job? = null

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is reported (in a build that reports crashes).
        CrashReporting.start(FileTextStore(File(filesDir, "crash-reports.txt")))
        Channels.create(this)
        AndroidAppIcon.bitmap = drawAppIcon(this)
        platform = androidPlatform(this, hooks, scope, onScreen = ::onScreen)
        stayConnectedStore = FileTextStore(File(filesDir, "stay-connected.txt"))
        askedNotifications = FileTextStore(File(filesDir, "asked-notifications.txt"))
        mutableStayConnected.value = stayConnectedStore.read()?.trim() == "on"
        notifier = AndroidNotifier(this)
        model = AppModel(platform, scope)
        Alerts(
            model = model,
            scope = scope,
            notifier = notifier,
            onScreen = ::onScreen,
            onWaiting = { count ->
                if (mutableWaiting.value != count) {
                    mutableWaiting.value = count
                    ConnectionService.update(this, count)
                }
            },
            onSession = ::followSession,
        ).start()
    }

    private fun onScreen(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun followSession(session: DashboardSession?) {
        watchingProjects?.cancel()
        // The demo is sample data: it gets no launcher shortcuts and no "stay connected" service.
        val demo = (model.screen.value as? AppScreen.Signed)?.demo == true
        if (session == null || demo) {
            ConnectionService.stop(this)
            Shortcuts.clear(this)
            return
        }
        watchingProjects = scope.launch {
            var last: List<String>? = null
            session.dashboard.projects.collect { projects ->
                val ids = projects.take(4).map { it.project.id + it.project.name }
                if (ids != last) {
                    last = ids
                    Shortcuts.update(this@AgentsApplication, projects)
                }
            }
        }
    }

    /** Turns "Stay connected" on or off. Call it from the screen: Android only starts the service then. */
    fun setStayConnected(context: Context, on: Boolean) {
        mutableStayConnected.value = on
        runCatching { stayConnectedStore.write(if (on) "on" else "off") }
        if (on && signedIn) ConnectionService.start(context) else ConnectionService.stop(context)
    }

    /** Signed in to a real dashboard: the demo doesn't count. */
    val signedIn: Boolean get() = (model.screen.value as? AppScreen.Signed)?.demo == false

    /** Opens card [cardId], in its own project's feed when that is known (else every project's). */
    fun openCard(cardId: String) {
        val dashboard = (model.screen.value as? AppScreen.Signed)?.session?.dashboard ?: return navigator.open(null, cardId)
        val projectId = dashboard.everything.state.value.card(cardId)?.projectId
            ?: dashboard.waiting.value.firstOrNull { it.id == cardId }?.request?.projectId
        val slug = projectId?.let { id -> dashboard.projects.value.firstOrNull { it.project.id == id }?.project?.slug }
        navigator.open(slug, cardId)
    }
}

/** Everything the Android app keeps, in its private storage. */
fun androidPlatform(context: Context, hooks: ActivityHooks, scope: CoroutineScope, onScreen: () -> Boolean): AppPlatform {
    val dir = context.filesDir
    val outbox = FileOutboxStorage(File(dir, "outbox"))
    val readMarks = File(dir, "read-marks.json")
    // In the cache folder: Android may clear it when space runs short, which costs only a slower next start.
    val feedCache = File(context.cacheDir, "feed-cache.json")
    val cardFolds = File(dir, "folded-cards.json")
    val postTarget = File(dir, "post-target.txt")
    val linksToWebsite = StoreRules.linksToWebsite(StoreRules.installer(context))
    return AppPlatform(
        sessions = SealedSessionStore(File(dir, "session.bin"), KeystoreSecretBox()),
        readMarks = FileTextStore(readMarks),
        outbox = outbox,
        httpEngine = { OkHttp.create() },
        deviceName = deviceName(context),
        imageCacheDir = File(context.cacheDir, "images").absolutePath,
        clearLocalData = {
            outbox.clear()
            readMarks.delete()
            feedCache.delete()
            cardFolds.delete()
            postTarget.delete()
        },
        settings = FileTextStore(File(dir, "settings.txt")),
        upsellMarks = FileTextStore(File(dir, "upsell.json")),
        feedCache = FileTextStore(feedCache),
        cardFolds = FileTextStore(cardFolds),
        postTarget = FileTextStore(postTarget),
        filePicker = AndroidPhotoPicker(hooks, context.contentResolver, scope),
        // No recogniser on the phone (some builds without Google's apps): no mic, rather than one that can't work.
        voiceInput = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechVoiceInput(context, hooks) else null,
        voiceReview = dev.wildware.agents.VoiceReviewSetting(FileTextStore(File(dir, "voice-review.txt"))),
        crashReports = CrashReporting.setting,
        sounds = AndroidSoundPlayer(context, onScreen).let { player -> SoundBoard(player, FileTextStore(File(dir, "sounds.txt")), quiet = player::quiet) },
        // A notice shown in the shade makes its channel's sound: over the app only a critical one is shown.
        systemSounds = { notice -> notice.critical || !onScreen() },
        // Keys, sealed by the Keystore like session.bin.
        teamList = SealedTextStore(File(dir, "teams.bin"), KeystoreSecretBox()),
        teamFilesFor = { key -> teamFiles(context, key) },
        guidedTour = true,
        // No sign-up or Team plan links in a copy from Google Play (StoreRules).
        linksToSignUp = linksToWebsite,
        buyingLinks = MutableStateFlow(linksToWebsite),
    )
}

/**
 * Team [key]'s own files, in `teams/<key>`. The first team (empty key) keeps the files it always
 * had; only what it parks while another team is open goes in `teams/first`.
 */
private fun teamFiles(context: Context, key: String): dev.wildware.agents.app.session.TeamFiles {
    val own = File(File(context.filesDir, "teams"), key.ifEmpty { "first" }).apply { mkdirs() }
    val home = if (key.isEmpty()) context.filesDir else own
    val cache = if (key.isEmpty()) context.cacheDir else File(File(context.cacheDir, "teams"), key).apply { mkdirs() }
    val readMarks = File(home, "read-marks.json")
    val feedCache = File(cache, "feed-cache.json")
    val cardFolds = File(home, "folded-cards.json")
    val postTarget = File(home, "post-target.txt")
    val outbox = FileOutboxStorage(File(own, "outbox"))
    return dev.wildware.agents.app.session.TeamFiles(
        FileTextStore(readMarks), FileTextStore(feedCache), FileTextStore(cardFolds), FileTextStore(postTarget), outbox,
        clear = {
            outbox.clear()
            listOf(readMarks, feedCache, cardFolds, postTarget).forEach { it.delete() }
            if (key.isNotEmpty()) {
                own.deleteRecursively()
                cache.deleteRecursively()
            }
        },
    )
}

/** The phone's own name (Settings › About phone), else its model: what the sign-in screen offers. */
private fun deviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
