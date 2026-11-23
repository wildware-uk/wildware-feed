package dev.wildware.feed.server.auth

import dev.wildware.feed.server.domain.DomainError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * At most [permits] password checks at once. Each argon2 hash holds about 69 MiB while it runs, so with
 * no cap a burst of sign-ins could ask for more memory than the server has. A caller waits up to
 * [waitMs] for its turn, then gets [Busy] (503, try again) instead of piling up.
 */
class PasswordGate(
    val permits: Int,
    private val waitMs: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** At most this many callers wait for a turn; the next is refused at once ([Full]), not queued. */
    val maxWaiting: Int = permits * 8,
) {
    class Busy : DomainError("busy", "The server is busy. Try again in a moment.", 503)

    /** The queue is full: refused before waiting, so a flood can't hold everyone's sign-in for seconds. */
    class Full : DomainError("rate_limited", "Too many password checks right now. Try again in a moment.", 429)

    private val semaphore = Semaphore(permits)
    private val waiting = java.util.concurrent.atomic.AtomicInteger()

    /** Set on the thread doing a turn's work, so the hashes inside it take no second turn. */
    private val holding = ThreadLocal<Boolean>()

    /**
     * For request handlers: waits for a turn without holding a thread, then runs [block] on the IO
     * pool. Every hash [block] does (a sign-in, an invite's new password) shares the one turn.
     */
    suspend fun <T> run(block: () -> T): T = withTurn { withContext(io) { held(block) } }

    /** For everything else (the command line, a caller not wrapped in [run]): blocks this thread for a turn. */
    fun <T> blocking(block: () -> T): T {
        if (holding.get() == true) return block()
        return runBlocking { withTurn { held(block) } }
    }

    internal suspend fun <T> withTurn(body: suspend () -> T): T {
        if (waiting.incrementAndGet() > maxWaiting + permits) {
            waiting.decrementAndGet()
            throw Full()
        }
        try {
            return withTurnCounted(body)
        } finally {
            waiting.decrementAndGet()
        }
    }

    private suspend fun <T> withTurnCounted(body: suspend () -> T): T {
        var got = false
        // Nothing suspends between acquire and `got = true`, so a timeout either cancels the wait
        // (the semaphore keeps its permit) or finds the permit ours.
        withTimeoutOrNull(waitMs) {
            semaphore.acquire()
            got = true
        }
        if (!got) throw Busy()
        try {
            return body()
        } finally {
            semaphore.release()
        }
    }

    private fun <T> held(block: () -> T): T {
        holding.set(true)
        try {
            return block()
        } finally {
            holding.remove()
        }
    }

    companion object {
        private const val MIB = 1024L * 1024

        /**
         * PASSWORD_HASH_CONCURRENCY, or sized from the heap: a hash holds about 69 MiB, so after 128 MiB
         * for the rest of the server, one per 80 MiB, at least one and at most four. Heroku's 206 MiB heap gets one.
         */
        fun fromEnv(env: Map<String, String> = System.getenv(), maxHeap: Long = Runtime.getRuntime().maxMemory()) =
            PasswordGate(env["PASSWORD_HASH_CONCURRENCY"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: forHeap(maxHeap), waitMs = 2_000)

        fun forHeap(maxHeap: Long): Int = ((maxHeap - 128 * MIB) / (80 * MIB)).coerceIn(1, 4).toInt()
    }
}
