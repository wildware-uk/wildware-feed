package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bell's list: read at start, re-read when the stream says it changed, cleared by the owner. */
class NotificationsTest {
    private fun TestScope.started(server: FakeDashboard): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime })
        dashboard.start()
        runCurrent()
        return dashboard
    }

    @Test
    fun `the list and the unseen count are read at start, newest first`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            notifications = listOf(FakeDashboard.notification("n1", 3, seen = true), FakeDashboard.notification("n2", 5))
        }
        val dashboard = started(server)

        val bell = dashboard.notifications.value
        assertEquals(listOf("n2", "n1"), bell.items.map { it.id })
        assertEquals(1, bell.unseen)
        assertEquals("/projects/mega?focus=u5", bell.items.first().path)
    }

    @Test
    fun `a new notification on the stream is read in`() = runTest {
        val server = FakeDashboard(testScheduler)
        val dashboard = started(server)
        assertEquals(0, dashboard.notifications.value.unseen)

        server.seq = 11
        server.notifications = listOf(FakeDashboard.notification("n1", 11))
        server.connections.single().event("notification.created", 11, buildJsonObject { put("notificationId", "n1") })
        runCurrent()

        assertEquals(listOf("n1"), dashboard.notifications.value.items.map { it.id })
        assertEquals(1, dashboard.notifications.value.unseen)
    }

    @Test
    fun `seeing one sends only its id, and the list is read again`() = runTest {
        val server = FakeDashboard(testScheduler).apply {
            notifications = listOf(FakeDashboard.notification("n1", 3), FakeDashboard.notification("n2", 5))
        }
        val dashboard = started(server)
        server.onWrite = { _, _ ->
            server.notifications = server.notifications.map { if (it.id == "n2") it.copy(seenAt = 9) else it }
            null
        }

        dashboard.markNotificationsSeen(listOf("n2"))
        runCurrent()

        assertEquals("""{"ids":["n2"]}""", server.writes.single { it.first == "POST /api/notifications/seen" }.second)
        assertEquals(1, dashboard.notifications.value.unseen)
        assertEquals(listOf(false, true), dashboard.notifications.value.items.map { it.seenAt == null })
    }

    @Test
    fun `mark all read sends no ids`() = runTest {
        val server = FakeDashboard(testScheduler).apply { notifications = listOf(FakeDashboard.notification("n1", 3)) }
        val dashboard = started(server)

        dashboard.markNotificationsSeen(null)
        runCurrent()

        assertEquals("{}", server.writes.single { it.first == "POST /api/notifications/seen" }.second)
    }

    @Test
    fun `a failed clear leaves the bell lit`() = runTest {
        val server = FakeDashboard(testScheduler).apply { notifications = listOf(FakeDashboard.notification("n1", 3)) }
        val dashboard = started(server)
        server.onWrite = { _, _ -> respond("""{"error":"internal"}""", HttpStatusCode.InternalServerError) }

        dashboard.markNotificationsSeen(null)
        runCurrent()

        assertEquals(1, dashboard.notifications.value.unseen)
    }

    @Test
    fun `a dashboard without the list leaves the bell empty and the feed working`() = runTest {
        val server = FakeDashboard(testScheduler).apply { updates = listOf(FakeDashboard.update("u1", 5)) }
        server.notificationsMissing = true
        val dashboard = started(server)

        assertEquals(NotificationList(), dashboard.notifications.value)
        assertTrue(dashboard.everything.state.value.loaded)
    }
}
