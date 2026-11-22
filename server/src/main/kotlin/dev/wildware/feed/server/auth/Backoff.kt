package dev.wildware.feed.server.auth

/**
 * A growing wait between wrong passwords for one key, in memory (forgotten on restart). The first
 * [free] wrong guesses wait for nothing; after that each one must come at least
 * `baseMs * 2^(n - free)` after the last, up to [maxMs]. Only the right password ([clear]), or
 * [forgetMs] with no wrong guess, starts the count again, so spreading guesses over many addresses
 * or waiting out a sliding window doesn't reset it. At most [maxKeys] keys are kept; the least
 * recently used one goes first.
 */
class Backoff(
    private val free: Int,
    private val baseMs: Long,
    private val maxMs: Long,
    private val forgetMs: Long,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(var failures: Int, var last: Long)

    private val states = LinkedHashMap<String, State>(16, 0.75f, true)

    /** The wait before the next guess at [key] is let through, in ms; 0 for none. */
    @Synchronized
    fun waitMs(key: String): Long {
        val now = clock()
        val state = live(key, now) ?: return 0
        if (state.failures < free) return 0
        return maxOf(state.last + delay(state.failures) - now, 0)
    }

    /**
     * Counts one guess at [key] as wrong, before it is checked, when it is let through; else the
     * wait in ms. A guess that turns out right is undone by [clear], one never checked by [giveBack].
     */
    @Synchronized
    fun take(key: String): SlidingLimiter.Verdict {
        val wait = waitMs(key)
        if (wait > 0) return SlidingLimiter.Verdict(false, wait)
        val now = clock()
        val state = states.getOrPut(key) { State(0, now) }
        state.failures++
        state.last = now
        while (states.size > maxKeys) states.keys.firstOrNull { it != key }?.let(states::remove) ?: break
        return SlidingLimiter.Verdict(true)
    }

    /** Undoes one [take] whose guess was never checked. */
    @Synchronized
    fun giveBack(key: String) {
        val state = states[key] ?: return
        state.failures = maxOf(state.failures - 1, 0)
    }

    /** The right password: the count starts again. */
    @Synchronized
    fun clear(key: String) {
        states.remove(key)
    }

    private fun live(key: String, now: Long): State? {
        val state = states[key] ?: return null
        if (now - state.last >= forgetMs) {
            states.remove(key)
            return null
        }
        return state
    }

    private fun delay(failures: Int): Long {
        val steps = (failures - free).coerceIn(0, 40)
        return minOf(baseMs shl steps, maxMs).let { if (it <= 0) maxMs else it }
    }
}
