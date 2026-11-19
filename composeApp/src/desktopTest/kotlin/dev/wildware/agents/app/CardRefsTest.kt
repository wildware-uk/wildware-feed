package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRefView
import dev.wildware.agents.MediaView
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ProjectView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.engine.FeedMapper
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.Highlighted
import dev.wildware.agents.app.feed.LinkedCard
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.Scene
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.VideoSource
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.wildware.agents.FeedState as EngineFeed

/**
 * Card refs: an agent replies on the work card with a video, then asks a question pointing at that
 * reply. The question shows the reply small, video and all, and a tap opens the reply's thread at it.
 */
@OptIn(ExperimentalTestApi::class)
class CardRefsTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    // ---- mapping ---------------------------------------------------------------------------------

    private val noon = 1_795_003_200_000L
    private val mapper = FeedMapper("https://dash.test/", now = { noon }, zone = TimeZone.UTC)
    private val mega = ProjectSummary(ProjectView(id = "p1", slug = "mega", name = "Mega"), waiting = 1, unread = 0)
    private val web = ProjectSummary(ProjectView(id = "p2", slug = "web", name = "Web"), waiting = 0, unread = 0)

    private fun asked(refs: List<CardRefView>): FeedItem.Question {
        val request = RequestCard(
            RequestView(id = "q1", agentId = "a1", projectId = "p1", kind = "confirm", question = "Does this look right?", refs = refs),
            agentName = "forge", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        val feed = EngineFeed(null, null, StreamStatus.Live, true, false, false, listOf(request), emptyList(), emptyList(), 0, false, emptyList())
        return mapper.map(feed, listOf(mega, web), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
    }

    @Test
    fun aQuestionShowsTheReplyItPointsAtWithItsVideo() {
        val video = MediaView(
            id = "m1", messageId = "r9", kind = "video", mime = "video/mp4", status = "ready", width = 640, height = 360, durationMs = 42_000,
            variants = listOf("original", "poster", "video"),
        )
        val q = asked(listOf(
            CardRefView(CardKinds.Message, "r9", projectId = "p1", author = "agent:a1", authorName = "forge", text = "Here it is running", media = video, mediaCount = 2, updateId = "u1"),
            CardRefView(CardKinds.Update, "u7", projectId = "p2", author = "agent:a2", authorName = "scout", title = "Login page", text = "Built"),
            CardRefView(CardKinds.Message, "gone", projectId = "p1", deleted = true),
            CardRefView(CardKinds.Message, "o3", projectId = "p1", author = "human", text = "a post", mediaCount = 1, media = MediaView(id = "f1", kind = "file", status = "ready", filename = "notes.txt", variants = listOf("original"))),
        ))
        val (reply, update, removed, post) = q.refs
        assertEquals("reply", reply.what)
        assertEquals("forge", reply.who)
        assertNull(reply.where, "the same project as the question: not repeated")
        assertEquals("Here it is running", reply.text)
        assertEquals("https://dash.test/media/m1/video", reply.media?.video?.url)
        assertEquals("0:42", reply.media?.video?.duration)
        assertEquals("+1 more", reply.mediaNote)
        assertEquals("mega", reply.projectSlug)

        assertEquals("update", update.what)
        assertEquals("Web", update.where)
        assertEquals("Login page", update.title)

        assertTrue(removed.removed)
        assertEquals("A message that was removed", removed.spoken)

        assertEquals("post", post.what)
        assertEquals("You", post.who)
        assertNull(post.media, "a file has no tile")
        assertEquals("notes.txt", post.mediaNote)
    }

    // ---- the screen ------------------------------------------------------------------------------

    private val fix = FakeFeed.state.sections[1].items.first { it.id == "u1" } as FeedItem.Update

    /** scout's reply on the fix, with the video the question asks about. */
    private val reply = fix.replies.first { it.id == "r3" }

    private val clip = Attachment(
        "build.mp4", "The counts after 30 minutes", aspect = 16f / 9f, scene = Scene.Dawn,
        video = VideoSource("https://example.com/media/m1/video", "0:42"),
    )

    private val pointer = LinkedCard(
        kind = CardKinds.Message, id = reply.id, who = "scout", what = "reply",
        text = "Confirmed from my side: counts match after 30 minutes of real traffic across 3 sessions.",
        media = clip, projectSlug = "agent-dashboard",
    )

    private val removed = LinkedCard(kind = CardKinds.Message, id = "gone", removed = true, what = "message")

    /** The morning's feed, with the first question pointing at scout's reply and at one removed since. */
    private fun state(): FeedState {
        val base = FakeFeed.state
        val waiting = base.sections[0]
        val question = (waiting.items[0] as FeedItem.Question).copy(
            prompt = "Ship the per-connection fix?",
            body = "scout's run is below.",
            refs = listOf(pointer, removed),
        )
        return base.copy(openThread = null, sections = listOf(waiting.copy(items = listOf(question))) + base.sections.drop(1))
    }

    private val lit = SemanticsMatcher.expectValue(Highlighted, true)

    private fun ComposeUiTest.openReply() {
        val chip = hasContentDescription("Open scout's reply: ${pointer.text}")
        // The feed's own list, the first that scrolls up and down: brought into view, then tapped.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(chip)
        waitForIdle()
        // On its heading: the middle of it is the video, which plays rather than opens.
        onNode(hasText("scout's reply"), useUnmergedTree = true).performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    @Test
    fun onAPhoneATapOpensTheThreadAtTheReply() = runDesktopComposeUiTest {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state()) } } }
        onNode(hasText("scout's reply", substring = true)).assertIsDisplayed()
        onNode(hasContentDescription(pointer.media!!.altText, substring = true) or hasContentDescription("Play", substring = true)).assertExists()
        openReply()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread")).assertExists()
        val ringed = onAllNodes(lit and hasAnyDescendant(hasText("Confirmed from my side", substring = true))).fetchSemanticsNodes()
        assertTrue(ringed.isNotEmpty(), "scout's reply is ringed in the thread")
        onAllNodes(lit and hasAnyDescendant(hasText("Confirmed from my side", substring = true))).onFirst().assertIsDisplayed()
    }

    @Test
    fun onDesktopATapOpensTheCardsThreadAtTheReply() = runDesktopComposeUiTest(1440, 900) {
        setContent { AgentTheme(dark = true) { FeedScreen(state()) } }
        openReply()
        onAllNodes(lit and hasAnyDescendant(hasText("Confirmed from my side", substring = true))).onFirst().assertIsDisplayed()
    }

    @Test
    fun aRemovedCardSaysSoAndOpensNothing() = runDesktopComposeUiTest {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state()) } } }
        val node = onNode(hasContentDescription("A message that was removed")).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnClick), "nothing to open")
        onNode(hasText("This was removed.")).assertExists()
    }

    // ---- screenshots -----------------------------------------------------------------------------

    private fun ComposeUiTest.save(name: String) {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun screenshots() {
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            runDesktopComposeUiTest(1170, 2532) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(3f)) {
                        AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state(), SafeArea(top = 54.dp, bottom = 34.dp)) } }
                    }
                }
                save("phone-$mode-card-refs")
                openReply()
                save("phone-$mode-card-refs-thread")
            }
            runDesktopComposeUiTest(2880, 1800) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f)) {
                        AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state()) } }
                    }
                }
                save("desktop-$mode-card-refs")
                openReply()
                save("desktop-$mode-card-refs-thread")
            }
        }
    }
}
