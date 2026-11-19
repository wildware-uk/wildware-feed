package dev.wildware.agents.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.wildware.agents.app.update.InstallState
import dev.wildware.agents.app.update.PillPlaced
import dev.wildware.agents.app.update.Release
import dev.wildware.agents.app.update.RestartPill
import dev.wildware.agents.app.update.UpdateActions
import dev.wildware.agents.app.update.UpdateDialog
import dev.wildware.agents.app.update.UpdatePrompt
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** "Restart to update" over the feed, its menu with the setting, and the setting in the dialog. */
class AutoUpdateScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "autoupdate").apply { mkdirs() }

    private val release = Release(
        version = "1.0.2",
        notes = "### What's new\n\n- Updates download by themselves: look for **Restart to update** at the top right.\n- Replies send faster on a slow connection.",
    )

    @Test
    fun restartPillMenuAndSetting() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            shoot("desktop-$theme-restart-pill", dark) { PillPlaced { RestartPill("1.0.2", it) } }
            shoot("desktop-$theme-restart-menu", dark) { PillPlaced { RestartPill("1.0.2", it, menuOpen = true) } }
            shoot("desktop-$theme-restart-pill-password", dark) { PillPlaced { RestartPill("1.0.2", it, asksForPassword = true) } }
            shoot("desktop-$theme-update-setting", dark) {
                UpdateDialog(UpdatePrompt.Offer(release), InstallState.Idle, current = "1.0.1", actions = UpdateActions(), automatic = false)
            }
        }
        shoot("desktop-light-restarting", dark = false) { PillPlaced { RestartPill("1.0.2", it, busy = "Restarting…") } }
        shoot("desktop-dark-restart-menu-password", dark = true) { PillPlaced { RestartPill("1.0.2", it, asksForPassword = true, menuOpen = true) } }
        shoot("narrow-light-restart-pill", dark = false, width = 480, height = 820) { PillPlaced { RestartPill("1.0.2", it) } }
    }

    private fun shoot(name: String, dark: Boolean, width: Int = 1440, height: Int = 900, overlay: @Composable BoxScope.() -> Unit) {
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
