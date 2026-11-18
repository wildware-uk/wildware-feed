package dev.wildware.feed.server.auth

/**
 * A sliding-window limiter per key, in memory (forgotten on restart), as the TS server's login and
 * per-token limiters (spec/01-config-and-auth.md §5.2, §7.3). At most [maxKeys] keys are kept;
 * the least recently used goes first.
 */
class SlidingLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val maxKeys: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Verdict(val allowed: Boolean, val retryAfterMs: Long = 0) {
        /** Retry-After in whole seconds, at least 1. */
        val retryAfterSeconds: Long get() = maxOf(1, (retryAfterMs + 999) / 1000)
    }

    private val hits = LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true)

    @Synchronized
    fun check(key: String): Verdict {
        val now = clock()
        val list = hits[key]?.also { prune(it, now) } ?: return Verdict(true)
        if (list.size < limit) return Verdict(true)
        return Verdict(false, maxOf(list.first() + windowMs - now, 0))
    }

    @Synchronized
    fun record(key: String) {
        val now = clock()
        val list = hits.getOrPut(key) { ArrayDeque() }
        prune(list, now)
        list.addLast(now)
        while (hits.size > maxKeys) hits.remove(hits.keys.first())
    }

    /** Check and, when allowed, record in one step (the per-token limiter's `take`). */
    @Synchronized
    fun take(key: String): Verdict = check(key).also { if (it.allowed) record(key) }

    @Synchronized
    fun reset(key: String) {
        hits.remove(key)
    }

    private fun prune(list: ArrayDeque<Long>, now: Long) {
        while (list.isNotEmpty() && list.first() <= now - windowMs) list.removeFirst()
    }
}
