package dev.wildware.feed.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsConfigTest {
    private val base = mapOf(
        "ADMIN_PASSWORD_HASH" to "\$argon2id\$x",
        "SESSION_SECRET" to "s".repeat(32),
        "TOKEN_SECRET" to "t".repeat(32),
    )

    @Test
    fun `SECRETS_KEY is optional, at least 32 characters, and never printed`() {
        assertNull(Config.load(base).secretsKeys)
        val key = "k".repeat(32)
        val old = "o".repeat(32)
        val config = Config.load(base + mapOf("SECRETS_KEY" to key, "SECRETS_KEY_OLD" to " $old , $key "))
        assertEquals(key, config.secretsKeys!!.current)
        assertEquals(listOf(old), config.secretsKeys!!.old)
        assertFalse(config.toString().contains(key) || config.toString().contains(old))

        val short = assertFailsWith<Config.Invalid> { Config.load(base + ("SECRETS_KEY" to "short")) }
        assertTrue(short.issues.single().startsWith("SECRETS_KEY:"))
        assertFailsWith<Config.Invalid> { Config.load(base + mapOf("SECRETS_KEY" to key, "SECRETS_KEY_OLD" to "short")) }
        assertFailsWith<Config.Invalid> { Config.load(base + ("SECRETS_KEY_OLD" to old)) }
    }
}
