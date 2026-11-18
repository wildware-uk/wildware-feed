package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A clipboard that keeps what is copied in memory, under both of Compose's clipboard APIs. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
class FakeClipboard : Clipboard, ClipboardManager {
    var text: String? = null
    override suspend fun getClipEntry(): ClipEntry? = text?.let { ClipEntry(StringSelection(it)) }
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val native = clipEntry?.nativeClipEntry as? java.awt.datatransfer.Transferable
        text = native?.getTransferData(DataFlavor.stringFlavor) as? String
    }
    override val nativeClipboard: java.awt.datatransfer.Clipboard get() = error("not used")
    override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
    override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
}

/** Selecting and copying the text of messages: free selection, code blocks and inline code. */
@OptIn(ExperimentalTestApi::class)
class TextSelectionTest {
    private val events = mutableListOf<FeedEvent>()
    private val clipboard = FakeClipboard()
    private val code = "// one marker per connection, so sessions stop starving each other\n" +
        "const key = `\${agentId}:\${connectionId}`;\n" +
        "unread.set(key, lastSeenId);"
    private val firstLine = "Two sessions on the same token"
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    @Suppress("DEPRECATION")
    private fun ComposeUiTest.feed(width: Int, height: Int, startAt: String?) = setContent {
        CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides clipboard) {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, height.dp)) {
                    FeedScreen(FakeFeed.state.copy(openThread = null), startAt = startAt, onEvent = { events += it })
                }
            }
        }
    }

    private fun ComposeUiTest.desktop(startAt: String? = "u1") = feed(1200, 900, startAt)
    private fun ComposeUiTest.phone(startAt: String? = "u1") = feed(390, 844, startAt)

    /** Where character [index] of a text node sits, in the node's own coordinates. */
    private fun SemanticsNodeInteraction.charCenter(index: Int): Offset {
        val node = fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.first().getBoundingBox(index).center
    }

    private fun SemanticsNodeInteraction.charCenter(of: String): Offset {
        val text = fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        val at = text.indexOf(of)
        assertTrue(at >= 0, "'$of' not in '$text'")
        return charCenter(at + of.length / 2)
    }

    @Test
    fun theCodeBlockCopyButtonCopiesTheExactCode() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        onNodeWithContentDescription("Copy code").performScrollTo().performClick()
        waitForIdle()
        assertEquals(code, clipboard.text)
        onNodeWithContentDescription("Copied").assertExists()
        assertTrue(events.isEmpty(), "copying is not a feed event: $events")
    }

    @Test
    fun clickingInlineCodeCopiesIt() = runDesktopComposeUiTest(1200, 900) {
        desktop(startAt = "u3")
        val body = onNodeWithText("Old Gradle caches", substring = true).performScrollTo()
        val at = body.charCenter("gradle --stop")
        body.performMouseInput { click(at) }
        waitForIdle()
        assertEquals("gradle --stop", clipboard.text)
        onNode(isLiveRegion() and SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Copied")))).assertExists()
        assertTrue(events.isEmpty(), "got $events")
    }

    @Test
    fun draggingAcrossParagraphsSelectsAndCtrlCCopies() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        val first = onNodeWithText(firstLine, substring = true).performScrollTo()
        val list = onNodeWithText("Old markers are migrated", substring = true)
        val from = Offset(1f, first.charCenter(0).y)
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val to = list.charCenter("migrated") + Offset(listBounds.left - firstBounds.left, listBounds.top - firstBounds.top)
        first.performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(40f, 0f))
            moveTo(to)
            release()
        }
        first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        val copied = clipboard.text ?: ""
        assertTrue(copied.startsWith("Two sessions"), "copied '$copied'")
        assertTrue("Unread state is now kept" in copied && "migr" in copied, "selection must cross paragraphs: '$copied'")
        assertTrue(events.isEmpty(), "dragging must not act on the card: $events")
        onNodeWithText("Copy link").assertDoesNotExist()
    }

    @Test
    fun doubleClickingBodyTextSelectsAWordAndDoesNotReact() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        val first = onNodeWithText(firstLine, substring = true).performScrollTo()
        first.performMouseInput { doubleClick(first.charCenter("sessions")) }
        first.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        assertEquals("sessions", clipboard.text?.trim())
        assertTrue(events.isEmpty(), "a double-click on text must not react: $events")
    }

    @Test
    fun rightClickingSelectedTextOffersCopyNotTheCardMenu() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        val first = onNodeWithText(firstLine, substring = true).performScrollTo()
        val at = first.charCenter("sessions")
        first.performMouseInput { doubleClick(at) }
        first.performMouseInput { rightClick(at) }
        waitForIdle()
        onNodeWithText("Copy").assertExists().performClick()
        waitForIdle()
        assertEquals("sessions", clipboard.text?.trim())
        onNodeWithText("Copy link").assertDoesNotExist()
    }

    @Test
    fun rightClickingTheTitleStillOpensTheCardMenu() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Copy link").assertExists()
    }

    @Test
    fun theCopyButtonWorksFromTheKeyboard() = runDesktopComposeUiTest(1200, 900) {
        desktop()
        val button = onNodeWithContentDescription("Copy code").performScrollTo()
        assertTrue(button.fetchSemanticsNode().config.getOrNull(SemanticsActions.RequestFocus) != null, "the copy button must take focus")
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(code, clipboard.text)
    }

    @Test
    fun onAPhoneLongPressOnBodyTextSelectsInsteadOfOpeningTheMenu() = runComposeUiTest {
        phone()
        val first = onNodeWithText(firstLine, substring = true).performScrollTo()
        first.performTouchInput { longClick(first.charCenter("sessions")) }
        waitForIdle()
        onNodeWithText("Copy link").assertDoesNotExist()
        assertTrue(events.isEmpty(), "got $events")
    }

    @Test
    fun onAPhoneLongPressOnTheTitleStillOpensTheMenu() = runComposeUiTest {
        phone()
        onNodeWithText(fixTitle).performScrollTo().performTouchInput { longClick() }
        onNodeWithText("Copy link").assertExists()
    }

    @Test
    fun onAPhoneTappingInlineCodeCopiesIt() = runComposeUiTest {
        phone(startAt = "u3")
        val body = onNodeWithText("Old Gradle caches", substring = true).performScrollTo()
        body.performTouchInput { click(body.charCenter("gradle --stop")) }
        waitForIdle()
        assertEquals("gradle --stop", clipboard.text)
    }

    @Test
    fun onAPhoneSwipingAQuestionBodyStillAnswers() = runComposeUiTest {
        phone(startAt = null)
        onNodeWithContentDescription("Question from relay, waiting on you").performTouchInput {
            swipe(center, center + Offset(260f, 0f), 200)
        }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Answer("q1", listOf("Ship it"))), events)
    }

    private fun isLiveRegion() = SemanticsMatcher("is a live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }
}
