package dev.wildware.feed.server.github

import dev.wildware.feed.server.auth.Tokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The pure parts of GitHub on cards (docs/GITHUB.md): the webhook's signature, a PR's CI and review
 * worked out from its checks and reviews, and the words of the lines that tell an agent.
 */
object PrState {
    const val OPEN = "open"
    const val DRAFT = "draft"
    const val MERGED = "merged"
    const val CLOSED = "closed"

    const val CI_NONE = "none"
    const val CI_PENDING = "pending"
    const val CI_PASSING = "passing"
    const val CI_FAILING = "failing"

    const val REVIEW_NONE = "none"
    const val APPROVED = "approved"
    const val CHANGES_REQUESTED = "changes_requested"
    const val COMMENTED = "commented"

    /** Conclusions that make CI red. A cancelled run on the head commit is a timeout or a stop: red too. */
    private val FAILED = setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure", "error")

    /** One check on the head commit: a check run (by its [id]) or a commit status (id 0, newest wins). */
    data class Check(val name: String, val id: Long, val status: String, val conclusion: String?, val url: String?) {
        val failed: Boolean get() = status == "completed" && conclusion in FAILED
        val finished: Boolean get() = status == "completed"

        val json: JsonObject get() = buildJsonObject {
            put("id", id); put("status", status); conclusion?.let { put("conclusion", it) }; url?.let { put("url", it) }
        }

        companion object {
            fun of(name: String, o: JsonObject) = Check(
                name, o["id"]?.jsonPrimitive?.longOrNull ?: 0, o["status"]?.jsonPrimitive?.contentOrNull ?: "queued",
                o["conclusion"]?.jsonPrimitive?.contentOrNull, o["url"]?.jsonPrimitive?.contentOrNull,
            )

            /** A commit status (`status` event): `pending`, `success`, `failure` or `error`. */
            fun ofStatus(context: String, state: String, url: String?) = when (state) {
                "pending" -> Check(context, 0, "in_progress", null, url)
                "success" -> Check(context, 0, "completed", "success", url)
                else -> Check(context, 0, "completed", if (state == "error") "error" else "failure", url)
            }
        }
    }

    fun checksOf(json: JsonObject): Map<String, Check> = json.mapValues { (name, v) -> Check.of(name, v.jsonObject) }

    fun checksJson(checks: Map<String, Check>): JsonObject = JsonObject(checks.mapValues { it.value.json })

    /**
     * [checks] with [check] in it: a check run replaces an older run of the same name (a lower id),
     * never a newer one, so a late delivery can't undo a re-run. A commit status always replaces.
     */
    fun merged(checks: Map<String, Check>, check: Check): Map<String, Check> {
        val old = checks[check.name]
        if (old != null && check.id != 0L && old.id > check.id) return checks
        return checks + (check.name to check)
    }

    /** CI from the head commit's checks: red, else still running, else green; nothing yet is none. */
    fun ci(checks: Map<String, Check>): String = when {
        checks.isEmpty() -> CI_NONE
        checks.values.any { it.failed } -> CI_FAILING
        checks.values.any { !it.finished } -> CI_PENDING
        else -> CI_PASSING
    }

    fun failing(checks: Map<String, Check>): List<Check> = checks.values.filter { it.failed }.sortedBy { it.name }

    /**
     * Each reviewer's latest review state (GitHub's `APPROVED`, `CHANGES_REQUESTED`, `COMMENTED`,
     * `DISMISSED`), with [login]'s new one. A comment after an approval or a change request leaves
     * that standing, as GitHub shows it; a dismissal takes it away.
     */
    fun reviewed(reviews: Map<String, String>, login: String, state: String): Map<String, String> {
        val s = state.lowercase()
        val before = reviews[login]
        return when {
            s == "dismissed" -> reviews - login
            s == COMMENTED && (before == APPROVED || before == CHANGES_REQUESTED) -> reviews
            else -> reviews + (login to s)
        }
    }

    /** The PR's review, overall: any change request wins, then an approval, then a comment. */
    fun review(reviews: Map<String, String>): String = when {
        reviews.values.any { it == CHANGES_REQUESTED } -> CHANGES_REQUESTED
        reviews.values.any { it == APPROVED } -> APPROVED
        reviews.values.any { it == COMMENTED } -> COMMENTED
        else -> REVIEW_NONE
    }

    fun changesRequested(reviews: Map<String, String>): Int = reviews.values.count { it == CHANGES_REQUESTED }

    fun reviewsOf(json: JsonObject): Map<String, String> = json.mapValues { it.value.jsonPrimitive.content }

    fun reviewsJson(reviews: Map<String, String>): JsonObject = JsonObject(reviews.mapValues { JsonPrimitive(it.value) })

    /** A PR's state from GitHub's `state`, `draft` and `merged`. */
    fun state(state: String?, draft: Boolean, merged: Boolean): String = when {
        merged -> MERGED
        state == "closed" -> CLOSED
        draft -> DRAFT
        else -> OPEN
    }

    // ---- the webhook's signature ----------------------------------------------------------------

    /**
     * Whether [header] (`X-Hub-Signature-256`: `sha256=<hex>`) is the HMAC-SHA256 of the exact [body]
     * bytes under [secret], compared in constant time.
     */
    fun signed(secret: String, body: ByteArray, header: String?): Boolean {
        val sent = header?.trim()?.takeIf { it.startsWith("sha256=") }?.removePrefix("sha256=")?.lowercase() ?: return false
        if (sent.length != 64) return false
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            doFinal(body)
        }
        val want = mac.joinToString("") { "%02x".format(it) }
        return Tokens.same(want, sent)
    }

    // ---- the lines --------------------------------------------------------------------------------

    /** `@` in GitHub's words must not @mention anyone here: a zero-width space after it. */
    private fun quiet(text: String) = text.replace("@", "@​")

    /**
     * "CI failed on #423 (acme/app): build (android), lint — <link>", then "@rover Please fix it and
     * push." The first line is what people see in the thread; the agent reads it all.
     */
    fun redLine(owner: String, repo: String, number: Long, failing: List<Check>, prUrl: String): String {
        val names = failing.take(MAX_NAMED).joinToString(", ") { quiet(it.name) } +
            if (failing.size > MAX_NAMED) " and ${failing.size - MAX_NAMED} more" else ""
        val link = failing.firstNotNullOfOrNull { it.url } ?: prUrl
        return "CI failed on #$number ($repo): $names — $link\n@$owner Please fix it and push. You'll hear here when it is green again."
    }

    fun greenLine(owner: String, repo: String, number: Long): String = "CI is green again on #$number ($repo).\n@$owner It passes now."

    /** "Changes requested on #423 by sam: <the review's first words> — <link>" */
    fun changesLine(owner: String, repo: String, number: Long, reviewer: String, body: String?, url: String): String {
        val said = body?.trim()?.takeIf { it.isNotEmpty() }?.let { quiet(cut(it, 400)) }
        return "Changes requested on #$number ($repo) by ${quiet(reviewer)} — $url\n" +
            (said?.let { "> ${it.replace("\n", "\n> ")}\n" } ?: "") +
            "@$owner Please address the review and push."
    }

    private fun cut(text: String, max: Int) = if (text.length <= max) text else text.substring(0, max).trimEnd() + "…"

    private const val MAX_NAMED = 4
}
