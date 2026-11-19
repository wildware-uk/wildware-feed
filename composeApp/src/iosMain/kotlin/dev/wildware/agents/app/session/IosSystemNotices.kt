package dev.wildware.agents.app.session

import dev.wildware.agents.DashboardClient
import dev.wildware.agents.MaxClearTags
import dev.wildware.agents.app.engine.NoticeCleanup
import dev.wildware.agents.app.engine.SystemNotices
import kotlinx.coroutines.CancellationException
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The dashboard's alerts in the iPhone's Notification Center and on its lock screen. Each carries
 * its `tag` (spec/08 §6.7) in its payload; the alert's identifier is the same tag where iOS took it
 * from the push's collapse id, so either finds it.
 */
object IosSystemNotices : SystemNotices {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun shown(): Set<String> = delivered().mapNotNullTo(HashSet(), ::tagOf)

    override fun remove(tags: Set<String>) {
        if (tags.isEmpty()) return
        center.getDeliveredNotificationsWithCompletionHandler { list ->
            val ids = list.orEmpty().filterIsInstance<UNNotification>().filter { tagOf(it) in tags }.map { it.request.identifier }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }

    /**
     * For a background push that names alerts to take down (`clear`): takes those down, and asks
     * the dashboard about every other alert still showing, since iOS may have dropped or merged
     * earlier pushes. [client] is null when the phone is signed out; then only [tags] come down.
     * Returns once the removal is asked of iOS, so the push's background time can end.
     */
    @Throws(Exception::class)
    suspend fun clearDelivered(tags: List<String>, client: DashboardClient?) {
        val showing = delivered().mapNotNull { n -> tagOf(n)?.let { it to n.request.identifier } }
        if (showing.isEmpty()) return
        val done = tags.toMutableSet()
        val ask = showing.map { it.first }.filter(NoticeCleanup::isDashboardTag).distinct()
        if (client != null && ask.isNotEmpty()) {
            try {
                ask.chunked(MaxClearTags).forEach { done += client.handledNotifications(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Offline: the tags the push named still come down; the app catches up when opened.
            }
        }
        val ids = showing.filter { it.first in done }.map { it.second }
        if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    private suspend fun delivered(): List<UNNotification> = suspendCoroutine { done ->
        center.getDeliveredNotificationsWithCompletionHandler { list -> done.resume(list.orEmpty().filterIsInstance<UNNotification>()) }
    }

    private fun tagOf(notification: UNNotification): String? {
        val request = notification.request
        return (request.content.userInfo["tag"] as? String) ?: request.identifier.takeIf(NoticeCleanup::isDashboardTag)
    }
}
