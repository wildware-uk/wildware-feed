package dev.wildware.agents.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.wildware.agents.app.desktop.DesktopNotice
import dev.wildware.agents.app.desktop.NotificationRules
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.engine.DashboardSession
import dev.wildware.agents.app.engine.NoticeCleanup
import dev.wildware.agents.app.engine.SystemNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The notification channels, which the owner can each turn off or quieten in Settings. */
object Channels {
    const val Questions = "questions"
    const val Replies = "replies"
    const val Connection = "connection"

    /**
     * Something an agent marked critical, on a phone that allows it to page. The channel asks to
     * break through Do Not Disturb; Android leaves the final say to the owner, in the channel's own
     * settings, which the alert settings open ([openCriticalSettings]).
     */
    const val Critical = "critical"

    /** The system's settings for the "Critical" channel, where Do Not Disturb can be overridden. */
    fun openCriticalSettings(context: Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Critical)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(Questions, context.getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_questions_about) },
                NotificationChannel(Replies, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_replies_about) },
                NotificationChannel(Critical, context.getString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = context.getString(R.string.channel_critical_about)
                        setBypassDnd(true)
                        enableVibration(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    },
                NotificationChannel(Connection, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = context.getString(R.string.channel_connection_about)
                        setShowBadge(false)
                    },
            ),
        )
    }
}

/**
 * Puts notices on screen. A tap opens the app at the notice's card. Each is tagged with its key
 * (`request-<id>`, `message-<id>`…), so [remove] can take it down once it is dealt with.
 */
class AndroidNotifier(private val context: Context) : SystemNotices {
    private val manager = NotificationManagerCompat.from(context)

    fun show(notice: DesktopNotice) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        // An agent asking to join waits on the owner as a question does: the same loud channel.
        val question = notice.key.startsWith("request-") || notice.key.startsWith("pairing-")
        val open = PendingIntent.getActivity(
            context,
            notice.key.hashCode(),
            MainActivity.openCard(context, notice.focusId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = when {
            notice.critical -> Channels.Critical
            question -> Channels.Questions
            else -> Channels.Replies
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_agents)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setCategory(if (notice.critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(
                when {
                    notice.critical -> NotificationCompat.PRIORITY_MAX
                    question -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notice.key, NoticeId, notification) }
    }

    override suspend fun shown(): Set<String> =
        runCatching { manager.activeNotifications.mapNotNullTo(HashSet()) { it.tag } }.getOrDefault(emptySet())

    override fun remove(tags: Set<String>) {
        if (tags.isEmpty()) return
        runCatching { manager.activeNotifications.filter { it.tag in tags }.forEach { manager.cancel(it.tag, it.id) } }
    }

    /** Clears every question and reply notice (not the "staying connected" one). */
    fun clearAll() {
        manager.activeNotifications.filter { it.tag != null }.forEach { manager.cancel(it.tag, it.id) }
    }

    private companion object {
        /** Each notice is told apart by its tag (the notice's key), so one id serves them all. */
        const val NoticeId = 1
    }
}

/**
 * Notifies new questions and mentions while the app runs, by the desktop's own rules: never over
 * the app while it is on screen, never for what was already there at sign-in. Takes them down
 * again once the owner deals with them, on this device or another. Also tells
 * [onWaiting] how many questions wait (null when signed out), for the connection notice and the
 * app shortcuts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Alerts(
    private val model: AppModel,
    private val scope: CoroutineScope,
    private val notifier: AndroidNotifier,
    private val onScreen: () -> Boolean,
    private val onWaiting: (Int?) -> Unit,
    private val onSession: (DashboardSession?) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            model.screen.collectLatest { screen ->
                val session = (screen as? AppScreen.Signed)?.session
                onSession(session)
                if (session == null) {
                    notifier.clearAll()
                    onWaiting(null)
                    return@collectLatest
                }
                follow(session)
            }
        }
    }

    private suspend fun follow(session: DashboardSession) = coroutineScope {
        val dashboard = session.dashboard
        // Notices the owner dealt with, here or on another device, come down: as the live
        // connection says so, and each time it comes back for whatever happened while it was away.
        launch { NoticeCleanup(dashboard, notifier).run() }
        val rules = NotificationRules()
        combine(dashboard.ready, dashboard.waiting, dashboard.everything.state, dashboard.pairings) { ready, waiting, feed, joining ->
            onWaiting(waiting.size)
            val slugs = dashboard.projects.value.associate { it.project.id to it.project.slug }
            rules.next(ready, waiting, feed, session.alerts.value.prefs, { id -> id?.let(slugs::get) }, joining)
        }.collectLatest { notices ->
            // A page goes out even with the app open: it is meant to be impossible to miss.
            notices.filter { it.critical || !onScreen() }.forEach(notifier::show)
        }
    }
}
