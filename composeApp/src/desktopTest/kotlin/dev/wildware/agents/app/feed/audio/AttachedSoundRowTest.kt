package dev.wildware.agents.app.feed.audio

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.SoundRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/** The row of a sound on a desktop, with the real player behind it: play, playing, the end, and why it can't. */
@OptIn(ExperimentalTestApi::class)
class AttachedSoundRowTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sounds/$name")).use { it.readBytes() }

    /** Takes samples without a sound device; holds the first block until [gate] opens. */
    private class HeldOutput(private val gate: CountDownLatch) : SoundOutput {
        @Volatile private var frames = 0L
        override fun open(sampleRate: Int, channels: Int) {}
        override fun write(samples: ShortArray) {
            gate.await(5, TimeUnit.SECONDS)
            frames += samples.size
        }
        override val framesPlayed: Long get() = frames
        override fun pause() {}
        override fun resume() {}
        override fun drain() {}
        override fun close() {}
    }

    @Test
    fun playShowsPauseUntilItEndsThenPlayAgain() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CountDownLatch(1)
        val player = AttachedSoundPlayer(400, scope, load = { fixture("short.mp3") }, output = { HeldOutput(gate) }, tickMs = 10)
        val sound = Sound("snd", "https://dash.test/media/snd/original", 400, "audio/mpeg", name = "ping.mp3")
        setContent { AgentTheme(false) { SoundRow(sound, index = 0, total = 1, player = player) } }

        onNodeWithContentDescription("Play sound 1 of 1, ping.mp3, 0:00").performClick()
        waitUntil(timeoutMillis = 5_000) { pauseShown() }
        gate.countDown()
        waitUntil(timeoutMillis = 5_000) { playShown() }
        onNodeWithText("0:00 / 0:00").assertExists()
        scope.cancel()
    }

    @Test
    fun aSoundItCantPlaySaysWhyOnItsRow() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = AttachedSoundPlayer(400, scope, load = { fixture("tone.flac") }, output = { HeldOutput(CountDownLatch(0)) })
        val sound = Sound("snd", "https://dash.test/media/snd/original", 400, "audio/flac")
        setContent { AgentTheme(false) { SoundRow(sound, index = 0, total = 1, player = player) } }

        onNodeWithContentDescription("Play sound 1 of 1, 0:00").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Can't play FLAC").assertExists() }.isSuccess }
        scope.cancel()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.pauseShown(): Boolean =
        runCatching { onNodeWithContentDescription("Pause sound 1 of 1, ping.mp3, 0:00").assertExists() }.isSuccess

    private fun androidx.compose.ui.test.ComposeUiTest.playShown(): Boolean =
        runCatching { onNodeWithContentDescription("Play sound 1 of 1, ping.mp3, 0:00").assertExists() }.isSuccess
}
