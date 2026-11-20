package dev.wildware.agents.app.feed.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where decoded samples go: the computer's speakers, or in a test somewhere that only counts them. */
interface SoundOutput : AutoCloseable {
    /** Gets ready for samples at [sampleRate] with [channels]; throws when nothing here can take them. */
    fun open(sampleRate: Int, channels: Int)

    /** Queues [samples] to play, waiting while the queue is full or the output is paused. */
    fun write(samples: ShortArray)

    /** How many frames have actually been heard since [open]. */
    val framesPlayed: Long

    fun pause()

    fun resume()

    /** Waits for everything queued to be heard. */
    fun drain()

    /** Drops anything queued and lets a waiting [write] go: the sound is being stopped. */
    override fun close()
}

/** The default output, through Java Sound: the same route the app's own little sounds take. */
class JavaSoundOutput : SoundOutput {
    @Volatile
    private var line: SourceDataLine? = null
    private var bytes = ByteArray(0)

    override fun open(sampleRate: Int, channels: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val opened = AudioSystem.getSourceDataLine(format)
        // About a quarter of a second queued: enough not to stutter, little enough to pause promptly.
        opened.open(format, (sampleRate / 4) * channels * 2)
        opened.start()
        line = opened
    }

    override fun write(samples: ShortArray) {
        val l = line ?: return
        if (bytes.size < samples.size * 2) bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        var at = 0
        val total = samples.size * 2
        while (at < total && l.isOpen) {
            val n = l.write(bytes, at, total - at)
            if (n <= 0 && !l.isActive) Thread.sleep(10)
            at += n
        }
    }

    override val framesPlayed: Long get() = line?.longFramePosition ?: 0

    override fun pause() {
        line?.stop()
    }

    override fun resume() {
        line?.start()
    }

    override fun drain() {
        line?.drain()
    }

    override fun close() {
        val l = line ?: return
        line = null
        runCatching { l.stop() }
        runCatching { l.flush() }
        runCatching { l.close() }
    }
}
