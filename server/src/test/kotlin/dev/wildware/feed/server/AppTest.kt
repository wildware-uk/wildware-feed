package dev.wildware.feed.server

import dev.wildware.feed.server.db.Database
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
}
