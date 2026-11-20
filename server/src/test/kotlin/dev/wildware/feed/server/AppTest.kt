package dev.wildware.feed.server

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.checkPassword
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    @Test
    fun `an event published while holding the database lock is refused, and one published after is not`() {
        App(config, Database.memory()).use { app ->
            val none = JsonObject(emptyMap())
            val error = assertFailsWith<IllegalStateException> {
                app.db.transaction { app.bus.publish("project.created", none) }
            }
            assertEquals("project.created published while holding the database lock; publish after the write", error.message)
            assertFailsWith<IllegalStateException> { app.db.withConnection { app.bus.publish("x", none) } }
            assertEquals(0, app.bus.lastSeq, "a refused publish takes no seq")
            app.db.update("UPDATE schema_migrations SET applied_at = applied_at")
            app.bus.publish("project.created", none)
            assertEquals(1, app.bus.lastSeq)
        }
    }

    @Test
    fun `a desktop host does not lock its own loopback clients out, a normal server does`() {
        val hash = dev.wildware.feed.server.auth.Passwords.hash("right")
        fun attempts(trust: Boolean, address: String): List<Int> =
            App(config.copy(adminPasswordHash = hash, trustLoopback = trust), Database.memory()).use { app ->
                (1..7).map { i ->
                    when (val r = app.checkPassword(if (i == 7) "right" else "wrong", address)) {
                        is dev.wildware.feed.server.http.PasswordCheck.Refused -> r.status
                        else -> 200
                    }
                }
            }
        assertEquals(listOf(400, 400, 400, 400, 400, 400, 200), attempts(true, "127.0.0.1"))
        assertEquals(listOf(400, 400, 400, 400, 400, 400, 200), attempts(true, "::1"))
        assertEquals(listOf(400, 400, 400, 400, 400, 429, 429), attempts(false, "127.0.0.1"))
        assertEquals(listOf(400, 400, 400, 400, 400, 429, 429), attempts(true, "192.0.2.7"), "only loopback is exempt")
    }

    @Test
    fun `a desktop host limits wrong guesses from loopback on a budget of their own`() {
        val hash = dev.wildware.feed.server.auth.Passwords.hash("right")
        App(config.copy(adminPasswordHash = hash, trustLoopback = true), Database.memory()).use { app ->
            fun status(password: String, address: String) = when (val r = app.checkPassword(password, address)) {
                is dev.wildware.feed.server.http.PasswordCheck.Refused -> r.status
                else -> 200
            }
            repeat(20) { assertEquals(400, status("wrong", "127.0.0.1")) }
            assertEquals(429, status("right", "::1"), "every loopback client shares one budget")
            assertEquals(200, status("right", "192.0.2.7"), "a phone on the network keeps its own")
        }
    }
}
