package dev.wildware.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the dashboard looks like right now, in the small shape widgets, Siri and Live Activities need. */
@Serializable
data class DashboardSummary(
    val at: String,
    val onlineAgents: List<OnlineAgent>,
    val requests: List<PendingRequest>,
    val work: WorkCounts,
    val projects: List<ProjectRef>,
)

@Serializable
data class OnlineAgent(
    val agentId: String,
    val name: String,
    val host: String? = null,
    val cwd: String? = null,
)

/** A question an agent is stopped on. [DashboardSummary.requests] lists them oldest first. */
@Serializable
data class PendingRequest(
    val id: String,
    val agentName: String,
    val projectSlug: String? = null,
    val projectName: String? = null,
    val question: String,
    val kind: RequestKind,
    val options: List<String>? = null,
    val expiresAt: String,
)

@Serializable
enum class RequestKind {
    @SerialName("text") Text,
    @SerialName("confirm") Confirm,
    @SerialName("buttons") Buttons,
    @SerialName("choice") Choice,
    @SerialName("multi_choice") MultiChoice,
    @SerialName("form") Form;

    val wireName: String get() = serializer().descriptor.getElementName(ordinal)

    companion object {
        fun fromWire(value: String): RequestKind? = entries.firstOrNull { it.wireName == value }
    }
}

@Serializable
data class WorkCounts(val todo: Int, val inProgress: Int, val blocked: Int)

@Serializable
data class ProjectRef(val slug: String, val name: String)
