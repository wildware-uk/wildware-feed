package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LogoChoice
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.Scene
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** A project's logo, beside its name or instead of it, picked from pictures already posted there. */
@OptIn(ExperimentalTestApi::class)
class ProjectLogoTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val name = FakeFeed.state.current.name

    private val choices = listOf(
        LogoChoice("md1", Attachment("dusk.png", "Dusk", aspect = 1f, scene = Scene.Dusk)),
        LogoChoice("md2", Attachment("dawn.png", "Dawn", aspect = 1f, scene = Scene.Dawn)),
        LogoChoice("md3", Attachment("night.png", "Night", aspect = 1f, scene = Scene.Night)),
    )

    /** The open project with picture md1 as its logo, standing in for the name when [instead]. */
    private fun withLogo(instead: Boolean, logoId: String? = "md1"): FeedState {
        val logo = choices.firstOrNull { it.mediaId == logoId }?.image?.copy(aspect = if (instead) 3f else 1f)
        val p = FakeFeed.state.current.copy(logo = logo, logoMediaId = logoId, logoInsteadOfName = instead && logo != null)
        return FakeFeed.state.copy(current = p, projects = FakeFeed.state.projects.map { if (it.id == p.id) p else it }, logoChoices = choices)
    }

    private fun ComposeUiTest.feed(state: FeedState) = setContent {
        AgentTheme(dark = true) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state, onEvent = { events += it }) } }
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun pickingAPictureMakesItTheLogo() = runComposeUiTest {
        feed(withLogo(instead = false, logoId = null))
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasContentDescription("Use picture 2 as the logo")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.SetProjectLogo("agent-dashboard", "md2")), events.filterIsInstance<FeedEvent.SetProjectLogo>())
        // No logo yet: nothing to show instead of the name, nothing to remove.
        onNode(hasText("Use the logo instead of the name")).assertDoesNotExist()
        onNode(hasText("Remove logo")).assertDoesNotExist()
    }

    @Test
    fun aLogoCanStandInForTheNameOrBeRemoved() = runComposeUiTest {
        feed(withLogo(instead = false))
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasText("Use the logo instead of the name")).performScrollTo().performClick()
        onNode(hasText("Remove logo")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.SetLogoInsteadOfName("agent-dashboard", true)), events.filterIsInstance<FeedEvent.SetLogoInsteadOfName>())
        assertEquals(listOf(FeedEvent.SetProjectLogo("agent-dashboard", null)), events.filterIsInstance<FeedEvent.SetProjectLogo>())
    }

    @Test
    fun aWordmarkIsTheHeadingAndSaysTheName() = runComposeUiTest {
        feed(withLogo(instead = true))
        onNode(heading and hasContentDescription(name)).assertExists()
        onNode(heading and hasText(name)).assertDoesNotExist()
    }

    @Test
    fun besideTheNameTheNameStaysTheHeading() = runComposeUiTest {
        feed(withLogo(instead = false))
        onNode(heading and hasText(name)).assertExists()
    }

    private fun ComposeUiTest.shoot(file: String, state: FeedState, scale: Float, dark: Boolean, safeArea: SafeArea, settings: Boolean = false) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, safeArea) } }
            }
        }
        if (settings) {
            onNode(hasContentDescription("Project settings")).performClick()
            onNode(hasText("Use the logo instead of the name")).performScrollTo()
        }
        waitForIdle()
        val out = File(outDir, "$file-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
        println("screenshot: ${out.absolutePath}")
    }

    @Test
    fun screenshots() {
        val phone = SafeArea(top = 54.dp, bottom = 34.dp)
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop-project-logo", withLogo(instead = false), 2f, dark, SafeArea()) }
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop-project-wordmark", withLogo(instead = true), 2f, dark, SafeArea()) }
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop-project-logo-picker", withLogo(instead = false), 2f, dark, SafeArea(), settings = true) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone-project-logo", withLogo(instead = false), 3f, dark, phone) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone-project-logo-picker", withLogo(instead = false), 3f, dark, phone, settings = true) }
        }
    }
}
