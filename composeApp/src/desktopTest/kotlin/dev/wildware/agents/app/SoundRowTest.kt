package dev.wildware.agents.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.SoundPlayer
import dev.wildware.agents.app.feed.SoundRow
import dev.wildware.agents.app.feed.SoundState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** A sound played in the app: play and pause, where it has got to, and a tap on the bars to jump. */
@OptIn(ExperimentalTestApi::class)
class SoundRowTest {
    private class FakePlayer : SoundPlayer {
        override val state = MutableStateFlow(SoundState(durationMs = 9_000))
        val calls = mutableListOf<String>()
        override fun play() { calls += "play"; state.value = state.value.copy(playing = true) }
        override fun pause() { calls += "pause"; state.value = state.value.copy(playing = false) }
        override fun seek(ms: Long) { calls += "seek $ms"; state.value = state.value.copy(positionMs = ms) }
        override fun release() { calls += "release" }
    }

    private val sound = Sound("snd", "https://dash.test/media/snd/original", 9_000, "audio/mpeg")

    @Test
    fun playPausesAndSaysWhereItIs() = runComposeUiTest {
        val player = FakePlayer()
        setContent { AgentTheme(false) { SoundRow(sound, index = 0, total = 1, player = player) } }
        onNodeWithContentDescription("Play sound 1 of 1, 0:09").performClick()
        assertEquals(listOf("play"), player.calls)
        onNodeWithContentDescription("Pause sound 1 of 1, 0:09").performClick()
        assertEquals(listOf("play", "pause"), player.calls)
        player.state.value = player.state.value.copy(positionMs = 3_000)
        onNodeWithText("0:03 / 0:09").assertExists()
    }
}
