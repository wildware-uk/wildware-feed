package dev.wildware.agents.app

import dev.wildware.agents.VoiceWord
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Composer
import dev.wildware.agents.app.feed.LocalVoiceInput
import dev.wildware.agents.app.feed.VoiceInput
import dev.wildware.agents.app.feed.VoiceRecorder
import kotlin.test.Test
import kotlin.test.assertEquals

/** While a voice note records, the words show as they are heard; the finished words replace them. */
@OptIn(ExperimentalTestApi::class)
class LiveWordsTest {
    /** Stands in for the phone's recorder: the test says when it started, what it heard, and the end. */
    private class FakeVoice : VoiceInput {
        var heard: (String) -> Unit = {}
        var started: (String?) -> Unit = {}
        var done: (String?, List<VoiceWord>, String?) -> Unit = { _, _, _ -> }
        var cancelled = false

        override fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit) {
            heard = onHeard
            started = onStarted
        }

        override fun finish(onDone: (text: String?, words: List<VoiceWord>, problem: String?) -> Unit) {
            done = onDone
        }

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun theRecorderHoldsTheWordsHeardSoFarUntilTheFinishedOnesArrive() {
        val voice = FakeVoice()
        val written = mutableListOf<String>()
        val recorder = VoiceRecorder(voice) { text, _ -> written += text; false }

        recorder.press()
        voice.started(null)
        voice.heard("ship the")
        assertEquals("ship the", recorder.heard)
        voice.heard("ship the build")
        assertEquals("ship the build", recorder.heard)

        recorder.release()
        assertEquals("ship the build", recorder.heard, "still shown while it is written down")
        voice.done("Ship the build.", emptyList(), null)
        assertEquals(listOf("Ship the build."), written)
        assertEquals("", recorder.heard)
    }

    @Test
    fun cancellingThrowsTheHeardWordsAway() {
        val voice = FakeVoice()
        val recorder = VoiceRecorder(voice) { _, _ -> false }
        recorder.press()
        voice.started(null)
        voice.heard("never mind")
        recorder.willCancel = true
        recorder.release()
        assertEquals("", recorder.heard)
        assertEquals(true, voice.cancelled)
    }

    @Test
    fun wordsHeardAfterTheEndAreIgnored() {
        val voice = FakeVoice()
        val recorder = VoiceRecorder(voice) { _, _ -> false }
        recorder.press()
        voice.started(null)
        recorder.release()
        voice.done("Done.", emptyList(), null)
        voice.heard("late partial")
        assertEquals("", recorder.heard)
    }

    @Test
    fun theComposerShowsTheWordsAsTheyAreSpoken() = runComposeUiTest {
        val voice = FakeVoice()
        setContent {
            CompositionLocalProvider(LocalVoiceInput provides voice) {
                AgentTheme(dark = false) {
                    Box(Modifier.size(390.dp, 300.dp)) { Composer(placeholder = "Message…", onSend = { _, _ -> }, compact = true) }
                }
            }
        }
        onNodeWithContentDescription("Hold to talk").performTouchInput { down(center) }
        runOnIdle { voice.started(null) }
        runOnIdle { voice.heard("ship the build") }
        onNodeWithText("ship the build").assertExists()
        onNodeWithContentDescription("Hold to talk").performTouchInput { up() }
        runOnIdle { voice.done("Ship the build tonight.", emptyList(), null) }
        onNodeWithText("ship the build").assertDoesNotExist()
        onNode(hasSetTextAction()).assertExists()
        onNodeWithText("Ship the build tonight.").assertExists()
    }
}
