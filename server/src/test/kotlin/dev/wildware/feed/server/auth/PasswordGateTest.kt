package dev.wildware.feed.server.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The gate in front of every password hash, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordGateTest {
    @Test
    fun `no more callers hold a turn at once than the gate has`() = runTest {
        val gate = PasswordGate(2, waitMs = 60_000, io = StandardTestDispatcher(testScheduler))
        var active = 0
        var peak = 0
        List(10) {
            launch {
                gate.withTurn {
                    active++
                    peak = maxOf(peak, active)
                    delay(100)
                    active--
                }
            }
        }.joinAll()
        assertEquals(2, peak)
    }

    @Test
    fun `a caller who waits too long is told the server is busy and the turn is not lost`() = runTest {
        val gate = PasswordGate(1, waitMs = 2_000, io = StandardTestDispatcher(testScheduler))
        val holder = launch { gate.withTurn { delay(10_000) } }
        runCurrent()
        assertFailsWith<PasswordGate.Busy> { gate.run { "too late" } }
        holder.join()
        // The timed-out wait gave nothing away and took nothing: the one turn is free again.
        assertEquals("next", gate.run { "next" })
        assertEquals("then", gate.run { "then" })
    }

    @Test
    fun `a full queue refuses at once instead of making the caller wait`() = runTest {
        val gate = PasswordGate(1, waitMs = 60_000, io = StandardTestDispatcher(testScheduler), maxWaiting = 1)
        val holder = launch { gate.withTurn { delay(10_000) } }
        val waiter = launch { gate.withTurn { } }
        runCurrent()
        val start = testScheduler.currentTime
        assertFailsWith<PasswordGate.Full> { gate.withTurn { } }
        assertEquals(start, testScheduler.currentTime, "refused without waiting")
        joinAll(holder, waiter)
        assertEquals("free", gate.run { "free" })
    }

    @Test
    fun `password work inside a turn takes no second turn`() = runTest {
        val gate = PasswordGate(1, waitMs = 2_000, io = StandardTestDispatcher(testScheduler))
        assertEquals(2, gate.run { gate.blocking { 1 } + gate.blocking { 1 } })
    }

    @Test
    fun `PASSWORD_HASH_CONCURRENCY sets the turns and the heap sizes them otherwise`() {
        val mib = 1024L * 1024
        assertEquals(1, PasswordGate.fromEnv(emptyMap(), maxHeap = 206 * mib).permits, "Heroku's heap fits one")
        assertEquals(3, PasswordGate.fromEnv(mapOf("PASSWORD_HASH_CONCURRENCY" to "3"), maxHeap = 206 * mib).permits)
        assertEquals(1, PasswordGate.fromEnv(mapOf("PASSWORD_HASH_CONCURRENCY" to "0"), maxHeap = 206 * mib).permits)
        assertEquals(1, PasswordGate.forHeap(64 * mib))
        assertEquals(2, PasswordGate.forHeap(300 * mib))
        assertEquals(4, PasswordGate.forHeap(8192 * mib))
    }
}
