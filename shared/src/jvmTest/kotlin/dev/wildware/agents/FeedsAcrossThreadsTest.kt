package dev.wildware.agents

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app opens and closes project feeds from its own thread while the engine works on another,
 * as the real app does: the engine runs on a background thread, the screen on the UI thread.
 */
class FeedsAcrossThreadsTest {
    @Test
    fun `opening feeds while the engine publishes never trips it`() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures += e })
        try {
            val dashboard = Dashboard(FakeDashboard(TestCoroutineScheduler()).client(), scope, ReadMarks())
            repeat(20_000) { i ->
                val feed = dashboard.feed("p${i % 40}")
                if (i % 3 == 0) feed.close()
                dashboard.changed()
            }
            assertEquals(dashboard.feed("p1"), dashboard.feed("p1"), "the same slug gives the same feed")
        } finally {
            scope.cancel()
        }
        assertTrue(failures.isEmpty(), "the engine failed: ${failures.firstOrNull()?.stackTraceToString()}")
    }
}
