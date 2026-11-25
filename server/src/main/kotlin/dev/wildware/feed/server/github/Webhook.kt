package dev.wildware.feed.server.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A webhook delivery read into what it changes (docs/GITHUB.md): GitHub's event name
 * (`X-GitHub-Event`) and its JSON. Anything that changes nothing on a card reads as null.
 */
object Webhook {
    sealed interface Change {
        val installationId: Long
    }

    /** The PR itself changed: opened, edited, pushed to, closed, merged, reopened, drafted. */
    data class Pull(
        override val installationId: Long, val repo: String, val number: Long, val title: String,
        val state: String, val headSha: String?,
    ) : Change

    /**
     * A check run or a commit status on commit [sha]. [heads] are the PRs GitHub says it is for,
     * with each one's head commit then (empty for a commit status, or a PR from a fork).
     */
    data class CheckOn(override val installationId: Long, val repo: String, val sha: String, val check: PrState.Check, val heads: Map<Long, String>) : Change

    /** A review was submitted or dismissed. */
    data class Review(
        override val installationId: Long, val repo: String, val number: Long, val reviewId: Long, val login: String,
        val state: String, val submitted: Boolean, val body: String?, val url: String,
    ) : Change

    /** The app was uninstalled: the installation is gone for every team. */
    data class Uninstalled(override val installationId: Long) : Change

    fun parse(event: String, payload: JsonObject): Change? {
        val installation = payload.obj("installation")?.long("id") ?: return null
        val action = payload.str("action")
        if (event == "installation") return if (action == "deleted") Uninstalled(installation) else null
        val repo = payload.obj("repository")?.str("full_name")?.lowercase() ?: return null
        return when (event) {
            "pull_request" -> {
                val pr = payload.obj("pull_request") ?: return null
                Pull(
                    installation, repo, pr.long("number") ?: payload.long("number") ?: return null, pr.str("title") ?: "",
                    PrState.state(pr.str("state"), pr.bool("draft"), pr.bool("merged")), pr.obj("head")?.str("sha"),
                )
            }
            "check_run" -> {
                val run = payload.obj("check_run") ?: return null
                val sha = run.str("head_sha") ?: return null
                val heads = run["pull_requests"]?.jsonArray.orEmpty().mapNotNull { p ->
                    val o = p.jsonObject
                    val n = o.long("number") ?: return@mapNotNull null
                    n to (o.obj("head")?.str("sha") ?: return@mapNotNull null)
                }.toMap()
                CheckOn(installation, repo, sha, checkOfRun(run), heads)
            }
            "status" -> {
                val sha = payload.str("sha") ?: return null
                val context = payload.str("context") ?: return null
                CheckOn(installation, repo, sha, PrState.Check.ofStatus(context, payload.str("state") ?: "pending", payload.str("target_url")), emptyMap())
            }
            "pull_request_review" -> {
                if (action != "submitted" && action != "dismissed") return null
                val review = payload.obj("review") ?: return null
                val pr = payload.obj("pull_request") ?: return null
                Review(
                    installation, repo, pr.long("number") ?: return null, review.long("id") ?: return null,
                    review.obj("user")?.str("login") ?: return null,
                    if (action == "dismissed") "dismissed" else review.str("state")?.lowercase() ?: return null,
                    action == "submitted", review.str("body"), review.str("html_url") ?: pr.str("html_url") ?: "",
                )
            }
            // check_suite says the same as its runs; the runs carry the names.
            else -> null
        }
    }

    /** A check run, from a webhook or the API: its name, id, status, conclusion and page. */
    fun checkOfRun(run: JsonObject): PrState.Check = PrState.Check(
        run.str("name") ?: "check", run.long("id") ?: 0, run.str("status") ?: "queued", run.str("conclusion"),
        run.str("html_url") ?: run.str("details_url"),
    )

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
}
