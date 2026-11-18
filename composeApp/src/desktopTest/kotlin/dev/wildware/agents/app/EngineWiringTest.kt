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
import androidx.compose.ui.test.SemanticsMatcher
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

    private fun ComposeUiTest.app(sessions: MemorySessionStore = MemorySessionStore(signedIn)) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = sessions, filePicker = { it(listOf(picked)) })
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
        val node = onAllNodes(target).fetchSemanticsNodes().first()
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

    @Test
    fun aReplySaysWhichLineItAnswers() = runDesktopComposeUiTest(width = 1440, height = 900) {
        // Replies land after their card here on purpose: that order once made this test flaky.
        server.messagesDelayMs = 400
        app()
        waitFor("Fixed the unread counts")
        // The reply can arrive after the card, and then also counts as new (", 1 new"), so match the start.
        waitForNode(hasContentDescription("Open thread, 1 reply", substring = true)).performClick()
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
        runCustomAction("Delete")
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
