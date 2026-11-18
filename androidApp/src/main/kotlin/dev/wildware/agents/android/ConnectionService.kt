package dev.wildware.agents.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * "Stay connected": a foreground service whose only job is to keep the app's process, and so its
 * live connection and notifications, running while the app is in the background. It shows a
 * quiet notice with the waiting count. Off by default; the command bar turns it on and off.
 * Real push (which needs none of this) is the [PushProvider] seam.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val waiting = (application as AgentsApplication).waiting.value
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        runCatching { ServiceCompat.startForeground(this, NoticeId, notice(this, waiting), type) }
            .onFailure { stopSelf() }
        return START_STICKY
    }

    companion object {
        private const val NoticeId = 2

        /** Starts it. Android only allows this while the app is on screen. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        /** Shows the new waiting count on the running service's notice. */
        fun update(context: Context, waiting: Int?) {
            val manager = NotificationManagerCompat.from(context)
            if (manager.activeNotifications.none { it.id == NoticeId && it.tag == null }) return
            runCatching { manager.notify(NoticeId, notice(context, waiting)) }
        }

        private fun notice(context: Context, waiting: Int?): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val text = when (waiting) {
                null, 0 -> "Nothing waiting on you"
                1 -> "1 question waiting"
                else -> "$waiting questions waiting"
            }
            return NotificationCompat.Builder(context, Channels.Connection)
                .setSmallIcon(R.drawable.ic_stat_agents)
                .setContentTitle("Connected")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
