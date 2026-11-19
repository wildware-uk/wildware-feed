package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardApp
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An app on a card shows as a link to open it; one only the dashboard can show says so. */
@OptIn(ExperimentalTestApi::class)
class AppLinksTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val opened = mutableListOf<String>()
    private val uris = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }
    private val diskTitle = "Build box disk is 86% full"

    /** The disk update, carrying an app that is a page of its own and one the dashboard hosts. */
    private val state: FeedState = FakeFeed.state.copy(
        openThread = null,
        sections = FakeFeed.state.sections.map { s ->
            s.copy(items = s.items.map { item ->
                if (item is FeedItem.Update && item.id == "u3") item.copy(apps = listOf(
                    CardApp("disk", "Disk usage", "Live usage by folder", "https://disk.example.test/live"),
                    CardApp("prune", "Prune caches"),
                )) else item
            })
        },
    )

    private fun ComposeUiTest.show(scale: Float = 1f, dark: Boolean = true, width: Int? = null) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale), LocalUriHandler provides uris) {
            AgentTheme(dark = dark) {
                Box(if (width != null) Modifier.size(width.dp, 900.dp) else Modifier.fillMaxSize()) { FeedScreen(state, startAt = "u3") }
            }
        }
    }

    @Test
    fun aPageOfItsOwnOpensInTheBrowser() = runComposeUiTest {
        show(width = 1440)
        onNode(hasClickAction() and hasContentDescription("Open the app Disk usage")).performScrollTo().performClick()
        assertEquals(listOf("https://disk.example.test/live"), opened)
    }

    @Test
    fun aHostedAppSaysItCantBeShownAndOpensNothing() = runComposeUiTest {
        show(width = 390)
        onNode(hasContentDescription("App Prune caches. It can't be shown in the app yet.")).performScrollTo().assertExists()
        assertTrue(onAllNodes(hasClickAction() and hasContentDescription("Prune caches", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark) }
        }
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean) {
        show(scale, dark)
        onNode(hasContentDescription("App Prune caches", substring = true)).performScrollTo()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-app-links.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
