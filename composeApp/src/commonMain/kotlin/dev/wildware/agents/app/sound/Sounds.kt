package dev.wildware.agents.app.sound

import dev.wildware.agents.TextStore
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's sound effects: the "agent voice", made by `tools/sounds/agentvoice.py`. This is the one
 * list of the sounds the app plays: each is `files/sounds/<file>.wav` in the Compose resources. The
 * iPhone app bundles the ones its pushed alerts name (`iosApp/Sounds`, picked by the server's
 * `Apns.soundFor`), and Android's notification channels carry `res/raw/<file>.ogg` copies of the
 * ones they use.
 *
 * Two kinds:
 * - the grid, for an update: one sound per priority and level ([grid]);
 * - moments, for things that happen in the app: a question, a mention, a card done.
 *
 * The old sounds' names (`question`, `message`, `done`, `error`, `critical`) now hold the moments
 * that replace them. An older iPhone app has only those five files, so a push naming one of them
 * still plays that app's own sound; any other name plays the system's default there.
 *
 * [rank] decides which one plays when several happen at once: the highest. A [critical] one plays
 * through the device's quiet settings where it can. An [own] one answers what the owner just did,
 * so it plays while they use the app.
 */
enum class Sound(val file: String, val rank: Int, val critical: Boolean = false, val own: Boolean = false) {
    /** An agent asks the owner something. */
    Question("question", 79),

    /** The owner answered a question. */
    Answered("answered", 60, own = true),

    /** A message to the owner: a reply to them, or a line in a thread they follow. */
    Message("message", 50),

    /** Someone else marked a card done. */
    Done("done", 55),

    /** A line names the owner: `@you`, `@owner` or their handle. */
    Mention("mention", 70),

    /** An agent asks to join: allow or deny. */
    Permission("permission", 80),

    /** A question marked critical. */
    Critical("critical", 100, critical = true),

    /** Someone else reacted to the owner's line. */
    Reaction("reaction", 40),

    /** Something the owner sent with files reached the dashboard. */
    Uploaded("uploaded", 45, own = true),

    /** The dashboard refused something the owner sent, for good. */
    Error("error", 75, own = true),

    LowInfo("low-info", 11),
    LowSuccess("low-success", 12),
    LowWarning("low-warning", 13),
    LowError("low-error", 14),
    MediumInfo("medium-info", 31),
    MediumSuccess("medium-success", 32),
    MediumWarning("medium-warning", 33),
    MediumError("medium-error", 34),
    HighInfo("high-info", 61),
    HighSuccess("high-success", 62),
    HighWarning("high-warning", 63),
    HighError("high-error", 64),
    CriticalInfo("critical-info", 91, critical = true),
    CriticalSuccess("critical-success", 92, critical = true),
    CriticalWarning("critical-warning", 93, critical = true),
    CriticalError("critical-error", 94, critical = true),
    ;

    /** Where the sound is in the Compose resources (`Res.readBytes`). */
    val resource: String get() = "files/sounds/$file.wav"

    companion object {
        /**
         * The grid sound for an update's [priority] ("low", "medium", "high", "critical") and [level]
         * ("info", "success", "warn", "error"). No priority is medium; no level is info.
         */
        fun grid(priority: String?, level: String?): Sound {
            val row = when (priority) {
                "low", "high", "critical" -> priority
                else -> "medium"
            }
            val column = when (level) {
                "success", "error" -> level
                "warn", "warning" -> "warning"
                else -> "info"
            }
            return entries.first { it.file == "$row-$column" }
        }
    }
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
 * silent ringer) only a [Sound.critical] one plays; while the owner is using the app ([activity]),
 * only a critical one or their [Sound.own] feedback.
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
        if (!sound.critical && ((activity.active && !sound.own) || runCatching(quiet).getOrDefault(false))) return
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
