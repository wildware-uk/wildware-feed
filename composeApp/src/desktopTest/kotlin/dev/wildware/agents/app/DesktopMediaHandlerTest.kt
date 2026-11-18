package dev.wildware.agents.app

import dev.wildware.agents.app.feed.DesktopMediaHandler
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.Sound
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Where the desktop puts media, and that a file from an agent is saved but never run. */
class DesktopMediaHandlerTest {
    private val root: File = Files.createTempDirectory("media").toFile()
    private val opened = mutableListOf<File>()
    private val auth = MediaAuth("https://dash.test", "Bearer key-1")
    private val seen = mutableListOf<String?>()

    private fun handler(status: HttpStatusCode = HttpStatusCode.OK) = DesktopMediaHandler(
        engine = {
            MockEngine { request ->
                seen += request.headers["Authorization"]
                respond("bytes of ${request.url.encodedPath}", status)
            }
        },
        downloads = File(root, "Downloads"),
        playing = File(root, "playing"),
        openWithSystem = { opened += it },
    )

    @Test
    fun aFileIsSavedUnderItsOwnNameAndNotOpened() = runBlocking {
        val file = FileAttachment("doc", "https://dash.test/media/doc/original", "../../build.log", "1 KB", "text/plain")
        val first = handler().save(file, auth)
        val second = handler().save(file, auth)

        assertEquals("Saved to Downloads as build.log", first)
        assertEquals("Saved to Downloads as build (2).log", second)
        assertEquals("bytes of /media/doc/original", File(root, "Downloads/build.log").readText())
        assertTrue(opened.isEmpty(), "a saved file is never opened")
        assertEquals(listOf<String?>("Bearer key-1", "Bearer key-1"), seen)
    }

    @Test
    fun aSoundOpensInTheSystemPlayerWithAnExtensionThatSaysWhatItIs() = runBlocking {
        handler().open(Sound("snd", "https://dash.test/media/snd/original", 9_000, "audio/mpeg"), auth)
        assertEquals("sound.mp3", opened.single().name)
        assertEquals("bytes of /media/snd/original", opened.single().readText())
    }

    @Test
    fun theKeyGoesOnlyToTheDashboard() = runBlocking {
        handler().open(Sound("x", "https://elsewhere.test/a.mp3", null, "audio/mpeg"), auth)
        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun aRefusedDownloadSaysSoAndLeavesNothingBehind() {
        val file = FileAttachment("doc", "https://dash.test/media/doc/original", "build.log", "1 KB", "text/plain")
        assertFailsWith<IllegalStateException> { runBlocking { handler(HttpStatusCode.NotFound).save(file, auth) } }
        assertTrue(File(root, "Downloads").listFiles().orEmpty().none { it.name == "build.log" })
    }
}
