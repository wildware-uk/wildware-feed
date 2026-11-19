package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Composer
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.LocalVoiceInput
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.SharedDraft
import dev.wildware.agents.app.feed.VoiceInput
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On a phone the message box offers the mic while it is empty and the send button once it holds
 * something. The button follows the words in the box however they got there.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerSendButtonTest {
    /** A phone's recorder that never records: only there so the box offers the mic. */
    private object QuietVoice : VoiceInput {
        override fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit) {}
        override fun finish(onDone: (text: String?, problem: String?) -> Unit) {}
        override fun cancel() {}
    }

    private val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) {}
    private var draftKey by mutableStateOf("project:one")
    private val sent = mutableListOf<String>()

    private fun ComposeUiTest.phone() = setContent {
        CompositionLocalProvider(LocalVoiceInput provides QuietVoice, LocalFeedUi provides ui) {
            AgentTheme(dark = false) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    Composer(
                        placeholder = "Message…",
                        onSend = { text, _ -> sent += text },
                        compact = true,
                        focus = remember { FocusRequester() },
                        draftKey = draftKey,
                    )
                }
            }
        }
    }

    private val box get() = hasSetTextAction() and hasContentDescription("Message")

    private fun ComposeUiTest.showsSend() {
        waitForIdle()
        onNodeWithContentDescription("Send").assertExists()
        onNodeWithContentDescription("Hold to talk").assertDoesNotExist()
    }

    private fun ComposeUiTest.showsMic() {
        waitForIdle()
        onNodeWithContentDescription("Hold to talk").assertExists()
        onNodeWithContentDescription("Send").assertDoesNotExist()
    }

    @Test
    fun typingTurnsTheMicIntoSend() = runComposeUiTest {
        phone()
        showsMic()
        onNode(box).performTextInput("ship it")
        showsSend()
    }

    @Test
    fun pastingOverTheWholeBoxTurnsTheMicIntoSend() = runComposeUiTest {
        phone()
        onNode(box).performTextReplacement("pasted from somewhere")
        showsSend()
    }

    @Test
    fun onlySpacesKeepTheMic() = runComposeUiTest {
        phone()
        onNode(box).performTextInput("   ")
        showsMic()
    }

    @Test
    fun wordsSharedInFromAnotherAppTurnTheMicIntoSend() = runComposeUiTest {
        phone()
        showsMic()
        runOnIdle { ui.incoming = SharedDraft("from another app", emptyList(), serial = 1) }
        showsSend()
    }

    @Test
    fun clearingTheBoxBringsTheMicBack() = runComposeUiTest {
        phone()
        onNode(box).performTextInput("never mind")
        showsSend()
        onNode(box).performTextClearance()
        showsMic()
    }

    @Test
    fun sendingEmptiesTheBoxAndBringsTheMicBack() = runComposeUiTest {
        phone()
        onNode(box).performTextInput("done")
        onNodeWithContentDescription("Send").performClick()
        showsMic()
        assertEquals(listOf("done"), sent)
    }

    @Test
    fun aBoxForAnotherProjectFollowsItsOwnWords() = runComposeUiTest {
        phone()
        showsMic()
        // The same box on screen now writes to another project: typing there must still offer Send.
        runOnIdle { draftKey = "project:two" }
        onNode(box).performTextInput("hello two")
        showsSend()
        onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("hello two"), sent)
        showsMic()
    }

    @Test
    fun aRestoredDraftOffersSendAndAnEmptyOneTheMic() = runComposeUiTest {
        phone()
        onNode(box).performTextInput("kept for later")
        showsSend()
        // Away to a project with nothing written: the mic.
        runOnIdle { draftKey = "project:two" }
        showsMic()
        // Back again: the draft comes back, and with it Send.
        runOnIdle { draftKey = "project:one" }
        showsSend()
    }
}
