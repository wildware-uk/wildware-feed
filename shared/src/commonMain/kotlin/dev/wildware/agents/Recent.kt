package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * What agents said lately, ready for Spotlight, Siri and the project widget: each line already
 * named (project slug and name, agent name) and turned from markdown into plain text.
 */
data class RecentActivity(
    val projects: List<ProjectRef>,
    /** Newest first. */
    val updates: List<RecentUpdate>,
    /** Agents' own messages only, newest first. */
    val messages: List<RecentMessage>,
)

data class RecentUpdate(
    val id: String,
    val project: ProjectRef?,
    val agentName: String,
    val title: String?,
    val text: String,
    /** Milliseconds since the epoch. */
    val createdAt: Long,
) {
    /** The line a card leads with: its title, or failing that its first sentence. */
    val headline: String get() = title?.takeIf { it.isNotBlank() } ?: PlainText.firstSentence(text).ifEmpty { "An update" }

    /**
     * Sentences of [text] already said by [headline]: the first, when there is no title or the
     * body opens by repeating it, as bodies often do. Skip them so nothing is read twice.
     */
    val sentencesInHeadline: Int
        get() {
            val title = title?.trim()?.takeIf { it.isNotEmpty() } ?: return 1
            return if (PlainText.firstSentence(text).trimEnd('.').equals(title.trimEnd('.'), ignoreCase = true)) 1 else 0
        }
}

data class RecentMessage(
    val id: String,
    val project: ProjectRef?,
    val agentName: String,
    val text: String,
    val createdAt: Long,
)

/** The parts of `GET /api/snapshot` this app reads. The server sends much more. */
@Serializable
internal data class SnapshotBody(
    val projects: List<Project>,
    val agentNames: Map<String, String> = emptyMap(),
    val updates: Updates,
    val messages: List<Message> = emptyList(),
) {
    @Serializable
    data class Project(val id: String, val slug: String, val name: String, val status: String = "active")

    @Serializable
    data class Updates(val items: List<Update>)

    @Serializable
    data class Update(
        val id: String,
        val projectId: String,
        val agentId: String,
        val title: String? = null,
        val body: String,
        val createdAt: Long,
        val deletedAt: Long? = null,
    )

    @Serializable
    data class Message(
        val id: String,
        val projectId: String? = null,
        val author: String,
        val body: String,
        val createdAt: Long,
        val deletedAt: Long? = null,
    )

    fun toActivity(): RecentActivity {
        val byId = projects.associate { it.id to ProjectRef(it.slug, it.name) }
        fun agent(id: String) = agentNames[id] ?: "An agent"
        return RecentActivity(
            projects = projects.filter { it.status == "active" }.map { ProjectRef(it.slug, it.name) },
            updates = updates.items
                .filter { it.deletedAt == null }
                .map { RecentUpdate(it.id, byId[it.projectId], agent(it.agentId), it.title, PlainText.fromMarkdown(it.body), it.createdAt) }
                .sortedByDescending { it.createdAt },
            messages = messages
                .filter { it.deletedAt == null && it.author.startsWith("agent:") }
                .map { RecentMessage(it.id, it.projectId?.let(byId::get), agent(it.author.removePrefix("agent:")), PlainText.fromMarkdown(it.body), it.createdAt) }
                .sortedByDescending { it.createdAt },
        )
    }
}
