package dev.wildware.agents.app

import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.performTextReplacement
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlin.test.AfterTest
import kotlin.test.Test
import dev.wildware.agents.NotificationView
import dev.wildware.agents.MediaView
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.MediaHandler
import dev.wildware.agents.app.feed.Playable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real engine from :shared, talking to [FakeServer] through a mock network, drawn by the real
 * screens. Each test does what the owner would do and checks what reached the server.
 */
@OptIn(ExperimentalTestApi::class)
class EngineWiringTest {
    private val server = FakeServer()
    private val signedIn = SavedSession("https://dash.test", "key-1", "Test device")

    @AfterTest
    fun closeStreams() = server.close()

    private val picked = PendingFile("shot.png", "image/png", byteArrayOf(1, 2, 3))

    /** Media handed to the system, recorded instead of opening real players. */
    private val media = object : MediaHandler {
        val opened = mutableListOf<String>()
        val saved = mutableListOf<String>()

        override suspend fun open(item: Playable, auth: MediaAuth?): String {
            opened += item.url
            return "Opened in your player"
        }

        override suspend fun save(file: FileAttachment, auth: MediaAuth?): String {
            saved += file.url
            return "Saved to Downloads"
        }
    }

    private fun ComposeUiTest.app(sessions: MemorySessionStore = MemorySessionStore(signedIn)) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = sessions, filePicker = { it(listOf(picked)) }, media = media)
        setContent {
            Box(Modifier.size(1440.dp, 900.dp)) {
                AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs())
            }
        }
    }

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    } catch (timeout: Throwable) {
        // Say what was on screen instead, so a failure is easy to read.
        throw AssertionError("never saw \"$text\"; the screen had:\n" + onRoot().printToString(), timeout)
    }

    private fun ComposeUiTest.waitForLabel(label: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Waits for [matcher] to match, then hands it back. The feed fills in over several server
     * replies (cards, then their comments, then read marks), so a node can show before it is in
     * its final shape; tests wait for the shape they need instead of the first sign of the card.
     */
    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher, unmerged: Boolean = false): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher, unmerged).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher, unmerged).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    /** Runs a screen reader action, on the node [on] narrows to when more than one offers it. */
    private fun ComposeUiTest.runCustomAction(label: String, on: SemanticsMatcher? = null) {
        val matcher = SemanticsMatcher("has action '$label'") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
        }
        val target = if (on != null) matcher and on else matcher
        waitForNode(target)
        // With [on], the innermost match: a reply's own node, not the card it sits in.
        val nodes = onAllNodes(target).fetchSemanticsNodes()
        val node = if (on != null) nodes.last() else nodes.first()
        runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
    }

    @Test
    fun aWrongPasswordSaysSoUnderThePasswordBox() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app(MemorySessionStore())
        onNodeWithContentDescription("Dashboard address").performTextInput("https://dashboard.test")
        onNodeWithContentDescription("Owner password").performTextInput("wrong")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitFor("That password isn't right. Try again.")
    }

    @Test
    fun theAddressStartsEmptyAndIsAskedFor() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app(MemorySessionStore())
        onNodeWithContentDescription("Owner password").performTextInput("right")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitFor("Type your dashboard's address.")
        assertTrue(server.authorizations.isEmpty())
    }

    @Test
    fun signingInSavesTheKeyAndOpensTheLiveFeed() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val sessions = MemorySessionStore()
        app(sessions)
        onNodeWithContentDescription("Dashboard address").performTextInput("https://dashboard.test")
        onNodeWithContentDescription("Owner password").performTextInput("right")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitFor("Fixed the unread counts")
        assertEquals("key-1", sessions.load()?.token)
        assertTrue(server.authorizations.isNotEmpty() && server.authorizations.all { it == "Bearer key-1" })
    }

    @Test
    fun aSavedKeyOpensTheFeedWithItsLiveState() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        waitFor("Ship the new sounds?")
        waitForLabel("Connection: Live")
    }

    @Test
    fun answeringAQuestionSendsTheAnswer() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Ship the new sounds?")
        waitForNode(hasText("Ship it"), unmerged = true).performClick()
        val body = waitForWrite("POST", "/api/requests/r1/answer")
        assertTrue("Ship it" in body, body)
    }

    private fun formRequest() = RequestView(
        id = "r9", seq = 9, agentId = "a1", projectId = "p1", kind = "form",
        question = "Send this commit message?", options = listOf("Commit", "Discard"),
        config = RequestConfig(label = "Commit message", defaultValue = "Fix the counts", min = 3),
        expiresAt = System.currentTimeMillis() + 3_600_000,
    )

    @Test
    fun aFormSendsTheChosenActionWithTheEditedText() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.requests = listOf(formRequest())
        app()
        waitFor("Send this commit message?")
        // The field is named, and starts with the agent's draft.
        waitForNode(hasContentDescription("Commit message") and hasText("Fix the counts"))
        onNodeWithContentDescription("Commit message").performTextReplacement("  Fix the unread counts  ")
        waitForNode(hasText("Commit") and hasClickAction()).performClick()
        val body = waitForWrite("POST", "/api/requests/r9/answer")
        assertTrue("\"action\":\"Commit\"" in body && "\"text\":\"Fix the unread counts\"" in body, body)
    }

    @Test
    fun aFormWontSendTextTheAgentSaidIsTooShort() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.requests = listOf(formRequest())
        app()
        waitForNode(hasContentDescription("Commit message") and hasText("Fix the counts"))
        onNodeWithContentDescription("Commit message").performTextReplacement("ok")
        waitFor("At least 3 characters (2 so far).")
        waitForNode(hasText("Commit") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        assertEquals(null, server.wrote("POST", "/api/requests/r9/answer"))
    }

    private fun notice(id: String, title: String, seen: Boolean = false, focus: String = "u1") = NotificationView(
        id = id, seq = 9, kind = "update", projectId = "p1", projectSlug = "mega", projectName = "Mega", updateId = focus,
        title = title, body = "Counts are per connection now.", createdAt = System.currentTimeMillis() - 60_000,
        seenAt = if (seen) System.currentTimeMillis() else null, path = "/projects/mega?focus=$focus",
    )

    @Test
    fun theBellCountsWhatIsNewAndARowGoesToItsCard() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.notifications = listOf(notice("n2", "scout replied"), notice("n1", "forge posted an update", seen = true))
        app()
        waitForNode(hasContentDescription("Notifications, 1 new")).performClick()
        waitFor("scout replied")
        waitFor("forge posted an update")
        waitForNode(hasText("scout replied")).performClick()
        val body = waitForWrite("POST", "/api/notifications/seen")
        assertEquals("""{"ids":["n2"]}""", body)
        // The panel closes on the card, whose thread sits under it on a desktop.
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("forge posted an update")).fetchSemanticsNodes().isEmpty() }
        waitFor("Fixed the unread counts")
        waitFor("Confirmed on my side.")
    }

    @Test
    fun markAllReadClearsTheBell() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.notifications = listOf(notice("n2", "scout replied"), notice("n1", "forge posted an update"))
        app()
        waitForNode(hasContentDescription("Notifications, 2 new")).performClick()
        val inPanel = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Notifications"))
        waitForNode(hasText("Mark all read") and hasClickAction() and inPanel).performClick()
        assertEquals("{}", waitForWrite("POST", "/api/notifications/seen"))
    }

    @Test
    fun anEmptyBellSaysWhatWillLandThere() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        waitForNode(hasContentDescription("Notifications")).performClick()
        waitFor("Nothing yet", substring = true)
    }

    private val statusPicker = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Work state")

    @Test
    fun markingAnUpdateAsWorkSendsItsState() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        runCustomAction("Mark as work…", on = hasAnyDescendant(hasText("Fixed the unread counts")))
        waitForNode(hasText("In progress") and hasAnyAncestor(statusPicker)).performClick()
        assertEquals("""{"status":"in_progress"}""", waitForWrite("POST", "/api/cards/update/u1/status"))
    }

    @Test
    fun aPostShowsItsStateAndCanStopBeingWork() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.messages = server.messages.map { if (it.id == "m-post") it.copy(status = "blocked") else it }
        app()
        waitForNode(hasContentDescription("status Blocked", substring = true))
        runCustomAction("Work state…", on = hasAnyDescendant(hasText("Why is the Mac build slow?")))
        waitForNode(hasText("Not work") and hasAnyAncestor(statusPicker)).performClick()
        assertEquals("""{"status":null}""", waitForWrite("POST", "/api/cards/message/m-post/status"))
    }

    @Test
    fun theBlockedFilterShowsOnlyBlockedWork() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.messages = server.messages.map { if (it.id == "m-post") it.copy(status = "blocked") else it }
        app()
        waitFor("Fixed the unread counts")
        waitForNode(hasText("Blocked", substring = true) and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Fixed the unread counts")).fetchSemanticsNodes().isEmpty() }
        waitFor("Why is the Mac build slow?")
    }

    private fun withMedia() {
        fun item(id: String, kind: String, variants: List<String>, durationMs: Long? = null, filename: String? = null, bytes: Long = 0) =
            MediaView(id = id, kind = kind, status = "ready", variants = variants, durationMs = durationMs, filename = filename, bytes = bytes, width = 1280, height = 720)
        server.updates = server.updates.map {
            it.copy(media = listOf(
                item("vid", "video", listOf("original", "poster", "video"), durationMs = 42_000),
                item("snd", "audio", listOf("original"), durationMs = 9_000),
                item("doc", "file", listOf("original"), filename = "build.log", bytes = 1536),
            ))
        }
    }

    @Test
    fun aFileIsSavedWhenTapped() = runDesktopComposeUiTest(width = 1440, height = 900) {
        withMedia()
        app()
        waitForNode(hasContentDescription("Download build.log, 1.5 KB")).performScrollTo().performClick()
        waitFor("Saved to Downloads")
        assertEquals(listOf("https://dash.test/media/doc/original"), media.saved)
    }

    @Test
    fun aVideoPlaysItsTranscodeWhenTapped() = runDesktopComposeUiTest(width = 1440, height = 900) {
        withMedia()
        app()
        waitForNode(hasContentDescription("Play video 1 of 1, 0:42")).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { media.opened.isNotEmpty() }
        assertEquals(listOf("https://dash.test/media/vid/video"), media.opened)
    }

    @Test
    fun onDesktopASoundPlaysInTheSystemPlayer() = runDesktopComposeUiTest(width = 1440, height = 900) {
        withMedia()
        app()
        waitForNode(hasContentDescription("Play sound 1 of 1, 0:09")).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { media.opened.isNotEmpty() }
        assertEquals(listOf("https://dash.test/media/snd/original"), media.opened)
    }

    @Test
    fun theAgentsListSaysWhichModelWhereAndWhen() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        // Drawn a line each; said in one go.
        waitForNode(hasContentDescription("forge, online, claude-opus-5-5, on build-box, in /work/garden-game, last seen just now"))
    }

    @Test
    fun theAlertSettingsOpenOnWhatIsSavedAndSaveEachChange() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.alertPrefs = """{"prefs":{"types":["request","message"]}}"""
        app()
        waitFor("Fixed the unread counts")
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        // A desktop raises its own alerts, so it offers the two kinds it has.
        waitForNode(hasText("Questions") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        waitForNode(hasText("Replies to me") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        waitForNode(hasText("Questions")).performClick()
        assertEquals("""{"prefs":{"types":["message"]}}""", waitForWrite("PUT", "/api/devices/current/alert-prefs"))
        waitForNode(hasText("Questions") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun aPostNoAgentHasSaysSo() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Why is the Mac build slow?")
        waitFor("Not delivered to any agent")
    }

    @Test
    fun aReplySaysWhichLineItAnswers() = runDesktopComposeUiTest(width = 1440, height = 900) {
        // Replies land after their card here on purpose: that order once made this test flaky.
        server.messagesDelayMs = 400
        app()
        waitFor("Fixed the unread counts")
        // The thread is under the card; the reply can arrive after the card.
        waitFor("Confirmed on my side.")
        runCustomAction("Reply", on = hasAnyDescendant(hasText("Confirmed on my side.")))
        waitFor("Replying to scout")
        onNodeWithContentDescription("Reply").performTextInput("Thanks, shipping it")
        onNodeWithContentDescription("Send", useUnmergedTree = false).performClick()
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("\"update\":\"u1\"" in body && "\"answers\":\"m1\"" in body && "Thanks, shipping it" in body, body)
    }

    @Test
    fun aDoubleTapReactsOnTheServer() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        onNodeWithText("Fixed the unread counts").performTouchInput { doubleClick() }
        val body = waitForWrite("POST", "/api/cards/update/u1/reactions")
        assertTrue("👍" in body, body)
    }

    @Test
    fun deletingYourOwnPostDeletesItOnTheServer() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Why is the Mac build slow?")
        val ownPost = SemanticsMatcher("the owner's post") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Actions for You's message" } == true
        }
        runCustomAction("Delete", on = ownPost)
        // It asks first; nothing is deleted until the owner says yes.
        waitForNode(hasText("Delete this post?"))
        onNode(hasText("Delete") and hasClickAction()).performClick()
        waitForWrite("DELETE", "/api/messages/m-post")
    }

    @Test
    fun postingGoesToTheProjectOnScreen() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Mega")
        waitForNode(hasContentDescription("Mega,", substring = true)).performClick()
        waitForLabel("Message")
        onNodeWithContentDescription("Message").performTextInput("Can someone look at the build?")
        onNodeWithContentDescription("Send").performClick()
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("\"project\":\"mega\"" in body && "look at the build" in body, body)
    }

    @Test
    fun attachedFilesUploadThroughTheEngineWithThePost() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Mega")
        waitForNode(hasContentDescription("Mega,", substring = true)).performClick()
        waitForLabel("Attach files")
        onNodeWithContentDescription("Attach files").performClick()
        onNodeWithContentDescription("Message").performTextInput("The build log")
        onNodeWithContentDescription("Send").performClick()
        waitForWrite("POST", "/api/media")
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("md-up" in body && "The build log" in body && "\"project\":\"mega\"" in body, body)
    }

    @Test
    fun whatCantSendYetShowsAsWaiting() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.failWrites = true
        app()
        waitFor("Ship the new sounds?")
        waitForNode(hasText("Ship it"), unmerged = true).performClick()
        waitFor("waiting to send", substring = true)
    }

    @Test
    fun refreshReadsEverythingAgain() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        val before = server.count("/api/snapshot")
        runCustomAction("Refresh")
        waitUntil(timeoutMillis = 10_000) { server.count("/api/snapshot") > before }
    }

    @Test
    fun signingOutForgetsTheKey() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val sessions = MemorySessionStore(signedIn)
        app(sessions)
        waitFor("Fixed the unread counts")
        onNodeWithContentDescription("Sign out").performClick()
        waitForLabel("Owner password")
        assertNull(sessions.load())
        assertNotNull(onNodeWithContentDescription("Owner password").fetchSemanticsNode())
    }
}
