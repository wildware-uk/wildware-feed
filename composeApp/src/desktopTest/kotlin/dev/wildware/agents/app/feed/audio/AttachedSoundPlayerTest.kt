package dev.wildware.agents.app.feed.audio

import dev.wildware.agents.app.feed.SoundState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A sound on a card, played on a desktop: play, playing, pause, seek, the end, and saying why it can't. */
class AttachedSoundPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sounds/$name")).use { it.readBytes() }

    /** Takes samples as fast as they come, so a test needs no sound device, and can hold them up. */
    private class CountingOutput(private val gate: CountDownLatch? = null) : SoundOutput {
        @Volatile var rate = 0
        @Volatile var frames = 0L
        @Volatile var channels = 1
        @Volatile var closed = false
        @Volatile var paused = false
        @Volatile var drained = false

        override fun open(sampleRate: Int, channels: Int) {
            rate = sampleRate
            this.channels = channels
        }

        override fun write(samples: ShortArray) {
            gate?.await(5, TimeUnit.SECONDS)
            frames += samples.size / channels
        }

        override val framesPlayed: Long get() = frames
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun drain() { drained = true }
        override fun close() { closed = true }
    }

    private fun player(
        bytes: () -> ByteArray,
        output: SoundOutput,
        knownMs: Long? = 400,
    ) = AttachedSoundPlayer(knownMs, scope, load = { bytes() }, output = { output }, tickMs = 10)

    private fun AttachedSoundPlayer.await(what: (SoundState) -> Boolean): SoundState =
        runBlocking { withTimeout(5_000) { state.first(what) } }

    @Test
    fun playsThenSaysItIsPlayingThenEnds() {
        val gate = CountDownLatch(1)
        val out = CountingOutput(gate)
        val p = player({ fixture("tone.mp3") }, out)
        assertFalse(p.state.value.playing)

        p.play()
        assertTrue(p.state.value.playing, "playing as soon as it is pressed")
        p.await { it.playing }

        gate.countDown()
        val ended = p.await { !it.playing }
        assertEquals(0, ended.positionMs, "back at the start once it ends")
        assertFalse(ended.failed)
        assertEquals(22_050, out.rate)
        assertTrue(out.frames > 22_050 * 0.35, "heard ${out.frames} frames")
        assertTrue(out.drained, "the last of it was let out")
        assertTrue(out.closed)
    }

    @Test
    fun aTenthOfASecondStillPlaysToTheEnd() {
        val out = CountingOutput()
        val p = player({ fixture("short.mp3") }, out, knownMs = 100)
        p.play()
        p.await { !it.playing }
        assertTrue(out.frames > 0)
        assertFalse(p.state.value.failed)
    }

    @Test
    fun everyFormatPlays() {
        for (name in listOf("tone.mp3", "stereo.mp3", "tone.m4a", "tone.aac", "tone.ogg", "tone.opus", "tone.wav")) {
            val out = CountingOutput()
            val p = player({ fixture(name) }, out)
            p.play()
            val end = p.await { !it.playing }
            assertFalse(end.failed, "$name failed: ${end.problem}")
            assertTrue(out.frames > out.rate * 0.35, "$name played ${out.frames} frames at ${out.rate}")
        }
    }

    @Test
    fun aSoundWithNoKnownLengthIsMeasured() {
        val gate = CountDownLatch(1)
        val p = player({ fixture("tone.ogg") }, CountingOutput(gate), knownMs = null)
        assertEquals(0, p.state.value.durationMs)
        p.play()
        val measured = p.await { it.durationMs > 0 }
        assertTrue(measured.durationMs in 380L..420L, "measured ${measured.durationMs}")
        gate.countDown()
    }

    @Test
    fun pauseHoldsItAndPlayCarriesOn() {
        val gate = CountDownLatch(1)
        val out = CountingOutput(gate)
        val p = player({ fixture("tone.wav") }, out)
        p.play()
        p.pause()
        assertFalse(p.state.value.playing)
        p.play()
        assertTrue(p.state.value.playing)
        gate.countDown()
        p.await { !it.playing }
        assertTrue(out.frames > 3_000)
    }

    @Test
    fun seekWhileStoppedStartsFromThere() {
        val out = CountingOutput()
        val p = player({ fixture("tone.wav") }, out)
        p.seek(200)
        assertEquals(200, p.state.value.positionMs)
        p.play()
        p.await { !it.playing }
        // Half of the 0.4 s at 8 kHz was skipped.
        assertTrue(out.frames in 1_500L..1_700L, "played ${out.frames} frames")
    }

    @Test
    fun aDownloadThatFailsSaysSo() {
        val p = player({ error("the dashboard answered 401") }, CountingOutput())
        p.play()
        val failed = p.await { it.failed }
        assertFalse(failed.playing)
        assertEquals("Couldn't download", failed.problem)
    }

    @Test
    fun aFormatItCantDecodeSaysSo() {
        val p = player({ fixture("tone.flac") }, CountingOutput())
        p.play()
        assertEquals("Can't play FLAC", p.await { it.failed }.problem)
    }

    @Test
    fun noSoundOutputSaysSo() {
        val none = object : SoundOutput by CountingOutput() {
            override fun open(sampleRate: Int, channels: Int) = throw IllegalArgumentException("No line matching")
        }
        val p = player({ fixture("tone.mp3") }, none)
        p.play()
        assertEquals("No sound output", p.await { it.failed }.problem)
    }

    @Test
    fun aFailedSoundCanBeTriedAgain() {
        var tries = 0
        val p = player({ if (tries++ == 0) error("offline") else fixture("tone.wav") }, CountingOutput())
        p.play()
        p.await { it.failed }
        p.play()
        val end = p.await { !it.playing && !it.failed }
        assertEquals(null, end.problem)
        assertEquals(2, tries)
    }
}
