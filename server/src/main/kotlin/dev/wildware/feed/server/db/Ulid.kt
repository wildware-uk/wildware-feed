package dev.wildware.feed.server.db

import java.security.SecureRandom

/**
 * ULIDs as the TS server mints them (`ulid` monotonicFactory, spec/06-database.md §2): 26 Crockford
 * base32 characters, time first, and within one millisecond each id is the last plus one, so id
 * order is insertion order.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private var lastTime = -1L
    private var lastRandom = IntArray(16)

    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): String {
        if (now <= lastTime) {
            increment(lastRandom)
        } else {
            lastTime = now
            lastRandom = IntArray(16) { random.nextInt(32) }
        }
        val out = CharArray(26)
        var t = lastTime
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t % 32).toInt()]
            t /= 32
        }
        for (i in 0 until 16) out[10 + i] = ALPHABET[lastRandom[i]]
        return String(out)
    }

    private fun increment(digits: IntArray) {
        for (i in digits.indices.reversed()) {
            if (digits[i] < 31) {
                digits[i]++
                return
            }
            digits[i] = 0
        }
        error("ULID random part overflowed within one millisecond")
    }

    private val shape = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
    fun valid(id: String) = shape.matches(id)
}
