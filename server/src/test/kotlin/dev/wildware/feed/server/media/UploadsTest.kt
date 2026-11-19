package dev.wildware.feed.server.media

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an upload keeps of the name it came with (a known difference from the TS server: spec/README.md). */
class UploadsTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000, maxVideoBytes = 1_000, maxFileBytes = 1_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    @Test
    fun `sounds videos and files keep their names and pictures do not`() {
        App(config, Database.memory()).use { app ->
            val (agent, _) = app.agents.mint("scout")
            fun nameOf(filename: String, mime: String): String? {
                val id = app.uploads.reserve(agent.id, filename, mime, 10).mediaId
                return app.db.one("SELECT filename FROM media WHERE id = ?", id) { it.getString(1) }
            }
            assertEquals("voice-1.mp3", nameOf("voice-1.mp3", "audio/mpeg"))
            assertEquals("take.m4a", nameOf("  take.m4a ", "audio/x-m4a"))
            assertEquals("demo.mp4", nameOf("demo.mp4", "video/mp4"))
            assertEquals("report.pdf", nameOf("report.pdf", "application/pdf"))
            assertNull(nameOf("chart.png", "image/png"))
        }
    }
}
