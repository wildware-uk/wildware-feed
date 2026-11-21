package dev.wildware.agents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The decisions behind a voice note: where it goes, whether it is worth sending, and what its text says. */
object VoiceNote {
    /** Shorter than this, a press was a tap on the button rather than something said. */
    const val MinimumSeconds = 0.6

    /** How a message says it was a voice note, on the dashboard: its `source`. */
    const val Source = "voice"

    /** How long a voice note sent on release waits, showing Undo, before it goes. */
    const val UndoMillis = 4_000L

    /** The dashboard refuses longer message bodies. */
    const val MaxBodyLength = 10_000

    private val whitespace = Regex("\\s+")

    fun isLongEnough(seconds: Double): Boolean = seconds >= MinimumSeconds

    /**
     * Whether letting go of the mic sends the note at once (with a short Undo), rather than putting
     * the words in the box to check. Only where the box can send a voice note itself, and only
     * while the owner hasn't asked to review them.
     */
    fun sendsOnRelease(review: Boolean, canSendHere: Boolean): Boolean = canSendHere && !review

    /**
     * The project a note goes to: the one picked by hand, else the one open on screen,
     * else the last one used, else the first there is. Slugs that no longer exist are skipped.
     */
    fun destination(picked: String?, path: String?, lastUsed: String?, projects: List<ProjectRef>): ProjectRef? =
        listOfNotNull(picked, path?.let(::projectSlug), lastUsed)
            .firstNotNullOfOrNull { slug -> projects.find { it.slug == slug } }
            ?: projects.firstOrNull()

    /** The project a dashboard page belongs to: `/projects/garden-game?focus=x` is `garden-game`. */
    fun projectSlug(path: String): String? {
        val segments = path.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        return segments.getOrNull(1)?.takeIf { segments[0] == "projects" }
    }

    /** What was said, as one tidy paragraph, or null when no words were picked up. */
    fun text(transcript: List<String>): String? {
        val text = transcript.joinToString(" ").trim().replace(whitespace, " ")
        return when {
            text.isEmpty() -> null
            text.length > MaxBodyLength -> text.take(MaxBodyLength - 1).trimEnd() + "…"
            else -> text
        }
    }
}

/**
 * "Review voice notes before sending", on this device: off unless turned on. Off, letting go of the
 * mic sends the note with a few seconds to Undo it; on, the words go into the box to check first.
 */
class VoiceReviewSetting(private val store: TextStore) {
    private val state = MutableStateFlow(runCatching { store.read() }.getOrNull()?.trim() == On)

    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        if (state.value == on) return
        state.value = on
        runCatching { store.write(if (on) On else Off) }
    }

    private companion object {
        const val On = "on"
        const val Off = "off"
    }
}
