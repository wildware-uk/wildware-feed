package dev.wildware.agents.app.sound

import dev.wildware.agents.app.resources.Res
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategorySoloAmbient
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * AVAudioPlayer, two preloaded per sound so a sound can overlap itself. The app's audio is
 * "ambient": it mixes with the owner's music rather than stopping it, and the silent switch
 * silences it. A critical sound plays through the silent switch, still mixed with what else plays.
 * Everything runs on the main thread, where AVFoundation wants it, and none of it waits there.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalResourceApi::class)
class IosSoundPlayer : SoundPlayer {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val players = HashMap<Sound, List<AVAudioPlayer>>()

    /** Whether the app's audio is ours to set: nothing else (a video, a voice note) has set it. */
    private var ambient = false

    init {
        main.launch {
            val session = AVAudioSession.sharedInstance()
            if (session.category == AVAudioSessionCategorySoloAmbient) {
                ambient = session.setCategory(AVAudioSessionCategoryAmbient, error = null)
            }
            for (sound in Sound.entries) {
                val bytes = runCatching { Res.readBytes(sound.resource) }.getOrNull() ?: continue
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                players[sound] = List(2) { AVAudioPlayer(data = data, error = null) }.filterNotNull().onEach { it.prepareToPlay() }
            }
        }
    }

    override fun play(sound: Sound) {
        main.launch {
            val choices = players[sound] ?: return@launch
            val player = choices.firstOrNull { !it.playing } ?: choices.first()
            val loud = sound.critical && ambient && AVAudioSession.sharedInstance().category == AVAudioSessionCategoryAmbient
            if (loud) {
                AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, withOptions = AVAudioSessionCategoryOptionMixWithOthers, error = null)
                AVAudioSession.sharedInstance().setActive(true, error = null)
            }
            player.stop()
            player.currentTime = 0.0
            player.play()
            if (loud) {
                delay((player.duration * 1000).toLong() + 300)
                if (AVAudioSession.sharedInstance().category == AVAudioSessionCategoryPlayback) {
                    AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, error = null)
                }
            }
        }
    }
}
