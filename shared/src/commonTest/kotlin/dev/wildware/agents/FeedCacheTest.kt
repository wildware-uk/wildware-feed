package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed kept on the device: shown at once on the next launch, then replaced by the server's. */
class FeedCacheTest {
    private val owner = FeedCache.ownerOf("https://dash.test/", "Bearer device-key")

    private fun cache(store: TextStore, owner: String = this.owner, maxChars: Int = 4_000_000) =
        FeedCache(store, owner, writeDelayMillis = 1_000, maxChars = maxChars, background = EmptyCoroutineContext)

    private fun TestScope.started(server: FakeDashboard, cache: FeedCache): Dashboard {
        val dashboard = Dashboard(server.client(), backgroundScope, ReadMarks(), clock = { testScheduler.currentTime }, cache = cache)
        dashboard.start()
        runCurrent()
        return dashboard
    }

    /** A first launch against [server] that reads everything and leaves it in [store]. */
    private fun TestScope.firstLaunch(store: TextStore, server: FakeDashboard) {
        val dashboard = started(server, cache(store))
        assertTrue(dashboard.everything.state.value.loaded)
        advanceTimeBy(1_500)
        runCurrent()
        dashboard.stop()
        assertNotNull(store.read(), "the feed was written down")
    }

    private fun FakeDashboard.filled() = apply {
        updates = listOf(FakeDashboard.update("u1", 5), FakeDashboard.update("u2", 7))
        messages = listOf(FakeDashboard.message("m1", 8, "agent:a2", "Looks right", 8_000, updateId = "u1"))
        requests = listOf(FakeDashboard.request("q1", 9))
    }

    @Test
    fun `the next launch shows the last feed at once and then the server feed`() = runTest {
        val store = MemoryTextStore()
        firstLaunch(store, FakeDashboard(testScheduler).filled())

        // The network is slow now, and the server has moved on.
        val server = FakeDashboard(testScheduler).filled().apply {
            readDelayMillis = 5_000
            updates = updates + FakeDashboard.update("u3", 11)
        }
        val dashboard = started(server, cache(store))
        val shown = dashboard.everything.state.value
        // u1 sits first: its reply is its newest activity.
        assertEquals(listOf("u1", "u2"), shown.cards.map { it.id }, "last time's cards, before any read answered")
        assertTrue(shown.cached)
        assertFalse(shown.loaded, "kept cards are not the server's word")
        assertEquals(1, (shown.card("u1") as UpdateCard).replies.size, "with their replies")
        assertEquals(listOf("q1"), dashboard.waiting.value.map { it.id }, "and the questions waiting")
        assertFalse(dashboard.ready.value, "nothing kept counts as seen for alerts")

        advanceTimeBy(5_001)
        runCurrent()
        val fresh = dashboard.everything.state.value
        assertEquals(listOf("u3", "u1", "u2"), fresh.cards.map { it.id })
        assertTrue(fresh.loaded)
        assertFalse(fresh.cached)
        assertTrue(dashboard.ready.value)
    }

    @Test
    fun `a cache from another dashboard or key is never shown`() = runTest {
        val store = MemoryTextStore()
        firstLaunch(store, FakeDashboard(testScheduler).filled())
        val server = FakeDashboard(testScheduler).apply { readDelayMillis = 5_000 }

        for (other in listOf(FeedCache.ownerOf("https://other.test/", "Bearer device-key"), FeedCache.ownerOf("https://dash.test/", "Bearer new-key"))) {
            val dashboard = started(server, cache(store, owner = other))
            assertTrue(dashboard.everything.state.value.cards.isEmpty(), "nothing from $other's cache")
            assertFalse(dashboard.everything.state.value.cached)
            dashboard.stop()
        }
    }

    @Test
    fun `a cache in an older shape or broken is ignored`() = runTest {
        val server = FakeDashboard(testScheduler).filled().apply { readDelayMillis = 5_000 }
        val texts = listOf(
            """{"version":0,"owner":"$owner","entries":{"timeline:*":{"seq":1}}}""",
            """{"version":${FeedCache.Version},"owner":"$owner","entries":{"timeline:*":{"seq":"not a number"}}}""",
            "not json at all",
        )
        for (text in texts) {
            val dashboard = started(server, cache(MemoryTextStore(text)))
            assertTrue(dashboard.everything.state.value.cards.isEmpty(), "ignored: $text")
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(listOf("u1", "u2"), dashboard.everything.state.value.cards.map { it.id }, "and the server's feed still loads")
            dashboard.stop()
        }
    }

    @Test
    fun `the key itself never goes into the cache`() = runTest {
        val store = MemoryTextStore()
        firstLaunch(store, FakeDashboard(testScheduler).filled())
        assertFalse("device-key" in store.read().orEmpty())
    }

    @Test
    fun `a burst of changes is written once after a pause`() = runTest {
        var writes = 0
        val store = object : TextStore {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                writes++
                this.text = text
            }
        }
        val server = FakeDashboard(testScheduler).filled()
        started(server, cache(store))
        assertEquals(0, writes, "nothing yet: the reads just landed")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, writes, "four readers, one write")
    }

    @Test
    fun `signing out stops writing`() = runTest {
        val store = MemoryTextStore()
        val dashboard = started(FakeDashboard(testScheduler).filled(), cache(store))
        dashboard.stop()
        advanceUntilIdle()
        assertNull(store.read())
    }

    @Test
    fun `an oversized cache drops project feeds before the all-projects feed`() = runTest {
        val store = MemoryTextStore()
        val cache = cache(store, maxChars = 25_000)
        val big = "x".repeat(10_000)
        cache.save("timeline:*", big, String.serializer(), backgroundScope)
        cache.save("timeline:mega", big, String.serializer(), backgroundScope)
        cache.save("requests", "small", String.serializer(), backgroundScope)
        cache.save("threads:other", big, String.serializer(), backgroundScope)
        advanceTimeBy(1_500)
        runCurrent()
        val text = assertNotNull(store.read())
        assertTrue(text.length <= 25_000, "kept under the limit (${text.length})")
        assertTrue("\"timeline:*\"" in text, "the all-projects feed stays")
        assertTrue("\"requests\"" in text, "the questions stay")
        assertFalse("\"timeline:mega\"" in text, "the oldest project feed goes first")
        assertTrue("\"threads:other\"" in text, "and only as many as needed")
    }
}
