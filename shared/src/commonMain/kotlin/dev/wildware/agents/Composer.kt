package dev.wildware.agents

/** Where the composer bar's words go. The chip above the bar shows [label]. */
sealed class ComposerTarget {
    abstract val label: String

    /**
     * The thread [DashboardClient.send] posts to. Null for [PageTextBox], whose words are written into
     * the page for the owner to check and send there.
     */
    abstract val thread: Map<String, String>?

    /** A new post in a project's feed. */
    data class Project(val project: ProjectRef) : ComposerTarget() {
        override val label get() = "To ${project.name}"
        override val thread get() = mapOf("project" to project.slug)
    }

    /** Talking over a question an agent asked, in that question's own thread, without answering it. */
    data class Reply(val requestId: String, val agentName: String) : ComposerTarget() {
        override val label get() = "Reply to $agentName"
        override val thread get() = mapOf("request" to requestId)
    }

    /** The text box the owner was using on the page: a reply, an answer, the feed's own composer. */
    data class PageTextBox(val placeholder: String?) : ComposerTarget() {
        override val label: String
            get() = placeholder?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "Into “${Composer.shorten(it)}”" }
                ?: "Into the page's text box"
        override val thread: Map<String, String>? get() = null
    }
}

/** The decisions behind the composer bar. */
object Composer {
    private const val MaxLabelLength = 28

    /**
     * Where the bar sends right now. What the owner chose by hand wins (an Inbox Reply, a project
     * picked on the chip); then the page's text box while it is there; then the project on screen,
     * else the last used, else the first. A chosen project that no longer exists falls through.
     */
    fun target(
        chosen: ComposerTarget?,
        pageBox: ComposerTarget.PageTextBox?,
        path: String?,
        lastUsed: String?,
        projects: List<ProjectRef>,
    ): ComposerTarget? {
        when (chosen) {
            is ComposerTarget.Reply -> return chosen
            is ComposerTarget.Project -> projects.find { it.slug == chosen.project.slug }?.let { return ComposerTarget.Project(it) }
            is ComposerTarget.PageTextBox -> if (pageBox != null) return pageBox
            null -> {}
        }
        if (pageBox != null) return pageBox
        return VoiceNote.destination(picked = null, path = path, lastUsed = lastUsed, projects = projects)
            ?.let { ComposerTarget.Project(it) }
    }

    /** Photos go up with a post or a reply; the page's text box can only take words. */
    fun acceptsPhotos(target: ComposerTarget?): Boolean = target != null && target !is ComposerTarget.PageTextBox

    fun canSend(text: String, photoCount: Int, target: ComposerTarget?): Boolean = when {
        target == null -> false
        text.isNotBlank() -> true
        else -> photoCount > 0 && acceptsPhotos(target)
    }

    /** Dictated words join what is already typed, with one space between. */
    fun appendDictation(existing: String, dictated: String): String {
        val words = dictated.trim()
        if (words.isEmpty()) return existing
        if (existing.isBlank()) return words
        return if (existing.last().isWhitespace()) existing + words else "$existing $words"
    }

    internal fun shorten(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MaxLabelLength) oneLine else oneLine.take(MaxLabelLength - 1).trimEnd() + "…"
    }
}
