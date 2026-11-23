package dev.wildware.agents.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.wildware.agents.VoiceNote
import dev.wildware.agents.VoiceWord
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ReplyView
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unsure words in a waiting voice note: tapped, the countdown holds and other guesses show. */
@OptIn(ExperimentalTestApi::class)
class VoiceWordChoiceTest {
    private val now = 1_000_000L
    private val events = mutableListOf<FeedEvent>()

    /** Where the outbox's hold ends, moved as the session would move it. */
    private val holdUntil = mutableStateOf<Long?>(now + VoiceNote.UndoMillis)
    private val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) { event ->
        events += event
        when (event) {
            is FeedEvent.PauseUnsent -> holdUntil.value = now + VoiceNote.PauseMillis
            is FeedEvent.ChooseWord -> holdUntil.value = now + VoiceNote.UndoMillis
            else -> Unit
        }
    }
    private val friday = VoiceWord(11, 17, 0.3, listOf("Monday"))

    private fun ComposeUiTest.show(sending: Boolean = true, undoUntil: Long? = now + VoiceNote.UndoMillis) {
        holdUntil.value = undoUntil
        setContent {
            CompositionLocalProvider(LocalFeedUi provides ui, LocalNow provides { now }) {
                AgentTheme(dark = false) {
                    ReplyView(
                        Reply(
                            id = "outbox:abc", author = Author.Owner("You"), time = "now", body = "Ship it on Friday",
                            voice = true, sending = sending, undoUntil = holdUntil.value, words = listOf(friday),
                        ),
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.word() =
        onNode(hasClickAction() and hasAnyAncestor(hasText("Ship it on Friday")), useUnmergedTree = true)

    @Test
    fun tappingAnUnsureWordHoldsTheCountdownAndOffersOtherWords() = runComposeUiTest {
        mainClock.autoAdvance = false
        show()
        mainClock.advanceTimeByFrame()
        word().performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(listOf<FeedEvent>(FeedEvent.PauseUnsent("abc")), events)
        onNodeWithText("Paused").assertExists()
        onNodeWithText("Keep “Friday”").assertExists()
        // The countdown stands still while the owner chooses.
        mainClock.advanceTimeBy(VoiceNote.UndoMillis * 2)
        onNodeWithText("Paused").assertExists()
        onNodeWithText("Undo").assertExists()

        onNodeWithText("Monday").performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(listOf(FeedEvent.PauseUnsent("abc"), FeedEvent.ChooseWord("abc", 0, "Monday")), events)
        onNodeWithText("Monday").assertDoesNotExist()
        // A fresh few seconds to see the change, then it goes.
        onNodeWithText("Sending in 4s").assertExists()
        mainClock.advanceTimeBy(VoiceNote.UndoMillis + 100)
        onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun keepingTheWordResumesWithItAsItIs() = runComposeUiTest {
        mainClock.autoAdvance = false
        show()
        mainClock.advanceTimeByFrame()
        word().performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Keep “Friday”").performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(listOf(FeedEvent.PauseUnsent("abc"), FeedEvent.ChooseWord("abc", 0, null)), events)
    }

    @Test
    fun onceSentTheWordsAreOrdinaryText() = runComposeUiTest {
        show(sending = false, undoUntil = null)
        onNodeWithText("Ship it on Friday").assertExists()
        word().assertDoesNotExist()
    }

    @Test
    fun afterTheWindowTheWordsAreOrdinaryText() = runComposeUiTest {
        show(undoUntil = now - 1)
        word().assertDoesNotExist()
    }
}
