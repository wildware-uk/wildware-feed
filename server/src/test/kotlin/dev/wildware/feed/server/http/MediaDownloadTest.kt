package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.media.Serve
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A picture or video saved from the app comes whole, as itself, under its own name: `?download=1`. */
class MediaDownloadTest {
    private fun config(dataDir: String) = Config(
        dataDir = dataDir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun png(): ByteArray {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, 0xFF8800) }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `the original of a picture downloads as an attachment only for someone signed in`() {
        val dir = Files.createTempDirectory("media-download").toFile()
        val app = App(config(dir.path), Database.memory())
        val (_, ownerKey) = app.devices.mint("Phone")
        val bytes = png()
        try {
            testApplication {
                application { feed(app) }
                suspend fun upload(name: String): String {
                    val response = client.post("/api/media?filename=$name") {
                        header("Authorization", "Bearer $ownerKey")
                        header("Content-Type", "image/png")
                        setBody(bytes)
                    }
                    assertEquals(201, response.status.value, response.bodyAsText())
                    return Wire.json.parseToJsonElement(response.bodyAsText()).jsonObject["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
                }
                val chart = upload("chart.png")

                val shown = client.get("/media/$chart/original") { header("Authorization", "Bearer $ownerKey") }
                assertEquals(200, shown.status.value)
                assertEquals("inline", shown.headers["Content-Disposition"], "shown in place as before")

                val saved = client.get("/media/$chart/original?download=1") { header("Authorization", "Bearer $ownerKey") }
                assertEquals(200, saved.status.value)
                // A picture keeps no name of its own on the dashboard, so it is saved as what it is.
                assertEquals("attachment; filename=\"image.png\"; filename*=UTF-8''image.png", saved.headers["Content-Disposition"])
                assertEquals("image/png", saved.headers["Content-Type"], "still says what it is")
                assertEquals("nosniff", saved.headers["X-Content-Type-Options"])
                assertContentEquals(bytes, saved.readRawBytes(), "the original bytes, not a thumbnail")

                assertEquals(401, client.get("/media/$chart/original?download=1").status.value, "nobody signed in gets nothing")
                assertEquals(401, client.get("/media/$chart/original?download=1") { header("Authorization", "Bearer not-a-key") }.status.value)

                // A name without an extension (a video's, say) gets one, so the computer knows what it is.
                app.db.update("UPDATE media SET filename = ? WHERE team_id = :team AND id = ?", "Screen shot", chart)
                val named = client.get("/media/$chart/original?download=1") { header("Authorization", "Bearer $ownerKey") }
                assertEquals("attachment; filename=\"Screen shot.png\"; filename*=UTF-8''Screen%20shot.png", named.headers["Content-Disposition"])
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a saved picture or video always has a name with an extension`() {
        assertEquals("clip.mp4", Serve.savedName("clip.mp4", "video", "video/mp4"))
        // The extension is always the checked type's: one that would run on a computer is replaced.
        assertEquals("clip.mp4", Serve.savedName("clip.hta", "video", "video/mp4"))
        assertEquals("photo.jpg", Serve.savedName("photo.exe", "image", "image/jpeg"))
        assertEquals("video.mp4", Serve.savedName(".hta", "video", "video/mp4"))
        assertEquals("notes.mp4", Serve.savedName("C:\\x\\notes.bat", "video", "video/mp4"))
        assertEquals("Screen recording.webm", Serve.savedName("Screen recording", "video", "video/webm"))
        assertEquals("image.jpg", Serve.savedName(null, "image", "image/jpeg"))
        assertEquals("image.png", Serve.savedName("  ", "image", "image/png"))
    }
}
