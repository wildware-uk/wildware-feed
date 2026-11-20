package dev.wildware.feed.server.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The agent stream works out a burst of events once, not once per event (on a virtual clock). */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentStreamBatchTest {
    @Test
    fun aBurstIsWorkedOutOnce() = runTest {
        val events = Channel<Int>(Channel.UNLIMITED)
        val batches = mutableListOf<Pair<Long, List<Int>>>()
        val job = launch { inBatches(events, settleMs = 100, gapMs = 1_000) { batches += currentTime to it } }

        (1..50).forEach { events.trySend(it) }
        runCurrent()
        assertEquals(0, batches.size, "nothing yet: the burst is still gathering")
        advanceTimeBy(101)
        assertEquals(listOf(100L to (1..50).toList()), batches, "fifty events, one pass, 100 ms after the first")

        // More events during the pause wait, then go together, a full gap after the last pass.
        advanceTimeBy(200)
        (51..60).forEach { events.trySend(it) }
        advanceTimeBy(500)
        events.trySend(61)
        advanceTimeBy(2_000)
        assertEquals(2, batches.size)
        assertEquals(1_100L to (51..61).toList(), batches[1])

        // A lone event after a quiet spell goes out promptly.
        events.trySend(62)
        advanceTimeBy(101)
        assertEquals(3, batches.size)
        assertEquals(listOf(62), batches[2].second)
        assertEquals(2_901L, batches[2].first, "sent at 2801, out 100 ms later")

        events.close()
        job.join()
    }
}
