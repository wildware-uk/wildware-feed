package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * How long the dashboard keeps media: [days], or null to keep it forever, for the [kinds] chosen.
 * Once a night the dashboard deletes chosen media older than that, except on pinned cards and open
 * requests. The owner sets it today; with teams it will be an admin's.
 */
@Serializable
data class MediaRetention(
    val days: Int? = null,
    val kinds: List<String> = AllKinds,
) {
    companion object {
        const val Image = "image"
        const val Video = "video"
        const val Audio = "audio"
        const val File = "file"

        /** Every kind, in the order the dashboard keeps them and the settings show them. */
        val AllKinds = listOf(Image, Video, Audio, File)

        /** The lengths offered as one tap; anything else is "Custom…". */
        val Presets = listOf(7, 14, 30, 90)

        const val MinDays = 1
        const val MaxDays = 3650

        /** "Forever", "1 day", "30 days". */
        fun label(days: Int?): String = when (days) {
            null -> "Forever"
            1 -> "1 day"
            else -> "$days days"
        }
    }
}

/** What the "Keep media" row has picked: forever, one of the presets, or a number typed in. */
sealed interface RetentionPeriod {
    data object Forever : RetentionPeriod
    data class Preset(val days: Int) : RetentionPeriod
    data object Custom : RetentionPeriod
}

/**
 * The "Keep media" settings as the owner edits them, before they are saved. [saved] is what the
 * dashboard has now; the rest is the draft. [confirming] is the "deleted for good" step, shown
 * before a save that turns removal on or makes it reach further.
 */
data class RetentionForm(
    val saved: MediaRetention,
    val period: RetentionPeriod,
    val customDays: String = "",
    val kinds: List<String> = saved.kinds,
    val confirming: Boolean = false,
) {
    /** The number of days the draft keeps media for, or null for forever (or a custom number that isn't one). */
    val days: Int? get() = when (period) {
        RetentionPeriod.Forever -> null
        is RetentionPeriod.Preset -> period.days
        RetentionPeriod.Custom -> customDays.trim().toIntOrNull()
    }

    /** What is wrong with the draft, said plainly, or null when it can be saved. */
    val problem: String? get() = when {
        period == RetentionPeriod.Custom && days.let { it == null || it !in MediaRetention.MinDays..MediaRetention.MaxDays } ->
            "Choose a number of days from ${MediaRetention.MinDays} to ${MediaRetention.MaxDays}."
        kinds.isEmpty() -> "Choose at least one kind of media."
        else -> null
    }

    /** The setting to send, or null while the draft has a [problem]. Kinds go in the dashboard's order. */
    val chosen: MediaRetention? get() =
        if (problem != null) null else MediaRetention(days, MediaRetention.AllKinds.filter { it in kinds })

    /** The draft differs from what is saved, and is valid. */
    val changed: Boolean get() = chosen?.let { it != saved.normalised() } == true

    /**
     * Saving would delete media that is kept today: removal turns on, gets shorter, or takes in a
     * kind it left alone. That is asked about first, because it can't be undone.
     */
    val deletesMore: Boolean get() {
        val next = chosen ?: return false
        val days = next.days ?: return false
        val before = saved.days ?: return true
        return days < before || next.kinds.any { it !in saved.kinds }
    }

    /** "Media older than 30 days will be deleted for good, each night. …" */
    val warning: String? get() = chosen?.days?.let { retentionWarning(it) }

    fun choose(period: RetentionPeriod): RetentionForm = copy(period = period, confirming = false)

    /** Typing into the custom box: digits only, four at most. */
    fun typeCustom(text: String): RetentionForm =
        copy(period = RetentionPeriod.Custom, customDays = text.filter { it.isDigit() }.take(4), confirming = false)

    /** Whether [kind] can be switched: the last one ticked has to stay ticked. */
    fun canToggle(kind: String): Boolean = kind !in kinds || kinds.size > 1

    fun toggle(kind: String): RetentionForm {
        if (!canToggle(kind)) return this
        val next = if (kind in kinds) kinds - kind else MediaRetention.AllKinds.filter { it in kinds || it == kind }
        return copy(kinds = next, confirming = false)
    }

    companion object {
        /** A fresh draft of [saved]: a length that isn't a preset shows as "Custom…" with its number. */
        fun of(saved: MediaRetention): RetentionForm {
            val days = saved.days
            return when {
                days == null -> RetentionForm(saved, RetentionPeriod.Forever)
                days in MediaRetention.Presets -> RetentionForm(saved, RetentionPeriod.Preset(days))
                else -> RetentionForm(saved, RetentionPeriod.Custom, customDays = days.toString())
            }
        }
    }
}

/** What the confirm step says before media starts going. */
fun retentionWarning(days: Int): String =
    "Media older than ${MediaRetention.label(days)} will be deleted for good, each night. Pinned cards and open requests keep theirs."

/** What shows where removed media was. */
fun removedMediaLabel(afterDays: Int): String = "Media removed after ${MediaRetention.label(afterDays)}"

private fun MediaRetention.normalised() = copy(kinds = MediaRetention.AllKinds.filter { it in kinds })
