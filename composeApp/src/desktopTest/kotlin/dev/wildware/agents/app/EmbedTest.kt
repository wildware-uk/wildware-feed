package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.EmbedCard
import dev.wildware.agents.app.feed.FakeFeed
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Things agents quote onto cards, drawn in the look of where they came from. */
@OptIn(ExperimentalTestApi::class)
class EmbedTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun aJiraTicketShowsItsKeyTitleStatusAndOnlyTheFieldsThatArrived() = runComposeUiTest {
        setContent { AgentTheme(dark = true) { EmbedCard(FakeFeed.jiraEmbed) } }
        onNode(hasContentDescription("Jira embed")).assertExists()
        onNode(hasText("FEED-112")).assertExists()
        onNode(hasText("Stale push tokens are not cleared")).assertExists()
        onNode(hasText("IN PROGRESS")).assertExists()
        onNode(hasText("scout")).assertExists()
        assertTrue(onAllNodes(hasText("Reporter")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theFooterLinkOpensTheSource() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val uris = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        setContent { CompositionLocalProvider(LocalUriHandler provides uris) { AgentTheme(dark = false) { EmbedCard(FakeFeed.jiraEmbed) } } }
        onNode(hasContentDescription("Open Open in Jira")).performClick()
        assertEquals(listOf("https://example.atlassian.net/browse/FEED-112"), opened)
    }

    @Test
    fun aSlackMessageShowsItsChannelAuthorAndText() = runComposeUiTest {
        setContent { AgentTheme(dark = true) { EmbedCard(FakeFeed.slackEmbed) } }
        onNode(hasContentDescription("#ios embed")).assertExists()
        onNode(hasText("Sam")).assertExists()
        onNode(hasText("#ios")).assertExists()
    }

    @Test
    fun aCardInTheFeedCarriesItsEmbeds() = runComposeUiTest {
        // Only yesterday's card, so it is on screen.
        val yesterday = FakeFeed.state.sections.last()
        val card = (yesterday.items.first() as dev.wildware.agents.app.feed.FeedItem.Update).copy(embeds = listOf(FakeFeed.jiraEmbed))
        val state = FakeFeed.state.copy(sections = listOf(yesterday.copy(items = listOf(card))))
        setContent { AgentTheme(dark = true) { dev.wildware.agents.app.feed.FeedScreen(state, dev.wildware.agents.app.feed.SafeArea()) } }
        onNode(hasText("Push alerts failed for 2 devices"), useUnmergedTree = true).assertExists()
        assertTrue(onAllNodes(hasContentDescription("Jira embed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun shoot(name: String, dark: Boolean, widthDp: Int) = runDesktopComposeUiTest(widthDp * 2, 900) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                AgentTheme(dark = dark) {
                    Column(Modifier.background(AgentTheme.colors.surface).padding(16.dp).width((widthDp - 32).dp)) {
                        EmbedCard(FakeFeed.slackEmbed)
                        Box(Modifier.padding(top = 10.dp)) { EmbedCard(FakeFeed.jiraEmbed) }
                    }
                }
            }
        }
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test fun phoneScreenshot() = shoot("embeds-phone-dark", true, 390)
    @Test fun desktopScreenshot() = shoot("embeds-desktop-light", false, 620)
}
