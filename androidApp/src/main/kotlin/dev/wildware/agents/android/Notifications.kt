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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The notification channels, which the owner can each turn off or quieten in Settings. */
object Channels {
    const val Questions = "questions"
    const val Replies = "replies"
    const val Connection = "connection"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(Questions, context.getString(R.string.channel_questions), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_questions_about) },
                NotificationChannel(Replies, context.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_replies_about) },
                NotificationChannel(Connection, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = context.getString(R.string.channel_connection_about)
                        setShowBadge(false)
                    },
            ),
        )
    }
}

/** Puts notices on screen. A tap opens the app at the notice's card. */
class AndroidNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun show(notice: DesktopNotice) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        val question = notice.key.startsWith("request-")
        val open = PendingIntent.getActivity(
            context,
            notice.key.hashCode(),
            MainActivity.openCard(context, notice.focusId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (question) Channels.Questions else Channels.Replies)
            .setSmallIcon(R.drawable.ic_stat_agents)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (question) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notice.key, NoticeId, notification) }
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
 * the app while it is on screen, never for what was already there at sign-in. Also tells
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

    private suspend fun follow(session: DashboardSession) {
        val dashboard = session.dashboard
        val rules = NotificationRules()
        combine(dashboard.ready, dashboard.waiting, dashboard.everything.state) { ready, waiting, feed ->
            onWaiting(waiting.size)
            rules.next(ready, waiting, feed)
        }.collectLatest { notices ->
            if (onScreen()) return@collectLatest
            notices.forEach(notifier::show)
        }
    }
}
