package dev.wildware.agents.app.sound

import dev.wildware.agents.TextStore
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's sound effects (made with ElevenLabs), one per kind of moment. This is the one list of
 * them: each is `files/sounds/<file>.wav` in the Compose resources, and the iPhone app bundles the
 * same `<file>.wav` for its pushed alerts (the server names it: `Apns.soundFor`). Android's
 * notification channels carry `res/raw/<file>.ogg` copies of [Question], [Message] and [Critical].
 *
 * Sends, ticks and votes have no sound. None but [Critical] plays while the owner is using the
 * app ([OwnerActivity]): they can see it.
 *
 * [rank] decides which one plays when several happen at once: the highest.
 */
enum class Sound(val file: String, val rank: Int) {
    /** An agent asks the owner something, or an agent asks to join. */
    Question("question", 5),

    /** A message to the owner: a reply to them, a mention, or a line in a thread they follow. */
    Message("message", 3),

    /** An agent finished: a card marked done, or a success update. */
    Done("done", 4),

    /** Something went wrong: a send refused for good, or an agent's error update. */
    Error("error", 6),

    /** Something marked critical. Plays through the device's quiet settings where it can. */
    Critical("critical", 7),
    ;

    /** Where the sound is in the Compose resources (`Res.readBytes`). */
    val resource: String get() = "files/sounds/$file.wav"
}

/**
 * Plays sounds on one platform: preloaded once, quick to start, never blocking the caller, and
 * fine to overlap. A player that can't (no audio device) just stays quiet.
 */
interface SoundPlayer {
    fun play(sound: Sound)
}

/**
 * Whether the owner is using the app right now: its window has focus and they touched, clicked,
 * scrolled or typed in it within [idleMillis]. The app's root reports both ([tracksUse]).
 */
class OwnerActivity(
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val idleMillis: Long = 60_000,
) {
    @Volatile private var focused = false
    @Volatile private var lastInput = Long.MIN_VALUE

    /** The window gained or lost focus. Coming to the front is using it. */
    fun focus(on: Boolean) {
        focused = on
        if (on) input()
    }

    /** The owner touched, clicked, scrolled or typed. */
    fun input() {
        lastInput = clock()
    }

    val active: Boolean get() = focused && lastInput != Long.MIN_VALUE && clock() - lastInput < idleMillis
}

/**
 * The owner's "Sounds" switch, kept on this device (on unless turned off), in front of the
 * platform's [player]. Several moments at once make one sound, the most important; the same sound
 * plays at most once every [gapMillis]. While the device asks for [quiet] (Do Not Disturb, a
 * silent ringer), or the owner is using the app ([activity]), only [Sound.Critical] plays.
 */
class SoundBoard(
    private val player: SoundPlayer?,
    private val store: TextStore,
    private val quiet: () -> Boolean = { false },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val gapMillis: Long = 400,
    /** Whether the owner is using the app now: then they see what happens, and hear nothing. */
    val activity: OwnerActivity = OwnerActivity(clock),
) {
    private val state = MutableStateFlow(runCatching { store.read() }.getOrNull()?.trim() != Off)

    /** Whether the app plays its sounds. */
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    /** Whether this device can play them at all; the switch shows only where it can. */
    val available: Boolean get() = player != null

    /** Whether the app itself plays sounds now: the iPhone leaves the sound off alerts shown over the app. */
    val playing: Boolean get() = player != null && state.value

    /** When each sound last played. Only the session's sound loop plays, one at a time. */
    private val lastPlayed = HashMap<Sound, Long>()

    fun set(on: Boolean) {
        if (state.value == on) return
        state.value = on
        runCatching { store.write(if (on) On else Off) }
    }

    /** Plays the most important of [sounds], if any, unless it played a moment ago or sounds are off. */
    fun play(sounds: Collection<Sound>) {
        val sound = sounds.maxByOrNull { it.rank } ?: return
        val player = player ?: return
        if (!state.value) return
        if (sound != Sound.Critical && (activity.active || runCatching(quiet).getOrDefault(false))) return
        val now = clock()
        val last = lastPlayed[sound]
        if (last != null && now - last in 0 until gapMillis) return
        lastPlayed[sound] = now
        runCatching { player.play(sound) }
    }

    fun play(sound: Sound) = play(listOf(sound))

    private companion object {
        const val On = "on"
        const val Off = "off"
    }
}
