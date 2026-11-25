package dev.wildware.agents

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

/**
 * A pull request linked to a card (server migration 079, docs/GITHUB.md). [state] is "open",
 * "draft", "merged" or "closed", null before GitHub has said; [ci] "none", "pending", "passing" or
 * "failing"; [review] "none", "approved", "changes_requested" or "commented". [owner] is the agent
 * (`agent:<id>`) told when CI fails.
 */
@Serializable
data class PrView(
    val repo: String = "",
    val number: Long = 0,
    val url: String = "",
    val title: String? = null,
    val state: String? = null,
    val ci: String = "none",
    val failing: List<PrCheckView> = emptyList(),
    val review: String = "none",
    val changesRequested: Int = 0,
    val owner: String? = null,
    val ownerName: String? = null,
) {
    /** Still open (or a draft, or not heard of yet). */
    val open: Boolean get() = state == null || state == "open" || state == "draft"

    /** Someone should look: CI is red, or a reviewer asked for changes, while it's open. */
    val needsAttention: Boolean get() = open && (ci == "failing" || review == "changes_requested")

    /** How it looks on the card: the worst thing first. */
    val tone: PrTone get() = when {
        state == "merged" -> PrTone.Merged
        state == "closed" -> PrTone.Closed
        needsAttention -> PrTone.Bad
        ci == "pending" -> PrTone.Waiting
        ci == "passing" || review == "approved" -> PrTone.Good
        else -> PrTone.Plain
    }

    /** The chip's words: "#423 · CI failing · 1 change requested". */
    val label: String get() = (listOf("#$number") + parts()).joinToString(" · ")

    /** What a screen reader says: "Pull request 423 in acme/app: CI failing, 1 change requested. Opens on GitHub." */
    val spoken: String get() = "Pull request $number in $repo" + parts().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ").orEmpty() + ". Opens on GitHub."

    private fun parts(): List<String> = buildList {
        when (state) {
            "merged" -> { add("Merged"); return@buildList }
            "closed" -> { add("Closed"); return@buildList }
            "draft" -> add("Draft")
        }
        when (ci) {
            "failing" -> add("CI failing")
            "pending" -> add("CI running")
            "passing" -> add("CI passing")
        }
        when {
            changesRequested > 1 -> add("$changesRequested changes requested")
            changesRequested == 1 || review == "changes_requested" -> add("1 change requested")
            review == "approved" -> add("Approved")
        }
    }
}

@Serializable
data class PrCheckView(val name: String = "", val url: String? = null)

/** The chip's colour: red for attention, amber while CI runs, green when good, purple merged. */
enum class PrTone { Bad, Waiting, Good, Merged, Closed, Plain }

/** The team's GitHub, as `GET /api/github` answers: whether the server has it, and what's linked. */
@Serializable
data class GitHubStatus(
    val available: Boolean = false,
    val installations: List<GitHubInstallation> = emptyList(),
    val canManage: Boolean = false,
)

/** An installation of the GitHub App this team linked: [account] is the user or org it's on. */
@Serializable
data class GitHubInstallation(val installationId: Long = 0, val account: String = "", val addedAt: Long = 0)

@Serializable
internal data class GitHubConnectAnswer(val url: String = "")

@Throws(Exception::class)
suspend fun DashboardClient.githubStatus(): GitHubStatus = http.get("api/github") { authorise() }.decoded()

/** GitHub's install page for the app, to open in the browser. Owners only. */
@Throws(Exception::class)
suspend fun DashboardClient.connectGitHub(): String =
    http.post("api/github/connect") {
        authorise()
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { })
    }.decoded<GitHubConnectAnswer>().url

/** Unlinks an installation from this team (it stays installed on GitHub). Owners only. */
@Throws(Exception::class)
suspend fun DashboardClient.disconnectGitHub(installationId: Long) {
    http.delete("api/github/installations/$installationId") { authorise() }.requireSuccess()
}
