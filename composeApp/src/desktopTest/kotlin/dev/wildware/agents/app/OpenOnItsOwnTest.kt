package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FeedSnapshot
import dev.wildware.agents.MessageView
import dev.wildware.agents.MessagesSnapshot
import dev.wildware.agents.UpdateView
import dev.wildware.agents.UpdatesPage
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A card the feed on screen hasn't loaded (far older than its first page, or another channel's)
 * opens on its own, read by itself with its thread. The feed under it is never paged down to it,
 * and "Back to feed" leaves the feed just as it was. Against the real engine and a fake server.
 */
@OptIn(ExperimentalTestApi::class)
class OpenOnItsOwnTest {
    private val server = FakeServer().apply { hasMore = true }
    private val navigator = AppNavigator()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val old = UpdateView(
        id = "u-old", seq = 1, projectId = "p1", agentId = "a1", title = "Something from last month",
        body = "The old plan, from before.", createdAt = FakeServer.now() - 40L * 24 * 3600_000,
    )
    private val oldReply = MessageView(
        id = "m-old", seq = 2, projectId = "p1", updateId = "u-old", author = "agent:a2", body = "The old reply, still there.",
        createdAt = FakeServer.now() - 39L * 24 * 3600_000,
    )

    @AfterTest
    fun stop() = server.close()

    /** `GET /api/threads/{id}`'s answer for [update] and its [replies]. */
    private fun threadBody(update: UpdateView, replies: List<MessageView>): String {
        val feed = json.encodeToJsonElement(FeedSnapshot.serializer(), FeedSnapshot(seq = 10, updates = UpdatesPage(listOf(update)), agentNames = server.agentNames)).jsonObject
        val thread = json.encodeToJsonElement(MessagesSnapshot.serializer(), MessagesSnapshot(seq = 10, messages = replies)).jsonObject
        return JsonObject(feed + thread + ("card" to buildJsonObject { put("kind", "update"); put("id", update.id) })).toString()
    }

    private fun ComposeUiTest.app(width: Int, height: Int) {
        val store = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1"))
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = store)
        setContent {
            Box(Modifier.size(width.dp, height.dp)) {
                AgentsApp(
                    platform, dark = true, accessibility = AccessibilityPrefs(),
                    safeArea = if (width < 600) SafeArea(top = 54.dp, bottom = 34.dp) else SafeArea(), navigator = navigator,
                )
            }
        }
    }

    private fun ComposeUiTest.shown(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.gone(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }

    /** Waits until nothing more is read from [path] for a second, and says how many reads there were. */
    private fun ComposeUiTest.settled(path: String): Int {
        var last = -1
        var since = System.currentTimeMillis()
        waitUntil(timeoutMillis = 15_000) {
            val n = server.count(path)
            if (n != last) {
                last = n
                since = System.currentTimeMillis()
            }
            System.currentTimeMillis() - since > 1_000
        }
        return last
    }

    @Test
    fun aLinkToACardFarOlderThanTheFeedOpensItOnItsOwnWithNoPageRead() = runDesktopComposeUiTest(1440, 900) {
        server.threads["u-old"] = threadBody(old, listOf(oldReply))
        app(1440, 900)
        shown("Fixed the unread counts")
        val pages = settled("/api/snapshot/updates")

        navigator.open(null, "u-old")
        shown("Something from last month")
        shown("The old reply, still there.")
        onNodeWithText("Back to feed").assertExists()
        assertEquals(1, server.count("/api/threads/u-old"), "read once, by itself")
        assertEquals(pages, server.count("/api/snapshot/updates"), "no feed page was read to find it")

        // Its reply box is open under it, and a reply goes to that card's thread.
        onNodeWithContentDescription("Reply").performTextInput("Still the plan")
        val field = onNodeWithContentDescription("Reply").fetchSemanticsNode().boundsInRoot
        val sends = onAllNodes(hasContentDescription("Send"))
        sends[sends.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.top == field.top }].performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 10_000) { server.wrote("POST", "/api/messages") != null }
        val body = server.wrote("POST", "/api/messages")!!
        assertTrue("\"update\":\"u-old\"" in body && "Still the plan" in body, body)

        // Back to the feed, just as it was: the old card was never put in it.
        onNodeWithText("Back to feed").performClick()
        gone("Something from last month")
        onAllNodes(hasText("Fixed the unread counts")).onFirst().assertExists()
        assertEquals(pages, server.count("/api/snapshot/updates"))
    }

    @Test
    fun aSearchResultFromAnotherChannelOpensOnItsOwnAndTheChannelStays() = runDesktopComposeUiTest(1440, 900) {
        app(1440, 900)
        shown("Fixed the unread counts")
        onAllNodes(hasText("Other")).onFirst().performClick()
        gone("Fixed the unread counts")
        val pages = settled("/api/snapshot/updates")

        onNodeWithContentDescription("Search everything. Shortcut Command K").performClick()
        onNodeWithContentDescription("Search or run a command").performTextInput("unread counts")
        onNodeWithContentDescription("Search or run a command").performKeyInput { pressKey(Key.Enter) }
        // The card and its thread, replies and all, over the feed of Other.
        shown("Fixed the unread counts")
        shown("Confirmed on my side.")
        onNodeWithText("Back to feed").assertExists()
        assertEquals(pages, server.count("/api/snapshot/updates"), "no feed page was read to find it")
        assertEquals(0, server.count("/api/snapshot?project=mega"), "Other stays on screen: Mega's feed is never read")

        onNodeWithText("Back to feed").performClick()
        gone("Fixed the unread counts")
    }

    @Test
    fun onAPhoneTheCardOnItsOwnOpensInTheThreadScreen() = runDesktopComposeUiTest(390, 844) {
        server.threads["m-old"] = threadBody(old, listOf(oldReply))
        app(390, 844)
        shown("Fixed the unread counts")
        val pages = settled("/api/snapshot/updates")

        // A link to the reply: its card opens, with the reply in its thread.
        navigator.open(null, "m-old")
        shown("Something from last month")
        shown("The old reply, still there.")
        assertEquals(pages, server.count("/api/snapshot/updates"), "no feed page was read to find it")

        onNodeWithContentDescription("Back to All channels").performClick()
        gone("Something from last month")
        onAllNodes(hasText("Fixed the unread counts")).onFirst().assertExists()
    }
}
