package dev.wildware.agents

/** The sizes the project widget comes in, and how much each one has room for. */
enum class CardSize(val questions: Int, val excerptSentences: Int) {
    /** Lock screen: one line or two. */
    Accessory(questions = 0, excerptSentences = 0),
    Small(questions = 0, excerptSentences = 1),
    Medium(questions = 1, excerptSentences = 2),
    Large(questions = 3, excerptSentences = 4),
}

/** What one project's widget shows: its latest news and the questions it is waiting on you for. */
data class ProjectCard(
    val project: ProjectRef,
    val latest: RecentUpdate?,
    /** The update's text after its headline, cut to fit. Empty when there is none. */
    val excerpt: String,
    /** Oldest first, as many as fit. */
    val questions: List<PendingRequest>,
    /** Every question waiting in this project, including ones that didn't fit. */
    val waiting: Int,
) {
    val headline: String get() = latest?.headline ?: "Nothing posted yet"

    /** One short line for the lock screen: what's waiting if anything is, otherwise the news. */
    val statusLine: String
        get() = when (waiting) {
            0 -> headline
            1 -> "1 question waiting"
            else -> "$waiting questions waiting"
        }

    companion object {
        /** Null when the project is gone (deleted or archived) since the widget was set up. */
        fun build(projectSlug: String, summary: DashboardSummary, activity: RecentActivity, size: CardSize): ProjectCard? {
            val project = summary.projects.firstOrNull { it.slug == projectSlug }
                ?: activity.projects.firstOrNull { it.slug == projectSlug }
                ?: return null
            val latest = activity.updates.firstOrNull { it.project?.slug == projectSlug }
            val waiting = summary.requests.filter { it.projectSlug == projectSlug }
            return ProjectCard(
                project = project,
                latest = latest,
                excerpt = latest?.let { excerpt(it, size) }.orEmpty(),
                questions = waiting.take(size.questions),
                waiting = waiting.size,
            )
        }

        private fun excerpt(update: RecentUpdate, size: CardSize): String {
            if (size.excerptSentences == 0) return ""
            return PlainText.sentences(update.text, size.excerptSentences, skip = update.sentencesInHeadline, maxLength = 90 * size.excerptSentences)
        }
    }
}
