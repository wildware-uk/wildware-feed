package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.engine.Mentionables
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.MentionKind
import dev.wildware.agents.app.feed.MentionText
import dev.wildware.agents.app.feed.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typing @ in any message box lists the agents it can mention, and picking one writes "@name ". */
@OptIn(ExperimentalTestApi::class)
class MentionTest {
    // --- The word being typed ---------------------------------------------------------------

    @Test
    fun anAtStartsAMentionAndTheLettersAfterItFilter() {
        assertEquals(MentionText.Typing(3, "fo"), MentionText.at("hi @fo", 6))
        assertEquals(MentionText.Typing(0, ""), MentionText.at("@", 1))
        assertEquals(MentionText.Typing(4, "build-b"), MentionText.at("ask @build-b", 12))
    }

    @Test
    fun anEmailAddressOrAFinishedWordIsNotAMention() {
        assertNull(MentionText.at("mail a@b", 8))
        assertNull(MentionText.at("hi @forge then", 14))
        assertNull(MentionText.at("hi there", 8))
    }

    @Test
    fun pickingReplacesWhatWasTypedWithTheNameAndASpace() {
        assertEquals("hi @forge " to 10, MentionText.insert("hi @fo", MentionText.Typing(3, "fo"), "forge"))
        assertEquals("@scout and more" to 7, MentionText.insert("@sc and more", MentionText.Typing(0, "sc"), "scout", cursor = 3))
    }

    // --- Who is listed ----------------------------------------------------------------------

    @Test
    fun onlineAgentsComeFirstThenTheRestByWhenTheyWereLastActive() {
        val names = mapOf("a1" to "scout", "a2" to "forge", "a3" to "quill", "a4" to "atlas")
        val online = listOf(LiveAgentView(agentId = "a2", name = "forge", lastHeartbeatAt = 10))
        val list = Mentionables.of(names, online, lastActive = mapOf("a3" to 500L, "a1" to 900L))
        assertEquals(listOf("here", "everyone", "forge", "scout", "quill", "atlas"), list.map { it.name })
        assertEquals(listOf(MentionKind.Group, MentionKind.Group), list.take(2).map { it.kind })
        assertEquals(listOf("every agent online now", "every agent"), list.take(2).map { it.hint })
        assertEquals(Presence.Online, list[2].presence)
        assertEquals(Presence.Offline, list.last().presence)
    }

    // --- The popup --------------------------------------------------------------------------

    private val box = hasContentDescription("Message") and hasSetTextAction()
    private fun row(name: String): SemanticsMatcher = hasContentDescription("Mention $name", substring = true)

    private fun ComposeUiTest.feed(width: Int = 1440) = setContent {
        AgentTheme(dark = true) { Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(FakeFeed.state) } }
    }

    private fun ComposeUiTest.typed(): String =
        onNode(box).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    @Test
    fun typingAtListsEveryAgentWithTheOnlineOnesFirst() = runComposeUiTest {
        feed()
        onNode(box).performTextInput("ask @")
        onNode(hasContentDescription("7 to mention", substring = true)).assertExists()
        assertTrue(onAllNodes(row("here")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(row("forge")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(row("quill")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun lettersFilterAndEnterInsertsTheName() = runComposeUiTest {
        feed()
        onNode(box).performTextInput("ask @sc")
        assertTrue(onAllNodes(row("forge")).fetchSemanticsNodes().isEmpty())
        onNode(box).performKeyInput { pressKey(Key.Enter) }
        assertEquals("ask @scout ", typed())
        assertTrue(onAllNodes(row("scout")).fetchSemanticsNodes().isEmpty(), "closed once picked")
    }

    @Test
    fun arrowsMoveTheChoiceAndTabInsertsIt() = runComposeUiTest {
        feed()
        onNode(box).performTextInput("@")
        onNode(box).performKeyInput { pressKey(Key.DirectionDown) }
        onNode(box).performKeyInput { pressKey(Key.Tab) }
        assertEquals("@" + FakeFeed.state.mentionable[1].name + " ", typed())
    }

    @Test
    fun escapeClosesTheListAndLeavesTheText() = runComposeUiTest {
        feed()
        onNode(box).performTextInput("hi @f")
        onNode(box).performKeyInput { pressKey(Key.Escape) }
        assertTrue(onAllNodes(row("forge")).fetchSemanticsNodes().isEmpty())
        assertEquals("hi @f", typed())
    }

    @Test
    fun onAPhoneATapInserts() = runComposeUiTest {
        feed(width = 390)
        onNode(box).performClick()
        onNode(box).performTextInput("hey @lu")
        onAllNodes(row("lumen")).onFirst().performClick()
        assertEquals("hey @lumen ", typed())
    }

    @Test
    fun aThreadReplyBoxHasTheListToo() = runComposeUiTest {
        feed()
        val reply = hasContentDescription("Reply") and hasSetTextAction()
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(reply)
        onAllNodes(reply).onFirst().performTextInput("thanks @rel")
        assertTrue(onAllNodes(row("relay")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("@relay")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun hereAndEveryoneComeFirstWithWhatTheyReach() = runComposeUiTest {
        feed()
        onNode(box).performTextInput("@ev")
        assertTrue(onAllNodes(hasContentDescription("Mention everyone, every agent", substring = true)).fetchSemanticsNodes().isNotEmpty())
        onNode(box).performKeyInput { pressKey(Key.Enter) }
        assertEquals("@everyone ", typed())
    }
}
