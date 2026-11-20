package dev.wildware.feed.server.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A client that stops reading is dropped, and a revoked one is cut even while a write is stuck (virtual clock). */
@OptIn(ExperimentalCoroutinesApi::class)
class SseSlowClientTest {
    private class Opened(val content: SseContent) {
        lateinit var sink: SseContent.Sink
    }

    private fun stream(maxQueuedBytes: Long, stallMs: Long = 600_000, allowed: () -> Boolean = { true }): Opened {
        var opened: Opened? = null
        val content = SseContent(
            heartbeatMs = 15_000, checkEveryMs = 5_000, stillAllowed = allowed,
            maxQueuedBytes = maxQueuedBytes, stallMs = stallMs,
        ) { sink -> opened!!.sink = sink; {} }
        return Opened(content).also { opened = it }
    }

    /** Send [chars] of frames, letting the writer run between them, as a busy stream would. */
    private fun TestScope.flood(sink: SseContent.Sink, chars: Int) {
        val frame = "x".repeat(16 * 1024)
        repeat(chars / frame.length) {
            sink.send(frame)
            runCurrent()
        }
    }

    @Test
    fun aClientThatStopsReadingIsDropped() = runTest {
        val s = stream(maxQueuedBytes = 256L * 1024)
        val neverRead = ByteChannel()
        val job = launch { s.content.writeTo(neverRead) }
        runCurrent()
        flood(s.sink, 4 * 1024 * 1024)
        advanceTimeBy(1_000)
        assertTrue(job.isCompleted, "the stream ends instead of holding megabytes for a client that left")
        assertEquals("client is not reading", s.content.cutReason)
    }

    @Test
    fun aStalledClientIsDroppedAfterAWhile() = runTest {
        val s = stream(maxQueuedBytes = 64L * 1024 * 1024, stallMs = 20_000)
        val neverRead = ByteChannel()
        val job = launch { s.content.writeTo(neverRead) }
        runCurrent()
        flood(s.sink, 4 * 1024 * 1024)
        advanceTimeBy(10_000)
        assertFalse(job.isCompleted, "not yet: a pause is not a stall")
        advanceTimeBy(20_000)
        assertTrue(job.isCompleted)
        assertEquals("client is not reading", s.content.cutReason)
    }

    @Test
    fun revokingCutsAStreamWhoseWriteIsStuck() = runTest {
        var allowed = true
        val s = stream(maxQueuedBytes = 64L * 1024 * 1024, allowed = { allowed })
        val neverRead = ByteChannel()
        val job = launch { s.content.writeTo(neverRead) }
        runCurrent()
        flood(s.sink, 4 * 1024 * 1024)
        advanceTimeBy(6_000)
        assertFalse(job.isCompleted)
        allowed = false
        advanceTimeBy(5_001)
        assertTrue(job.isCompleted, "the token check ends it although the writer is blocked")
        assertEquals("no longer allowed", s.content.cutReason)
    }

    @Test
    fun aClientThatReadsIsKept() = runTest {
        val s = stream(maxQueuedBytes = 256L * 1024, stallMs = 20_000)
        val channel = ByteChannel()
        val job = launch { s.content.writeTo(channel) }
        val reader = launch {
            val buffer = ByteArray(64 * 1024)
            while (channel.readAvailable(buffer) >= 0) Unit
        }
        runCurrent()
        flood(s.sink, 4 * 1024 * 1024)
        advanceTimeBy(120_000)
        assertFalse(job.isCompleted, "a client keeping up stays connected")
        assertEquals(null, s.content.cutReason)
        s.sink.close()
        advanceTimeBy(1_000)
        assertTrue(job.isCompleted)
        reader.cancel()
    }
}
