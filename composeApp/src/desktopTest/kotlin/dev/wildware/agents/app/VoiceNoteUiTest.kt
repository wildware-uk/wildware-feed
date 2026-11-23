package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.OwnerActions
import dev.wildware.agents.VoiceNote
import dev.wildware.agents.VoiceReviewSetting
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.Composer
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.PostCard
import dev.wildware.agents.app.feed.Project
import dev.wildware.agents.app.feed.SendingLabel
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.LocalVoiceInput
import dev.wildware.agents.app.feed.LocalVoiceReview
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ReplyView
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.VoiceInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Voice notes in the app: a small mic marks one, letting go of the mic sends it with a few
 * seconds to Undo, and "Review voice notes before sending" puts the words in the box instead.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceNoteUiTest {
    /** Records at once and hears the same words every time. */
    private object HeardVoice : VoiceInput {
        override fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit) = onStarted(null)
        override fun finish(onDone: (text: String?, problem: String?) -> Unit) = onDone("ship it on friday", null)
        override fun cancel() {}
    }

    private val events = mutableListOf<FeedEvent>()
    private val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) { events += it }
    private val now = 1_000_000L

    private fun reply(voice: Boolean, sending: Boolean = false, undoUntil: Long? = null) = Reply(
        id = "outbox:abc", author = Author.Owner("You"), time = "9:05", body = "ship it on friday",
        voice = voice, sending = sending, undoUntil = undoUntil,
    )

    private fun ComposeUiTest.show(reply: Reply) = setContent {
        CompositionLocalProvider(LocalFeedUi provides ui, LocalNow provides { now }) {
            AgentTheme(dark = false) { ReplyView(reply) }
        }
    }

    @Test
    fun aVoiceNoteIsMarkedAndATypedOneIsNot() {
        runComposeUiTest {
            show(reply(voice = true))
            onNode(hasContentDescription("voice note", substring = true)).assertExists()
        }
        runComposeUiTest {
            show(reply(voice = false))
            onNode(hasContentDescription("voice note", substring = true)).assertDoesNotExist()
        }
    }

    @Test
    fun theUndoWindowCountsDownAndIsNotDrawnAsSending() = runComposeUiTest {
        mainClock.autoAdvance = false
        show(reply(voice = true, sending = true, undoUntil = now + VoiceNote.UndoMillis))
        mainClock.advanceTimeByFrame()
        onNodeWithText("Sending in 4s").assertExists()
        onNodeWithText("Undo").assertExists()
        onNode(hasContentDescription(SendingLabel, substring = true)).assertDoesNotExist()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Sending in 3s").assertExists()
        // Once the window shuts, a note still waiting on the network looks like any other send.
        mainClock.advanceTimeBy(VoiceNote.UndoMillis)
        onNodeWithText("Undo").assertDoesNotExist()
        onNode(hasText("Sending in", substring = true)).assertDoesNotExist()
        onNode(hasContentDescription(SendingLabel, substring = true)).assertExists()
    }

    @Test
    fun aWindowAlreadyOverShowsNoPill() = runComposeUiTest {
        show(reply(voice = true, sending = true, undoUntil = now - 1))
        onNodeWithText("Undo").assertDoesNotExist()
        onNode(hasContentDescription(SendingLabel, substring = true)).assertExists()
    }

    @Test
    fun aPostInItsUndoWindowShowsTheCountdown() = runComposeUiTest {
        mainClock.autoAdvance = false
        val post = FeedItem.Post(
            id = "outbox:abc", time = "now", author = Author.Owner("You"), project = Project("p1", "one", unread = 0, hue = 0, slug = "one"),
            body = "ship it on friday", sending = true, voice = true, undoUntil = now + VoiceNote.UndoMillis,
        )
        setContent {
            CompositionLocalProvider(LocalFeedUi provides ui, LocalNow provides { now }) {
                AgentTheme(dark = false) { PostCard(post) }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Sending in 4s").assertExists()
        onNode(hasContentDescription(SendingLabel, substring = true)).assertDoesNotExist()
        onNodeWithText("Undo").performClick()
        assertEquals(listOf<FeedEvent>(FeedEvent.DropUnsent("abc")), events)
        mainClock.advanceTimeBy(VoiceNote.UndoMillis + 100)
        onNodeWithText("Undo").assertDoesNotExist()
        onNode(hasContentDescription(SendingLabel, substring = true)).assertExists()
    }

    @Test
    fun undoTakesTheNoteOutOfTheOutboxAndGoesWhenTheWaitIsOver() = runComposeUiTest {
        mainClock.autoAdvance = false
        show(reply(voice = true, sending = true, undoUntil = now + VoiceNote.UndoMillis))
        mainClock.advanceTimeByFrame()
        onNodeWithText("Undo").performClick()
        assertEquals(listOf<FeedEvent>(FeedEvent.DropUnsent("abc")), events)
        mainClock.advanceTimeBy(VoiceNote.UndoMillis + 100)
        onNodeWithText("Undo").assertDoesNotExist()
    }

    private fun ComposeUiTest.composer(review: Boolean, sentVoice: MutableList<Pair<String, OwnerActions.Voice>>) = setContent {
        CompositionLocalProvider(
            LocalVoiceInput provides HeardVoice,
            LocalVoiceReview provides VoiceReviewSetting(MemoryTextStore(if (review) "on" else null)),
            LocalFeedUi provides ui,
        ) {
            AgentTheme(dark = false) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    Composer(
                        placeholder = "Message…",
                        onSend = { _, _ -> error("typed send") },
                        onSendVoice = { text, _, voice -> sentVoice += text to voice },
                        compact = true,
                        focus = remember { FocusRequester() },
                        draftKey = "project:one",
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.holdAndLetGo() {
        onNodeWithContentDescription("Hold to talk").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithContentDescription("Hold to talk").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    @Test
    fun lettingGoSendsTheNoteWithAnUndoWindow() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, OwnerActions.Voice>>()
        composer(review = false, sent)
        holdAndLetGo()
        assertEquals(listOf("ship it on friday" to OwnerActions.Voice(VoiceNote.UndoMillis)), sent)
        onNode(hasSetTextAction() and hasText("ship it on friday")).assertDoesNotExist()
    }

    @Test
    fun withReviewOnTheWordsGoInTheBoxToCheck() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, OwnerActions.Voice>>()
        composer(review = true, sent)
        holdAndLetGo()
        assertTrue(sent.isEmpty(), sent.toString())
        onNode(hasSetTextAction() and hasText("ship it on friday")).assertExists()
        // Sent by hand, it still goes as a voice note, with no wait.
        onNodeWithContentDescription("Send").performClick()
        waitForIdle()
        assertEquals(listOf("ship it on friday" to OwnerActions.Voice()), sent)
    }
}
