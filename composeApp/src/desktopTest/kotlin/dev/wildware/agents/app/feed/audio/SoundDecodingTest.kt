package dev.wildware.agents.app.feed.audio

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Each kind of sound an agent attaches decodes on a desktop, with no sound device needed: the
 * fixtures are a 440 Hz tone, made with ffmpeg, 0.4 s long (and one of 0.1 s, the shortest in use).
 */
class SoundDecodingTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sounds/$name")) { "no fixture $name" }.use { it.readBytes() }

    private class Decoded(val rate: Int, val channels: Int, val ms: Long, val loudest: Int)

    private fun decode(name: String): Decoded = SoundDecoding.open(fixture(name)).use { source ->
        var frames = 0L
        var loudest = 0
        while (true) {
            val block = source.read() ?: break
            frames += block.size / source.channels
            block.forEach { loudest = maxOf(loudest, abs(it.toInt())) }
        }
        Decoded(source.sampleRate, source.channels, frames * 1000 / source.sampleRate, loudest)
    }

    private fun assertTone(name: String, format: SoundFormat, rate: Int, channels: Int, ms: LongRange) {
        assertEquals(format, SoundDecoding.formatOf(fixture(name)), name)
        val decoded = decode(name)
        assertEquals(rate, decoded.rate, "$name sample rate")
        assertEquals(channels, decoded.channels, "$name channels")
        assertTrue(decoded.ms in ms, "$name lasted ${decoded.ms} ms, wanted $ms")
        // A real tone, not silence: ffmpeg's sine peaks at an eighth of full scale, about 4,100.
        assertTrue(decoded.loudest > 2_000, "$name peaked at ${decoded.loudest}")
    }

    @Test
    fun mp3Decodes() = assertTone("tone.mp3", SoundFormat.Mp3, 22_050, 1, 380L..520L)

    @Test
    fun stereoMp3WithAnId3TagDecodes() = assertTone("stereo.mp3", SoundFormat.Mp3, 44_100, 2, 380L..520L)

    @Test
    fun aTenthOfASecondMp3Decodes() = assertTone("short.mp3", SoundFormat.Mp3, 22_050, 1, 80L..200L)

    // JAAD gives mono AAC back as two matching channels.
    @Test
    fun m4aDecodes() = assertTone("tone.m4a", SoundFormat.Mp4, 22_050, 2, 380L..520L)

    @Test
    fun rawAacDecodes() = assertTone("tone.aac", SoundFormat.Adts, 22_050, 2, 380L..520L)

    @Test
    fun oggVorbisDecodes() = assertTone("tone.ogg", SoundFormat.Vorbis, 22_050, 1, 380L..520L)

    @Test
    fun opusDecodes() = assertTone("tone.opus", SoundFormat.Opus, 48_000, 1, 380L..440L)

    @Test
    fun wavDecodes() = assertTone("tone.wav", SoundFormat.Wave, 8_000, 1, 390L..410L)

    @Test
    fun durationIsMeasuredByDecoding() {
        assertTrue(SoundDecoding.durationMs(fixture("tone.ogg")) in 380L..420L)
    }

    @Test
    fun flacSaysItCantPlay() {
        assertEquals(SoundFormat.Flac, SoundDecoding.formatOf(fixture("tone.flac")))
        val e = assertFailsWith<UnplayableSound> { SoundDecoding.open(fixture("tone.flac")) }
        assertEquals("Can't play FLAC", e.message)
    }

    @Test
    fun somethingThatIsNoSoundSaysItCantPlay() {
        val e = assertFailsWith<UnplayableSound> { SoundDecoding.open("<html>not found</html>".encodeToByteArray()) }
        assertEquals("Can't play this kind of sound", e.message)
    }

    @Test
    fun aFileCutOffBeforeAnySoundSaysItCantPlay() {
        assertFailsWith<UnplayableSound> { SoundDecoding.open(fixture("tone.mp3").copyOf(12)) }
    }

    /** The AAC decoder adds itself to Java Sound; the app's own sounds, read there, must still be WAV. */
    @Test
    fun theAppsOwnSoundsStillReadAsWav() {
        val sounds = File("src/commonMain/composeResources/files/sounds").listFiles { f -> f.extension == "wav" }.orEmpty()
        assertTrue(sounds.isNotEmpty())
        for (sound in sounds) {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(sound.readBytes())).use { stream ->
                assertTrue(stream.format.encoding.toString().startsWith("PCM"), "${sound.name} read as ${stream.format}")
            }
        }
    }
}
