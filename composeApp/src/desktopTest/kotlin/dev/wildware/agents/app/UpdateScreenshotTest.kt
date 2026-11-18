package dev.wildware.agents.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.wildware.agents.app.update.InstallState
import dev.wildware.agents.app.update.Release
import dev.wildware.agents.app.update.UpdateActions
import dev.wildware.agents.app.update.UpdateDialog
import dev.wildware.agents.app.update.UpdatePillPlaced
import dev.wildware.agents.app.update.UpdatePrompt
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The update pill over the feed, and the dialog at each step, on made-up data. */
class UpdateScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private val release = Release(
        version = "1.0.2",
        notes = "### What's new\n\n- The app updates itself: look for **Update available** at the top right.\n- Replies send faster on a slow connection.\n- Fixed the tray count after waking from sleep.",
    )

    @Test
    fun pillAndDialog() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            shoot("desktop-$theme-update-pill", dark) {
                UpdatePillPlaced("1.0.2") {}
            }
            shoot("desktop-$theme-update-dialog", dark) {
                UpdatePillPlaced("1.0.2") {}
                UpdateDialog(UpdatePrompt.Offer(release), InstallState.Idle, current = "1.0.1", actions = UpdateActions())
            }
        }
        shoot("narrow-light-update-pill", dark = false, width = 480, height = 820) { UpdatePillPlaced("1.0.2") {} }
        shoot("desktop-light-update-pill-no-thread", dark = false, width = 1000, height = 700) { UpdatePillPlaced("1.0.2") {} }
        shoot("desktop-dark-update-downloading", dark = true) {
            UpdateDialog(UpdatePrompt.Offer(release), InstallState.Downloading(61_000_000, 98_000_000), current = "1.0.1", actions = UpdateActions())
        }
        shoot("desktop-light-update-failed", dark = false) {
            UpdateDialog(
                UpdatePrompt.Offer(release),
                InstallState.Failed("The download didn't match its checksum, so it wasn't installed. Try again."),
                current = "1.0.1",
                actions = UpdateActions(),
            )
        }
    }

    private fun shoot(name: String, dark: Boolean, width: Int = 1440, height: Int = 900, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val scale = 2f
        ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentsPreview(dark = dark, overlay = overlay)
            }
        }.use { scene ->
            var t = 0L
            repeat(8) {
                scene.render(t)
                t += 16_000_000L
                Thread.sleep(40)
            }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
            val file = File(outDir, "$name.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name is empty")
            println("screenshot: ${file.absolutePath}")
        }
    }
}
