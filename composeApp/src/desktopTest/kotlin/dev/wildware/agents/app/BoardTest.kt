package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FeedSnapshot
import dev.wildware.agents.MessagesSnapshot
import dev.wildware.agents.UpdateView
import dev.wildware.agents.UpdatesPage
import dev.wildware.agents.app.session.AppPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.design.AccessibilityPrefs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board in the app, through the real engine and a fake dashboard: columns, moves, removal, scope. */
@OptIn(ExperimentalTestApi::class)
class BoardTest {
    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun card(id: String, kind: String, status: String, title: String, canMove: Boolean = true, removed: Boolean = false) =
        """{"card":{"kind":"$kind","id":"$id"},"projectId":"p1","channel":{"slug":"mega","name":"Mega"},"status":"$status",""" +
            """"statusAt":${FakeServer.now() - 3_600_000},"createdAt":1,"movedAt":1,"title":"$title",""" +
            """"on":[{"kind":"agent","id":"a1","name":"forge"}],"replies":0,"waiting":0,"removed":$removed,"canMove":$canMove}"""

    private fun board(vararg columns: Pair<String, List<String>>) =
        """{"columns":[${listOf("todo", "in_progress", "blocked", "done").joinToString(",") { s ->
            """{"status":"$s","cards":[${columns.toMap()[s].orEmpty().joinToString(",")}],"more":false}"""
        }}],"autoRemoveDays":7}"""

    private fun ComposeUiTest.app(width: Int = 1440, height: Int = 900) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(width.dp, height.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
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

    private fun ComposeUiTest.openBoard() {
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Board") and hasClickAction()).performClick()
    }

    /** Drags [card] by the mouse to the middle of the [status] column. */
    private fun ComposeUiTest.drag(card: String, status: String) {
        val from = waitForNode(hasTestTag("board-card-$card")).fetchSemanticsNode().boundsInRoot
        val to = waitForNode(hasTestTag("board-column-$status")).fetchSemanticsNode().boundsInRoot
        val step = (to.center - from.center) / 10f
        waitForNode(hasTestTag("board-card-$card")).performMouseInput {
            moveTo(Offset(width / 2f, height / 2f))
            press()
            repeat(10) { moveBy(step) }
            release()
        }
    }

    @Test
    fun theColumnsAreTheWorkStatesWithTheirCards() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board("todo" to listOf(card("m-post", "message", "todo", "Why is the Mac build slow?")), "done" to listOf(card("u1", "update", "done", "Fixed the unread counts")))
        app()
        openBoard()
        for (label in listOf("To do", "In progress", "Blocked", "Done")) waitForNode(hasText(label))
        waitForNode(hasTestTag("board-card-m-post"))
        waitForNode(hasTestTag("board-card-u1"))
        waitForNode(hasText("forge"))
        assertTrue(synchronized(server.calls) { server.calls.toList() }.any { it.startsWith("/api/board") }, server.calls.toString())
    }

    @Test
    fun draggingACardToAnotherColumnChangesItsStatus() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board("todo" to listOf(card("m-post", "message", "todo", "Why is the Mac build slow?")))
        app()
        openBoard()
        drag("m-post", "in_progress")
        assertEquals("""{"status":"in_progress"}""", waitForWrite("POST", "/api/cards/message/m-post/status"))
    }

    @Test
    fun aDoneCardCanBeRemovedFromTheBoard() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board("done" to listOf(card("u1", "update", "done", "Fixed the unread counts")))
        app()
        openBoard()
        waitForNode(hasText("Remove from board") and hasClickAction()).performClick()
        assertEquals("""{"removed":true}""", waitForWrite("POST", "/api/cards/update/u1/board"))
    }

    @Test
    fun theBoardFollowsTheSidebarsSelection() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        openBoard()
        waitUntil(timeoutMillis = 10_000) { synchronized(server.calls) { server.calls.toList() }.any { it.startsWith("/api/board") } }
        assertTrue(synchronized(server.calls) { server.calls.toList() }.none { it.startsWith("/api/board") && "channel=" in it }, "All channels reads everything")
        waitForNode(hasText("Mega") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 10_000) { synchronized(server.calls) { server.calls.toList() }.any { it.startsWith("/api/board") && "channel=mega" in it } }
    }

    @Test
    fun aReadOnlyViewerCantDragACard() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.board = board("todo" to listOf(card("m-post", "message", "todo", "Why is the Mac build slow?", canMove = false)))
        app()
        openBoard()
        drag("m-post", "done")
        waitForIdle()
        Thread.sleep(500)
        assertNull(server.wrote("POST", "/api/cards/message/m-post/status"), "a read-only card stays where it is")
        // Its menu offers no moves, and says why.
        waitForNode(hasContentDescription("Card options")).performClick()
        waitForNode(hasText("You can read this channel but not change it."))
        assertTrue(onAllNodes(hasText("Move to Done")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun clickingACardOpensItOnItsOwnWithoutPagingTheFeed() = runDesktopComposeUiTest(width = 1440, height = 900) {
        // A card the feed never loaded: the board has it, the feed doesn't.
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val old = UpdateView(id = "u-old", seq = 1, projectId = "p1", agentId = "a1", title = "Something from last month", body = "The old plan.", createdAt = FakeServer.now() - 40L * 24 * 3600_000)
        val feed = json.encodeToJsonElement(FeedSnapshot.serializer(), FeedSnapshot(seq = 10, updates = UpdatesPage(listOf(old)), agentNames = server.agentNames)).jsonObject
        val thread = json.encodeToJsonElement(MessagesSnapshot.serializer(), MessagesSnapshot(seq = 10, messages = emptyList())).jsonObject
        server.threads["u-old"] = JsonObject(feed + thread + ("card" to buildJsonObject { put("kind", "update"); put("id", "u-old") })).toString()
        server.board = board("todo" to listOf(card("u-old", "update", "todo", "Something from last month")))
        app()
        openBoard()
        val pages = server.count("/api/snapshot/updates")
        waitForNode(hasTestTag("board-card-u-old")).performClick()
        waitForNode(hasText("Back to feed"))
        waitForNode(hasText("The old plan."))
        assertTrue(onAllNodes(hasTestTag("board-column-todo")).fetchSemanticsNodes().isEmpty(), "the board steps aside")
        assertEquals(1, server.count("/api/threads/u-old"), "read once, by itself")
        assertEquals(pages, server.count("/api/snapshot/updates"), "no feed page was read to find it")
    }

    @Test
    fun allFourColumnsFitBesideTheSidebarAndTheRailAt1280Wide() = runDesktopComposeUiTest(width = 1280, height = 1000) {
        app(1280, 1000)
        openBoard()
        val done = waitForNode(hasTestTag("board-column-done")).fetchSemanticsNode().boundsInRoot
        val width = onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(done.width > 0 && done.right <= width, "Done is on screen: $done in $width")
    }
}
