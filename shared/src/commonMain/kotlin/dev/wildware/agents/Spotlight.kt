package dev.wildware.agents

/** One thing Spotlight can find. [id] is the dashboard page it opens, so a tap needs no lookup. */
data class SpotlightEntry(
    val id: String,
    val title: String,
    val text: String,
    val keywords: List<String>,
    /** Milliseconds since the epoch, or null for a project. */
    val createdAt: Long?,
)

/** What the phone's search should know about: every active project and the latest updates in them. */
object Spotlight {
    private const val MaxText = 1000

    fun entries(activity: RecentActivity): List<SpotlightEntry> {
        val live = activity.projects.associateBy { it.slug }
        val projects = activity.projects.map { project ->
            val latest = activity.updates.firstOrNull { it.project?.slug == project.slug }
            SpotlightEntry(
                id = projectPath(project.slug),
                title = project.name,
                text = latest?.let { "${it.agentName}: ${it.headline}" } ?: "A project in Wildware Feed",
                keywords = listOf(project.name, project.slug, "agents"),
                createdAt = null,
            )
        }
        // An update in a project that's gone or archived isn't worth finding any more.
        val updates = activity.updates.mapNotNull { update ->
            val project = update.project?.let { live[it.slug] } ?: return@mapNotNull null
            SpotlightEntry(
                id = "${projectPath(project.slug)}?focus=${update.id}",
                title = update.headline,
                text = PlainText.clip("${update.agentName} in ${project.name}: ${update.text.replace('\n', ' ')}", MaxText),
                keywords = listOf(project.name, update.agentName),
                createdAt = update.createdAt,
            )
        }
        return projects + updates
    }

    /** What was indexed before but isn't now, so Spotlight stops offering it. */
    fun removed(previous: List<String>, current: List<SpotlightEntry>): List<String> {
        val keep = current.mapTo(HashSet()) { it.id }
        return previous.filter { it !in keep }
    }

    private fun projectPath(slug: String) = "/projects/$slug"
}
