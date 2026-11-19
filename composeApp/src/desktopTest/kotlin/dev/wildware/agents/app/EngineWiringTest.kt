package dev.wildware.agents.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
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

    /** Where a post from "All projects" goes, kept on the device. */
    private val postTarget = dev.wildware.agents.MemoryTextStore()

    private fun ComposeUiTest.app(sessions: MemorySessionStore = MemorySessionStore(signedIn)) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = sessions, filePicker = { it(listOf(picked)) }, media = media, postTarget = postTarget)
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

    /** "Connect to a host", step 1: the address, checked against the server, then its password box. */
    private fun ComposeUiTest.connectTo(address: String) {
        onNodeWithContentDescription("Host address").performTextInput(address)
        onNode(hasText("Continue") and hasClickAction()).performClick()
        waitForLabel("Owner password")
    }

    @Test
    fun aWrongPasswordSaysSoUnderThePasswordBox() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app(MemorySessionStore())
        connectTo("https://dashboard.test")
        onNodeWithContentDescription("Owner password").performTextInput("wrong")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitFor("That password isn't right. Try again.")
    }

    @Test
    fun theAddressStartsEmptyAndIsAskedFor() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app(MemorySessionStore())
        onNode(hasText("Continue") and hasClickAction()).performClick()
        waitFor("Type the host's address.")
        assertTrue(server.authorizations.isEmpty())
    }

    @Test
    fun signingInSavesTheKeyAndOpensTheLiveFeed() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val sessions = MemorySessionStore()
        app(sessions)
        connectTo("https://dashboard.test")
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

    private fun joining() = dev.wildware.agents.PairingView(
        id = "pr1", name = "laptop-personal", host = "laptop", code = "K7Q-3MD",
        createdAt = System.currentTimeMillis(), expiresAt = System.currentTimeMillis() + 600_000,
    )

    @Test
    fun anAgentAskingToJoinIsAllowedUnderTheChosenName() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.pairings = listOf(joining())
        app()
        waitFor("laptop-personal wants to join")
        onNode(hasContentDescription("Agent name") and hasSetTextAction()).performTextReplacement("laptop")
        onNode(hasText("Allow") and hasClickAction()).performClick()
        val body = waitForWrite("POST", "/api/pairings/pr1/approve")
        assertEquals("""{"name":"laptop"}""", body)
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("laptop-personal wants to join")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aTakenNameKeepsThePromptOpenAndSaysWhy() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.pairings = listOf(joining().copy(name = "forge"))
        server.refuseApprove = "name_taken"
        app()
        waitFor("forge wants to join")
        onNode(hasText("Allow") and hasClickAction()).performClick()
        waitFor("Another agent is already called that. Choose another name.")
        onNode(hasText("Deny") and hasClickAction()).performClick()
        waitForWrite("POST", "/api/pairings/pr1/deny")
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
        // Its own click: scrolled to the edge, it can sit under the composer that floats over the feed's end.
        waitForNode(hasContentDescription("Download build.log, 1.5 KB")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Saved to Downloads")
        assertEquals(listOf("https://dash.test/media/doc/original"), media.saved)
    }

    @Test
    fun aVideoPlaysInItsCardWhenTapped() = runDesktopComposeUiTest(width = 1440, height = 900) {
        withMedia()
        app()
        waitForNode(hasContentDescription("Play video 1 of 1, 0:42")).performScrollTo().performClick()
        // In the app, in its card: nothing downloaded, nothing handed to another program.
        waitForNode(hasContentDescription("Full screen"))
        assertTrue(media.opened.isEmpty(), "got ${media.opened}")
    }

    @Test
    fun onDesktopASoundPlaysInTheApp() = runDesktopComposeUiTest(width = 1440, height = 900) {
        withMedia()
        app()
        waitForNode(hasContentDescription("Play sound 1 of 1, 0:09")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(media.opened.isEmpty(), "nothing handed to the system's player: ${media.opened}")
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
    fun aVideoOnAPostShowsAsTheServerSendsIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.messageMedia = mapOf(
            "m-post" to listOf(
                dev.wildware.agents.MediaView(id = "v1", kind = "video", status = "ready", variants = listOf("original", "poster", "video"), durationMs = 12_000, width = 1280, height = 720, mime = "video/mp4"),
                dev.wildware.agents.MediaView(id = "f1", kind = "file", status = "ready", variants = listOf("original"), filename = "crash.log", bytes = 1536, mime = "text/plain"),
            ),
        )
        app()
        waitFor("Why is the Mac build slow?")
        waitForNode(hasContentDescription("Play video 1 of 1, 0:12"))
        waitForNode(hasContentDescription("Download crash.log", substring = true))
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
        // The thread's Send sits on the reply box's own row; the feed's composer floats nearby.
        val field = onNodeWithContentDescription("Reply").fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        val thread = sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == field.top }
        sends[thread].performSemanticsAction(SemanticsActions.OnClick)
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("\"update\":\"u1\"" in body && "\"answers\":\"m1\"" in body && "Thanks, shipping it" in body, body)
    }

    @Test
    fun replyOnACommentMentionsItsAgentAndAnswersIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        waitFor("Confirmed on my side.")
        // The comment sits under the feed's floating box here, out of the pointer's reach, so Reply is
        // pressed as a screen reader does; ReplyToCommentTest presses the hover button.
        runCustomAction("Reply", on = hasAnyDescendant(hasText("Confirmed on my side.")))
        waitFor("Replying to scout")
        val box = onNode(hasContentDescription("Reply") and hasSetTextAction())
        assertEquals("@scout ", box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        box.performTextInput("thanks, shipping it")
        val field = box.fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        sends[sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == field.top }].performSemanticsAction(SemanticsActions.OnClick)
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("\"update\":\"u1\"" in body && "\"answers\":\"m1\"" in body && "@scout thanks, shipping it" in body, body)
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
    fun aNewProjectIsMadeOnTheServerAndArchivingSetsItsStatus() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Mega")
        waitForNode(hasText("New channel") and hasClickAction()).performScrollTo().performClick()
        waitForNode(hasContentDescription("Channel name") and hasSetTextAction()).performTextInput("harbor-app")
        waitForNode(hasText("Create channel")).performClick()
        assertTrue("\"name\":\"harbor-app\"" in waitForWrite("POST", "/api/projects"))

        waitForNode(hasContentDescription("Mega,", substring = true)).performClick()
        waitForNode(hasContentDescription("Channel settings")).performClick()
        waitForNode(hasText("Archive channel")).performClick()
        assertEquals("""{"status":"archived"}""", waitForWrite("PATCH", "/api/projects/mega"))
    }

    @Test
    fun postingFromAllProjectsGoesToThePickedProject() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Fixed the unread counts")
        waitForNode(hasContentDescription("Posting to", substring = true)).performClick()
        waitForNode(hasContentDescription("Post to Other")).performClick()
        waitForLabel("Message")
        onNodeWithContentDescription("Message").performTextInput("Hello from everything")
        onNodeWithContentDescription("Send").performClick()
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("\"project\":\"other\"" in body && "Hello from everything" in body, body)
    }

    @Test
    fun allProjectsPostsToTheProjectLastOpened() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Mega")
        waitForNode(hasContentDescription("Mega,", substring = true)).performClick()
        waitForLabel("Message")
        waitForNode(hasContentDescription("All channels", substring = true)).performClick()
        waitForNode(hasContentDescription("Posting to Mega. Choose a channel"))
    }

    @Test
    fun theLastProjectPostedToIsRememberedOnTheDevice() = runDesktopComposeUiTest(width = 1440, height = 900) {
        postTarget.write("other")
        app()
        waitFor("Fixed the unread counts")
        waitForNode(hasContentDescription("Posting to Other. Choose a channel")).performClick()
        waitForNode(hasContentDescription("Post to Mega")).performClick()
        onNodeWithContentDescription("Message").performTextInput("Over here")
        onNodeWithContentDescription("Send").performClick()
        assertTrue("\"project\":\"mega\"" in waitForWrite("POST", "/api/messages"))
        waitUntil(timeoutMillis = 5_000) { postTarget.read() == "mega" }
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
        waitForLabel("Host address")
        assertNull(sessions.load())
        assertNotNull(onNodeWithContentDescription("Host address").fetchSemanticsNode())
    }

    @Test
    fun talkingOverAQuestionPostsIntoThatQuestion() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitFor("Ship the new sounds?")
        onNodeWithText("Discuss").performScrollTo().performClick()
        val box = hasSetTextAction() and hasContentDescription("Reply")
        waitForNode(box)
        onNode(box).performTextInput("Which sounds exactly?")
        onNode(box).performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) } }
        waitUntil(timeoutMillis = 5_000) { server.wrote("POST", "/api/messages") != null }
        val body = server.wrote("POST", "/api/messages")!!
        assertTrue("\"request\":\"r1\"" in body && "Which sounds exactly?" in body, body)
    }
}
