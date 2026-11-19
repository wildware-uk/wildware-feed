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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AlertSettings
import dev.wildware.agents.app.feed.CriticalNotice
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalCriticalAlertSettings
import dev.wildware.agents.app.feed.LocalPushAlerts
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A critical question and update, the desktop banner, and the setting on a phone. */
@OptIn(ExperimentalTestApi::class)
class CriticalScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private val state: FeedState = FakeFeed.state.let { s ->
        var questions = 0
        s.copy(
            sections = s.sections.map { section ->
                section.copy(items = section.items.map {
                    when (it) {
                        is FeedItem.Question -> if (questions++ == 0) it.copy(critical = true) else it
                        is FeedItem.Update -> if (it.high) it.copy(critical = true, high = false) else it
                        else -> it
                    }
                })
            },
            critical = listOf(CriticalNotice("q", "relay", "Turn on reaction sounds for everyone?", question = true)),
            alerts = AlertSettings(AlertPrefs(critical = true, criticalAgents = listOf(s.agents.first().id)), loaded = true),
        )
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview = ScreenPreview(), android: Boolean = false) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(scale),
                LocalPushAlerts provides !android,
                LocalCriticalAlertSettings provides (if (android) ({}) else null),
            ) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, safeArea, preview = preview) } }
            }
        }
        waitForIdle()
        // The critical group sits below the other filters; show it with its agent list.
        if (preview.alertsOpen) runCatching { onNode(hasText("FROM AGENTS", substring = true)).performScrollTo() }
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    private val phoneArea = SafeArea(top = 54.dp, bottom = 34.dp)

    @Test fun phoneCard() = runDesktopComposeUiTest(1170, 2532) { shoot("phone-dark-critical-card", 3f, true, phoneArea) }
    @Test fun phoneSetting() = runDesktopComposeUiTest(1170, 2532) { shoot("phone-dark-critical-setting", 3f, true, phoneArea, ScreenPreview(alertsOpen = true)) }
    @Test fun androidSetting() = runDesktopComposeUiTest(1170, 2532) { shoot("android-light-critical-setting", 3f, false, phoneArea, ScreenPreview(alertsOpen = true), android = true) }
    @Test fun desktopCard() = runDesktopComposeUiTest(2880, 1800) { shoot("desktop-light-critical-banner", 2f, false, SafeArea()) }
    @Test fun desktopDark() = runDesktopComposeUiTest(2880, 1800) { shoot("desktop-dark-critical-banner", 2f, true, SafeArea()) }
}
