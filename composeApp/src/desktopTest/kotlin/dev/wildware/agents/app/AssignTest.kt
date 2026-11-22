package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Assigning work in the app (server migration 072): the picker, avatars on board cards, the board's filters, and A. */
@OptIn(ExperimentalTestApi::class)
class AssignTest {
    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun card(id: String, title: String, assignees: String = "") =
        """{"card":{"kind":"message","id":"$id"},"projectId":"p1","channel":{"slug":"mega","name":"Mega"},"status":"todo",""" +
            """"statusAt":${FakeServer.now() - 3_600_000},"createdAt":1,"movedAt":1,"title":"$title",""" +
            """"on":[{"kind":"agent","id":"a1","name":"forge"}],"replies":0,"waiting":0,"removed":false,"canMove":true,"assignees":[$assignees]}"""

    private fun board(vararg cards: String) =
        """{"columns":[{"status":"todo","cards":[${cards.joinToString(",")}],"more":false},""" +
            """{"status":"in_progress","cards":[],"more":false},{"status":"blocked","cards":[],"more":false},{"status":"done","cards":[],"more":false}],"autoRemoveDays":7}"""

    private val forge = """{"principal":"agent:a1","kind":"agent","id":"a1","name":"forge"}"""

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

    private fun ComposeUiTest.boardCalled(part: String) =
        waitUntil(timeoutMillis = 10_000) { synchronized(server.calls) { server.calls.toList() }.any { it.startsWith("/api/board") && part in it } }

    private fun ComposeUiTest.openBoard() {
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Board") and hasClickAction()).performClick()
    }

    @Test
    fun assigningAnAgentFromABoardCardSavesItAndSaysWhoCantRead() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board(card("m-post", "Why is the Mac build slow?"))
        app()
        openBoard()
        waitForNode(hasContentDescription("Card options")).performClick()
        waitForNode(hasText("Assign…") and hasClickAction()).performClick()
        // Everyone who could be: lumen can't read the channel and says so.
        waitForNode(hasText("Can't read this channel"))
        waitForNode(hasTestTag("assign-forge")).performClick()
        waitForNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals("""{"assignees":["agent:a1"],"mode":"set"}""", waitForWrite("POST", "/api/cards/message/m-post/assignees"))
    }

    @Test
    fun aBoardCardShowsItsAssigneesAsAvatars() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board(card("m-post", "Why is the Mac build slow?", forge))
        app()
        openBoard()
        // The card says so to a screen reader, and draws the avatars inside it.
        waitForNode(hasTestTag("board-card-m-post") and hasContentDescription("assigned to forge", substring = true))
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag("assignees") and hasContentDescription("Assigned to forge"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The chips gain one for each assignee seen.
        waitForNode(hasText("forge") and hasClickAction())
    }

    @Test
    fun theFilterChipsAskTheBoardForMyCardsAndNobodys() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board(card("m-post", "Why is the Mac build slow?", forge))
        app()
        openBoard()
        waitForNode(hasText("Assigned to me") and hasClickAction()).performClick()
        boardCalled("assigned=me")
        waitForNode(hasText("Unassigned") and hasClickAction()).performClick()
        boardCalled("assigned=none")
        waitForNode(hasText("forge") and hasClickAction()).performClick()
        boardCalled("assigned=agent%3Aa1")
    }

    @Test
    fun pressingAOnAFocusedBoardCardOpensThePicker() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board(card("m-post", "Why is the Mac build slow?"))
        app()
        openBoard()
        waitForNode(hasTestTag("board-card-m-post"))
        val pane = waitForNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Board"))
        pane.performKeyInput { pressKey(Key.DirectionDown) }
        pane.performKeyInput { pressKey(Key.A) }
        waitForNode(hasText("Can't read this channel"))
        waitUntil(timeoutMillis = 10_000) { server.calls.any { it == "/api/cards/message/m-post/assignees" } }
    }
}
