package dev.wildware.agents.app.sound

import dev.wildware.agents.app.resources.Res
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Java Sound, on the computer's default output. Each sound is read and decoded once, on its own
 * thread; each play opens a short-lived clip there, so sounds overlap and nothing waits on the
 * window's thread. A computer with no audio output stays quiet.
 */
class DesktopSoundPlayer : SoundPlayer {
    private class Decoded(val format: AudioFormat, val bytes: ByteArray)

    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "sounds").apply { isDaemon = true } }
    private val decoded = ConcurrentHashMap<Sound, Decoded>()

    @Volatile
    private var broken = false

    init {
        worker.execute { Sound.entries.forEach(::decode) }
    }

    @OptIn(ExperimentalResourceApi::class)
    private fun decode(sound: Sound): Decoded? = decoded[sound] ?: runCatching {
        val file = runBlocking { Res.readBytes(sound.resource) }
        AudioSystem.getAudioInputStream(ByteArrayInputStream(file)).use { stream ->
            Decoded(stream.format, stream.readAllBytes())
        }
    }.getOrNull()?.also { decoded[sound] = it }

    override fun play(sound: Sound) {
        if (broken) return
        worker.execute {
            val audio = decode(sound) ?: return@execute
            runCatching {
                val clip = AudioSystem.getClip()
                clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) clip.close() }
                clip.open(audio.format, audio.bytes, 0, audio.bytes.size)
                clip.start()
            }.onFailure { failure ->
                // No mixer at all (a server, a container): stop trying. A busy device may free up.
                if (failure is IllegalArgumentException) broken = true
            }
        }
    }
}
