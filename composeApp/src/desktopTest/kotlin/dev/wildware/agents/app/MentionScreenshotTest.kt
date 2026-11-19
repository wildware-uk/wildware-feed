package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The @ list open in the feed's message box. */
@OptIn(ExperimentalTestApi::class)
class MentionScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea, typed: String) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state, safeArea) } }
            }
        }
        val box = onAllNodes(hasContentDescription("Message") and hasSetTextAction()).onFirst()
        box.performClick()
        box.performTextInput(typed)
        box.performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test fun phone() = runDesktopComposeUiTest(1170, 2532) { shoot("phone-dark-mention-groups", 3f, true, SafeArea(top = 54.dp, bottom = 34.dp), "Can you check this @") }
    @Test fun desktop() = runDesktopComposeUiTest(2880, 1800) { shoot("desktop-light-mention-groups", 2f, false, SafeArea(), "Over to @") }
}
