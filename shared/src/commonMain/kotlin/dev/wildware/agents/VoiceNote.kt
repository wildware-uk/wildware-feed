package dev.wildware.agents

/** The decisions behind a voice note: where it goes, whether it is worth sending, and what its message says. */
object VoiceNote {
    /** Shorter than this, a press was a tap on the button rather than something said. */
    const val MinimumSeconds = 0.6

    /** The dashboard refuses longer message bodies. */
    const val MaxBodyLength = 10_000

    const val NoWordsCaption = "Voice note (no words picked up)"

    const val Filename = "voice-note.m4a"
    const val MimeType = "audio/mp4"

    private val whitespace = Regex("\\s+")

    fun isLongEnough(seconds: Double): Boolean = seconds >= MinimumSeconds

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

    /** The message posted with the recording: what was said, as one tidy paragraph, or a caption when nothing was. */
    fun messageBody(transcript: List<String>): String {
        val text = transcript.joinToString(" ").trim().replace(whitespace, " ")
        return when {
            text.isEmpty() -> NoWordsCaption
            text.length > MaxBodyLength -> text.take(MaxBodyLength - 1).trimEnd() + "…"
            else -> text
        }
    }
}
