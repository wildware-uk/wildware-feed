package dev.wildware.feed.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test vectors from spec/01-config-and-auth.md, each checked against the TS server there. */
class CryptoTest {
    private val t32 = "t".repeat(32)

    @Test
    fun `agent tokens and device keys hash as HMAC-SHA256 hex under TOKEN_SECRET`() {
        assertEquals(
            "7fec7d5f16cdafa6abc866d824093848ee299a7ea40e0fcb79d83bed1462fdb0",
            Tokens.hash("A".repeat(43), t32),
        )
        assertTrue(Tokens.wellFormed("A".repeat(43)))
        assertFalse(Tokens.wellFormed("short"))
        assertEquals(43, Tokens.random().length)
    }

    @Test
    fun `share tokens hash with a share prefix, upload tokens sign their id`() {
        assertEquals(
            "b2c91b9cb359e29fe3c1a60f5b3ff3678a7e4aa00bb9cb610c7fb4a990ee3815",
            Tokens.hashShare("A".repeat(43), t32),
        )
        assertEquals(
            "01HZY3M5X8Q7R2T4V6W8Y0A2C4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E",
            Tokens.signUpload("01HZY3M5X8Q7R2T4V6W8Y0A2C4", t32),
        )
        assertEquals("01HZY3M5X8Q7R2T4V6W8Y0A2C4", Tokens.verifyUpload("01HZY3M5X8Q7R2T4V6W8Y0A2C4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E", t32))
        assertNull(Tokens.verifyUpload("01hzy3m5x8q7r2t4v6w8y0a2c4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E", t32))
        assertNull(Tokens.verifyUpload("01HZY3M5X8Q7R2T4V6W8Y0A2C4.N6mosJLXY_82aUyijaTlwhVZalq3a-FEniDWbkU2J-E.x", t32))
    }

    @Test
    fun `argon2id hashes in the TS format and verifies the TS server's hashes`() {
        val ts = "\$argon2id\$v=19\$m=65536,p=4,t=3\$sS0xJIH/XROdP+3fZ1XZQg\$6Dq1XKkl0J3S4bKnII+SuCGMGUJSW/ulGcwUNnMDlfc"
        assertTrue(Passwords.verify(ts, "correct horse battery staple"))
        assertFalse(Passwords.verify(ts, "wrong"))
        assertFalse(Passwords.verify(ts, ""))
        val mine = Passwords.hash("hunter2")
        assertTrue(Regex("^\\\$argon2id\\\$v=19\\\$m=65536,p=4,t=3\\\$[A-Za-z0-9+/]{22}\\\$[A-Za-z0-9+/]{43}$").matches(mine), mine)
        assertTrue(Passwords.verify(mine, "hunter2"))
    }

    @Test
    fun `the login limiter allows five wrong guesses in fifteen minutes`() {
        var now = 0L
        val limiter = SlidingLimiter(limit = 5, windowMs = 900_000, clock = { now })
        repeat(5) { assertTrue(limiter.check("a").allowed); limiter.record("a"); now += 1000 }
        val refused = limiter.check("a")
        assertFalse(refused.allowed)
        assertEquals(900_000 - 5000, refused.retryAfterMs)
        assertTrue(limiter.check("b").allowed)
        limiter.reset("a")
        assertTrue(limiter.check("a").allowed)
    }
}
