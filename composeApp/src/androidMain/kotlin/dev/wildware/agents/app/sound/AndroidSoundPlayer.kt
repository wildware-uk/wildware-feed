package dev.wildware.agents.app.sound

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import dev.wildware.agents.app.resources.Res
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * A [SoundPool], loaded once off the main thread. Sounds play as notification sounds, so the
 * phone's notification volume, silent and vibrate modes and Do Not Disturb all apply; a critical
 * one plays as an alarm, which gets through. Only while the app is on screen ([onScreen]): away
 * from it, the notification channels carry the sounds.
 */
class AndroidSoundPlayer(private val context: Context, private val onScreen: () -> Boolean) : SoundPlayer {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sounds").apply { isDaemon = true } }
    private val normal = pool(AudioAttributes.USAGE_NOTIFICATION_EVENT)
    private val alarm = pool(AudioAttributes.USAGE_ALARM)
    private val ids = ConcurrentHashMap<Sound, Int>()

    init {
        worker.execute { Sound.entries.forEach(::load) }
    }

    private fun pool(usage: Int): SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()

    /** SoundPool reads files, so each sound is written to the cache folder: afresh each start, as an update may bring new ones. */
    @OptIn(ExperimentalResourceApi::class)
    private fun load(sound: Sound) {
        runCatching {
            val file = File(context.cacheDir, "sounds/${sound.file}.wav")
            file.parentFile?.mkdirs()
            file.writeBytes(runBlocking { Res.readBytes(sound.resource) })
            ids[sound] = (if (sound == Sound.Critical) alarm else normal).load(file.path, 1)
        }
    }

    /** The phone's own quiet: a silent or vibrating ringer, or Do Not Disturb. */
    fun quiet(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        return audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            (notifications != null && notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    override fun play(sound: Sound) {
        if (!onScreen()) return
        worker.execute {
            val id = ids[sound] ?: return@execute
            (if (sound == Sound.Critical) alarm else normal).play(id, 1f, 1f, 1, 0, 1f)
        }
    }
}
