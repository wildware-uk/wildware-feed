package dev.wildware.feed.server.auth

/**
 * A sliding-window limiter per key, in memory (forgotten on restart), as the TS server's login and
 * per-token limiters (spec/01-config-and-auth.md §5.2, §7.3). At most [maxKeys] keys are kept.
 * When there are more, the least recently used key that is not at its limit goes first, so a flood
 * of new keys can't push out a lockout that is still in force.
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
    fun record(key: String, count: Int = 1) {
        val now = clock()
        val list = hits.getOrPut(key) { ArrayDeque() }
        prune(list, now)
        repeat(count) { list.addLast(now) }
        while (hits.size > maxKeys) evictOne(now, keep = key)
    }

    /** Drops the least recently used key not at its limit (an empty one is not); only when all are, the oldest. */
    private fun evictOne(now: Long, keep: String) {
        val entries = hits.entries.iterator()
        while (entries.hasNext()) {
            val (key, list) = entries.next()
            if (key == keep) continue
            prune(list, now)
            if (list.size < limit) {
                entries.remove()
                return
            }
        }
        hits.keys.firstOrNull { it != keep }?.let(hits::remove) ?: hits.remove(keep)
    }

    /** Check and, when allowed, record in one step (the per-token limiter's `take`). */
    @Synchronized
    fun take(key: String): Verdict = check(key).also { if (it.allowed) record(key) }

    /** [take] for [count] at once: all of them fit under the limit, or none is recorded. */
    @Synchronized
    fun take(key: String, count: Int): Verdict {
        if (count <= 0) return Verdict(true)
        val now = clock()
        val list = hits[key]?.also { prune(it, now) }
        val used = list?.size ?: 0
        if (used + count <= limit) {
            record(key, count)
            return Verdict(true)
        }
        // When it would fit again: once enough of the oldest hits leave the window.
        val waitFor = list?.elementAtOrNull(used + count - limit - 1)
        return Verdict(false, if (waitFor == null || count > limit) windowMs else maxOf(waitFor + windowMs - now, 0))
    }

    /** Undo [count] [take]s that turned out not to count. */
    @Synchronized
    fun giveBack(key: String, count: Int = 1) {
        val list = hits[key] ?: return
        repeat(count) { list.removeLastOrNull() }
    }

    @Synchronized
    fun reset(key: String) {
        hits.remove(key)
    }

    private fun prune(list: ArrayDeque<Long>, now: Long) {
        while (list.isNotEmpty() && list.first() <= now - windowMs) list.removeFirst()
    }
}
