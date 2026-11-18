package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FeedState as EngineFeed
import dev.wildware.agents.MediaRetention
import dev.wildware.agents.MediaView
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ProjectView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.UpdateView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.engine.FeedMapper
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Files
import dev.wildware.agents.app.feed.MediaGrid
import dev.wildware.agents.app.feed.RetentionSettings
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.Sounds
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media retention in the app: removed media's placeholder, and the "Keep media" setting. */
@OptIn(ExperimentalTestApi::class)
class RetentionTest {
    // --- Mapping -------------------------------------------------------------------------------

    private val mapper = FeedMapper("https://dash.test/", now = { 1_795_003_200_000L }, zone = TimeZone.UTC)
    private val mega = ProjectSummary(ProjectView(id = "p1", slug = "mega", name = "Mega"), waiting = 0, unread = 0)
    private val removed = MediaView.Removed("2026-11-18T03:00:00Z", 30)

    private fun media(id: String, kind: String, gone: Boolean, variants: List<String> = listOf("original")) = MediaView(
        id = id, kind = kind, status = "ready", filename = "$id.bin", width = 1600, height = 900, mime = "application/octet-stream",
        variants = if (gone) emptyList() else variants, removed = removed.takeIf { gone },
    )

    private fun feed(cards: List<UpdateCard>, waiting: List<RequestCard> = emptyList()) =
        EngineFeed(null, null, StreamStatus.Live, true, false, false, waiting, emptyList(), cards, 0, false, emptyList())

    @Test
    fun removedMediaKeepsItsPlaceAndHasNoAddressToLoad() {
        val card = UpdateCard(
            update = UpdateView(
                id = "u1", seq = 1, projectId = "p1", agentId = "a1", title = "Screens", body = "", createdAt = 1,
                media = listOf(
                    media("img", "image", gone = true),
                    media("pic", "image", gone = false, variants = listOf("original", "thumb-1600")),
                    media("vid", "video", gone = true),
                    media("snd", "audio", gone = true),
                    media("doc", "file", gone = true),
                ),
            ),
            agentName = "forge", projectName = "Mega", isNew = false, activityAt = 1,
            replies = emptyList(), unread = 0, tags = emptyList(), fields = emptyList(), apps = emptyList(),
        )
        val item = mapper.map(feed(listOf(card)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Update

        assertEquals(listOf(30, null, 30), item.attachments.map { it.removedAfterDays })
        assertEquals(listOf(null, "https://dash.test/media/pic/thumb-1600", null), item.attachments.map { it.url })
        assertNull(item.attachments[2].video, "a removed video has nothing to play")
        assertEquals("Media removed after 30 days", item.attachments[0].altText)
        assertEquals(16f / 9f, item.attachments[0].aspect, "the slot its picture took")
        assertEquals(listOf(Sound("snd", "", null, "application/octet-stream", removedAfterDays = 30)), item.sounds)
        assertEquals("", item.files.single().url)
        assertEquals(30, item.files.single().removedAfterDays)
    }

    @Test
    fun aRemovedOptionPictureShowsThePlaceholderOnItsOption() {
        val request = RequestCard(
            RequestView(
                id = "r1", agentId = "a1", kind = "choice", question = "Which?", options = listOf("Dusk", "Dawn"),
                config = RequestConfig(optionMedia = mapOf("Dusk" to "md1", "Dawn" to "md2")),
                media = listOf(media("md1", "image", gone = true), media("md2", "image", gone = false)),
            ),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
        assertEquals(listOf(30, null), q.choices.map { it.image?.removedAfterDays })
        assertEquals(listOf(null, "https://dash.test/media/md2/original"), q.choices.map { it.image?.url })
    }

    // --- The placeholder ----------------------------------------------------------------------

    @Test
    fun thePlaceholderSaysWhatHappenedAndOpensNothing() = runDesktopComposeUiTest(width = 600, height = 900) {
        val gone = Attachment("chart.png", "Media removed after 30 days", aspect = 16f / 9f, removedAfterDays = 30)
        val kept = Attachment("other.png", "A chart", aspect = 16f / 9f)
        var opened = -1
        setContent {
            AgentTheme(dark = false) {
                Column {
                    MediaGrid(listOf(gone, kept), onOpen = { opened = it })
                    Sounds(listOf(Sound("s1", "", 9_000, "audio/mpeg", removedAfterDays = 7)))
                    Files(listOf(FileAttachment("f1", "", "build.log", "1.5 KB", "text/plain", removedAfterDays = 1)))
                }
            }
        }
        onAllNodes(hasContentDescription("Media removed after 30 days"), useUnmergedTree = true).onFirst().assertExists()
        onNodeWithContentDescription("Media removed after 7 days").assertExists()
        onNodeWithContentDescription("Media removed after 1 day").assertExists()
        // Nothing to play or download where they were.
        assertTrue(onAllNodes(hasContentDescription("Play sound", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasContentDescription("Download build.log", substring = true)).fetchSemanticsNodes().isEmpty())
        // The tile beside it still opens.
        onNode(hasClickAction() and hasContentDescription("Picture 2 of 2", substring = true)).performClick()
        assertEquals(1, opened)
        assertTrue(onAllNodes(hasClickAction() and hasContentDescription("Media removed", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    // --- The setting --------------------------------------------------------------------------

    private val owner = RetentionSettings(MediaRetention(), loaded = true, allowed = true)

    private fun ComposeUiTest.sheet(retention: RetentionSettings = owner, preview: ScreenPreview = ScreenPreview(retentionOpen = true)): MutableList<FeedEvent> {
        val events = mutableListOf<FeedEvent>()
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.state.copy(retention = retention), preview = preview, onEvent = { events += it }) }
            }
        }
        return events
    }

    private fun ComposeUiTest.save(): SemanticsNodeInteraction = onNode(hasText("Save"))

    @Test
    fun turningItOnAsksFirstThenSaves() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val events = sheet()
        save().assertIsNotEnabled()
        onNodeWithText("7 days").performClick()
        save().performClick()
        onNodeWithText("Media older than 7 days will be deleted for good, each night. Pinned cards and open requests keep theirs.").assertExists()
        assertTrue(events.none { it is FeedEvent.SetMediaRetention }, "nothing is sent before the owner says so")
        onNodeWithText("Delete older media").performClick()
        assertEquals(FeedEvent.SetMediaRetention(MediaRetention(7, MediaRetention.AllKinds)), events.last())
    }

    @Test
    fun cancellingTheConfirmSendsNothing() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val events = sheet()
        onNodeWithText("30 days").performClick()
        save().performClick()
        onNodeWithText("Cancel").performClick()
        assertTrue(onAllNodes(hasText("deleted for good", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(events.none { it is FeedEvent.SetMediaRetention })
    }

    @Test
    fun keepingMediaLongerSavesWithoutAsking() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val events = sheet(owner.copy(setting = MediaRetention(7)))
        onNodeWithText("90 days").performClick()
        save().performClick()
        assertEquals(FeedEvent.SetMediaRetention(MediaRetention(90)), events.last())
    }

    @Test
    fun customDaysOutsideOneTo3650CantBeSaved() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val events = sheet()
        onNodeWithText("Custom…").performClick()
        val box = onNodeWithContentDescription("Number of days, from 1 to 3650")
        box.performTextInput("0")
        waitUntil { onAllNodes(hasText("Choose a number of days from 1 to 3650.")).fetchSemanticsNodes().isNotEmpty() }
        save().assertIsNotEnabled()
        box.performTextInput("45")
        waitUntil { onAllNodes(hasText("Choose a number of days from 1 to 3650.")).fetchSemanticsNodes().isEmpty() }
        save().assertIsEnabled().performClick()
        onNodeWithText("Delete older media").performClick()
        assertEquals(FeedEvent.SetMediaRetention(MediaRetention(45)), events.last())
    }

    @Test
    fun theLastKindTickedStaysTicked() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet(owner.copy(setting = MediaRetention(30, kinds = listOf("image", "file"))))
        onNodeWithText("Images").assertIsEnabled().performClick()
        onNodeWithText("Files").assertIsNotEnabled()
        onNodeWithText("Videos").assertIsEnabled()
    }

    @Test
    fun theDashboardsRefusalIsShownAsItSaidIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet(owner.copy(problem = "days must be a whole number of days from 1 to 3650, or null for never"))
        onNodeWithText("days must be a whole number of days from 1 to 3650, or null for never").assertExists()
    }

    @Test
    fun theAccountSheetLeadsToKeepMedia() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet(owner.copy(setting = MediaRetention(30)), ScreenPreview(alertsOpen = true))
        onNode(hasText("Keep media") and hasText("30 days") and hasClickAction()).performClick()
        // The account sheet gives way to the setting, open on what is saved.
        onNodeWithText("KEEP MEDIA FOR").assertExists()
        onNode(hasText("30 days") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        assertTrue(onAllNodes(hasText("TELL ME ABOUT")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun whoeverMayNotChangeItNeverSeesIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet(RetentionSettings(), ScreenPreview(alertsOpen = true))
        onNodeWithText("TELL ME ABOUT").assertExists()
        assertTrue(onAllNodes(hasText("Keep media")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("THIS DASHBOARD")).fetchSemanticsNodes().isEmpty())
    }

    // --- Through the real engine --------------------------------------------------------------

    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    @Test
    fun theOwnerTurnsItOnFromTheAccountSheetAndTheDashboardKeepsIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("Keep media") and hasClickAction()).performClick()
        waitForNode(hasText("14 days")).performClick()
        waitForNode(hasText("Videos")).performClick()
        waitForNode(hasText("Save") and hasClickAction()).performClick()
        waitForNode(hasText("Delete older media")).performClick()
        assertEquals("""{"days":14,"kinds":["image","audio","file"]}""", waitForWrite("PUT", "/api/settings/media-retention"))
        waitForNode(hasText("Saved."))
    }

    @Test
    fun aRefusedSaveShowsTheDashboardsWords() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.refuseRetention = "choose at least one kind of media"
        app()
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("Keep media") and hasClickAction()).performClick()
        waitForNode(hasText("90 days")).performClick()
        waitForNode(hasText("Save") and hasClickAction()).performClick()
        waitForNode(hasText("Delete older media")).performClick()
        waitForNode(hasText("choose at least one kind of media"))
    }

    @Test
    fun aDashboardThatRefusesTheReadHidesTheSetting() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.mediaRetention = null
        app()
        waitUntil(timeoutMillis = 10_000) { server.count("/api/settings/media-retention") > 0 }
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("TELL ME ABOUT"))
        waitForIdle()
        assertTrue(onAllNodes(hasText("Keep media")).fetchSemanticsNodes().isEmpty())
    }
}
