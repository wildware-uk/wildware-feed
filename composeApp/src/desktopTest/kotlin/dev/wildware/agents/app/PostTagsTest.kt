package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tags picked before a post is written go up with it, as on the website. */
@OptIn(ExperimentalTestApi::class)
class PostTagsTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.feed(width: Int) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), onEvent = { events += it }) }
        }
    }

    private fun ComposeUiTest.pick(vararg names: String, typed: String? = null) {
        onNodeWithContentDescription("Add a tag").performClick()
        names.forEach { onNode(hasContentDescription("$it, off")).performClick() }
        if (typed != null) {
            onNode(hasContentDescription("New tag") and hasSetTextAction()).performTextInput(typed)
            onNode(hasText("Add")).performClick()
        }
        onNode(hasText("Done") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.write(text: String) {
        onNode(hasContentDescription("Message") and hasSetTextAction()).performTextInput(text)
        onNodeWithContentDescription("Send").performClick()
        waitForIdle()
    }

    @Test
    fun pickedTagsGoWithThePostAndNothingIsSentBefore() = runComposeUiTest {
        feed(1440)
        pick("bug", typed = "release")
        assertTrue(events.none { it is FeedEvent.Tag }, "nothing is tagged until there is a post")
        onNode(hasContentDescription("Take the tag bug off this post")).assertExists()
        write("Release notes are up")
        val post = events.filterIsInstance<FeedEvent.Post>().single()
        assertEquals(listOf("t1" to "bug", "new:release" to "release"), post.tags.map { it.id to it.name })
        // The next post starts with none.
        onNode(hasContentDescription("Take the tag bug off this post")).assertDoesNotExist()
    }

    @Test
    fun aPickedTagComesOffBeforePosting() = runComposeUiTest {
        feed(1440)
        pick("bug", "sessions")
        onNode(hasContentDescription("Take the tag bug off this post")).performClick()
        write("Only sessions")
        assertEquals(listOf("sessions"), events.filterIsInstance<FeedEvent.Post>().single().tags.map { it.name })
    }

    @Test
    fun aPhoneTagsItsPostToo() = runComposeUiTest {
        feed(390)
        pick("bug")
        write("From the phone")
        assertEquals(listOf("bug"), events.filterIsInstance<FeedEvent.Post>().single().tags.map { it.name })
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(openThread = null), safeArea) } }
            }
        }
        pick("bug", "sessions")
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-post-tags.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark, SafeArea()) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark, SafeArea(top = 54.dp, bottom = 34.dp)) }
        }
    }
}
