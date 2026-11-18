package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import androidx.compose.ui.unit.dp

/** The demo as a reviewer first sees it: the sign-in screen's button, then the demo feed. */
@OptIn(ExperimentalTestApi::class)
class DemoScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    private fun ComposeUiTest.phone(dark: Boolean) {
        val platform = AppPlatform.inMemory(engine = { error("no network in the demo") })
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                Box(Modifier.fillMaxSize()) {
                    AgentsApp(platform, dark = dark, accessibility = AccessibilityPrefs(), safeArea = SafeArea(top = 54.dp, bottom = 34.dp))
                }
            }
        }
    }

    @Test
    fun phoneDark() = runDesktopComposeUiTest(1170, 2532) {
        phone(dark = true)
        save("phone-dark-demo-sign-in")
        onNodeWithText("Try a demo").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Use the new watering-can sound", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        save("phone-dark-demo-feed")
    }

    @Test
    fun phoneLight() = runDesktopComposeUiTest(1170, 2532) {
        phone(dark = false)
        onNodeWithText("Try a demo").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Use the new watering-can sound", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        save("phone-light-demo-feed")
    }
}
