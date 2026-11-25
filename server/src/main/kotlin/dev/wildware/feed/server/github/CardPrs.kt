package dev.wildware.feed.server.github

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Feed
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.invalid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * The pull requests linked to one team's updates (migration 079, docs/GITHUB.md): linking, what
 * reads show, and each webhook change applied to this team's cards only. Everything here runs in
 * one team ([dev.wildware.feed.server.db.Tenant]); every statement names it.
 */
class CardPrs(private val feed: Feed) {
    private val db get() = feed.db

    data class Row(
        val id: String, val updateId: String, val repo: String, val number: Long, val url: String, val owner: String,
        val title: String?, val state: String?, val headSha: String?, val checks: Map<String, PrState.Check>, val ci: String,
        val reviews: Map<String, String>, val review: String, val alertedSha: String?, val redOpen: Boolean, val reviewAlerted: Long?,
    ) {
        val ownerAgent: String? get() = owner.takeIf { it.startsWith(AGENT) }?.removePrefix(AGENT)

        /** What a read shows. */
        fun json(ownerName: String?): JsonObject = buildJsonObject {
            put("repo", repo)
            put("number", number)
            put("url", url)
            title?.let { put("title", it) }
            state?.let { put("state", it) }
            put("ci", ci)
            put("failing", JsonArray(PrState.failing(checks).map { c -> buildJsonObject { put("name", c.name); c.url?.let { put("url", it) } } }))
            put("review", review)
            put("changesRequested", PrState.changesRequested(reviews))
            put("owner", owner)
            ownerName?.let { put("ownerName", it) }
        }

        /** It needs someone to look: CI red, or a change asked for, while it is still open. */
        val needsAttention: Boolean get() = (state == null || state == PrState.OPEN || state == PrState.DRAFT) &&
            (ci == PrState.CI_FAILING || review == PrState.CHANGES_REQUESTED)
    }

    /** Each update's PRs in [ids], oldest linked first, as reads show them. */
    fun forUpdates(ids: List<String>): Map<String, List<JsonObject>> = views(db, ids)

    /**
     * Link the PR at [url] to [updateId], for [agent] (`agent:<id>`), who is its owner from now on.
     * [snapshot] is the PR as GitHub has it now, when it could be read. Linking it again is no
     * change, and makes [agent] the owner.
     */
    fun link(agent: String, updateId: String, url: String, snapshot: PullSnapshot?): Row = feed.op {
        val (repo, number) = parse(url)
        val card = CardRef("update", updateId)
        val update = feed.cards.resolveToWrite(card)
        val at = feed.now()
        val existing = db.one("SELECT $COLUMNS FROM card_prs WHERE team_id = :team AND update_id = ? AND repo = ? AND number = ?", updateId, repo, number) { row(it) }
        if (existing == null) {
            val count = db.one("SELECT count(*) FROM card_prs WHERE team_id = :team AND update_id = ?", updateId) { it.getLong(1) } ?: 0
            if (count >= MAX) throw invalid("a card can have at most $MAX pull requests")
            db.update(
                "INSERT INTO card_prs (team_id, id, update_id, repo, number, url, owner, linked_at, updated_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (team_id, update_id, repo, number) DO NOTHING",
                Ulid.next(at), updateId, repo, number, canonical(repo, number), agent, at, at,
            )
        } else if (existing.owner != agent) {
            db.update("UPDATE card_prs SET owner = ?, updated_at = ? WHERE team_id = :team AND id = ?", agent, at, existing.id)
        }
        if (snapshot != null) {
            val checks = snapshot.checks
            db.update(
                "UPDATE card_prs SET title = ?, state = ?, head_sha = ?, checks = ?, ci = ?, reviews = ?, review = ?, updated_at = ? " +
                    "WHERE team_id = :team AND update_id = ? AND repo = ? AND number = ?",
                snapshot.title, snapshot.state, snapshot.headSha, PrState.checksJson(checks).toString(), PrState.ci(checks),
                PrState.reviewsJson(snapshot.reviews).toString(), PrState.review(snapshot.reviews), at, updateId, repo, number,
            )
        }
        val saved = db.one("SELECT $COLUMNS FROM card_prs WHERE team_id = :team AND update_id = ? AND repo = ? AND number = ?", updateId, repo, number) { row(it) }!!
        changed(saved, update.projectId)
        saved
    }

    /** Take the PR at [url] off [updateId]. False when it wasn't linked. */
    fun unlink(updateId: String, url: String): Boolean = feed.op {
        val (repo, number) = parse(url)
        val update = feed.cards.resolveToWrite(CardRef("update", updateId))
        val n = db.update("DELETE FROM card_prs WHERE team_id = :team AND update_id = ? AND repo = ? AND number = ?", updateId, repo, number)
        if (n > 0) feed.cards.find(CardRef("update", updateId))?.let { feed.cards.announce(CardRef("update", updateId), it) }
        if (n > 0) feed.publish("card.pr", buildJsonObject { put("updateId", updateId); put("projectId", update.projectId ?: ""); put("repo", repo); put("number", number); put("removed", true) })
        n > 0
    }

    // ---- webhook changes, in this team ------------------------------------------------------------

    /** Apply [change] to this team's cards. Answers how many PR links it touched. */
    fun apply(change: Webhook.Change): Int = when (change) {
        is Webhook.Pull -> applyPull(change)
        is Webhook.CheckOn -> applyCheck(change)
        is Webhook.Review -> applyReview(change)
        is Webhook.Uninstalled -> 0
    }

    /** Live links to PR [number] in [repo], on updates not deleted. */
    private fun linked(repo: String, number: Long): List<Row> = db.query(
        "SELECT ${COLUMNS.split(", ").joinToString(", ") { "p.$it" }} FROM card_prs AS p JOIN updates AS u ON u.team_id = p.team_id AND u.id = p.update_id " +
            "WHERE p.team_id = :team AND p.repo = ? AND p.number = ? AND u.deleted_at IS NULL ORDER BY p.seq",
        repo, number,
    ) { row(it) }

    private fun applyPull(c: Webhook.Pull): Int = feed.op {
        val rows = linked(c.repo, c.number)
        for (r in rows) {
            val newHead = c.headSha != null && c.headSha != r.headSha
            val checks = if (newHead) emptyMap() else r.checks
            db.update(
                "UPDATE card_prs SET title = ?, state = ?, head_sha = ?, checks = ?, ci = ?, updated_at = ? WHERE team_id = :team AND id = ?",
                c.title, c.state, c.headSha ?: r.headSha, PrState.checksJson(checks).toString(), PrState.ci(checks), feed.now(), r.id,
            )
            changedById(r.id)
        }
        rows.size
    }

    private fun applyCheck(c: Webhook.CheckOn): Int = feed.op {
        // The PRs whose head is this commit: those we know are, and those GitHub says are now.
        val byHead = db.query(
            "SELECT ${COLUMNS.split(", ").joinToString(", ") { "p.$it" }} FROM card_prs AS p JOIN updates AS u ON u.team_id = p.team_id AND u.id = p.update_id " +
                "WHERE p.team_id = :team AND p.repo = ? AND p.head_sha = ? AND u.deleted_at IS NULL ORDER BY p.seq",
            c.repo, c.sha,
        ) { row(it) }
        val adopted = c.heads.filterValues { it == c.sha }.keys.flatMap { linked(c.repo, it) }.filter { it.headSha != c.sha }
        val rows = (byHead + adopted).distinctBy { it.id }
        for (r in rows) {
            val base = if (r.headSha == c.sha) r.checks else emptyMap()
            val checks = PrState.merged(base, c.check)
            db.update(
                "UPDATE card_prs SET head_sha = ?, checks = ?, ci = ?, updated_at = ? WHERE team_id = :team AND id = ?",
                c.sha, PrState.checksJson(checks).toString(), PrState.ci(checks), feed.now(), r.id,
            )
            routeCi(r.id)
            changedById(r.id)
        }
        rows.size
    }

    private fun applyReview(c: Webhook.Review): Int = feed.op {
        val rows = linked(c.repo, c.number)
        for (r in rows) {
            val reviews = PrState.reviewed(r.reviews, c.login, c.state)
            db.update(
                "UPDATE card_prs SET reviews = ?, review = ?, updated_at = ? WHERE team_id = :team AND id = ?",
                PrState.reviewsJson(reviews).toString(), PrState.review(reviews), feed.now(), r.id,
            )
            if (c.submitted && c.state == PrState.CHANGES_REQUESTED && r.reviewAlerted != c.reviewId && open(r.state)) {
                // Claim the review first, so a second delivery of it can't say it twice.
                val claimed = db.update(
                    "UPDATE card_prs SET review_alerted = ? WHERE team_id = :team AND id = ? AND coalesce(review_alerted, 0) <> ?",
                    c.reviewId, r.id, c.reviewId,
                )
                if (claimed > 0) line(r, PrState.changesLine(ownerName(r), r.repo, r.number, c.login, c.body, c.url.ifEmpty { r.url }))
            }
            changedById(r.id)
        }
        rows.size
    }

    /** CI on the head commit went red (tell the owner, once per commit) or green after red (say so). */
    private fun routeCi(id: String) {
        val r = byId(id) ?: return
        if (!open(r.state) || r.headSha == null) return
        when (r.ci) {
            PrState.CI_FAILING -> if (r.alertedSha != r.headSha) {
                val claimed = db.update(
                    "UPDATE card_prs SET alerted_sha = ?, red_open = 1 WHERE team_id = :team AND id = ? AND coalesce(alerted_sha, '') <> ?",
                    r.headSha, r.id, r.headSha,
                )
                if (claimed > 0) line(r, PrState.redLine(ownerName(r), r.repo, r.number, PrState.failing(r.checks), r.url))
            }
            PrState.CI_PASSING -> if (r.redOpen) {
                val claimed = db.update("UPDATE card_prs SET red_open = 0 WHERE team_id = :team AND id = ? AND red_open = 1", r.id)
                if (claimed > 0) line(r, PrState.greenLine(ownerName(r), r.repo, r.number))
            }
        }
    }

    private fun open(state: String?) = state == null || state == PrState.OPEN || state == PrState.DRAFT

    private fun ownerName(r: Row): String =
        r.ownerAgent?.let { a -> db.one("SELECT name FROM agents WHERE team_id = :team AND id = ?", a) { it.getString(1) } } ?: "owner"

    /** A line in the card's thread from GitHub: it reaches only the agent it names. */
    private fun line(r: Row, body: String) {
        feed.messages.post(Messages.Post(author = Messages.GITHUB, body = body, updateId = r.updateId, github = true))
    }

    private fun byId(id: String): Row? = db.one("SELECT $COLUMNS FROM card_prs WHERE team_id = :team AND id = ?", id) { row(it) }

    private fun changedById(id: String) {
        val r = byId(id) ?: return
        val update = feed.cards.find(CardRef("update", r.updateId)) ?: return
        changed(r, update.projectId)
    }

    /** The card changed: the apps read it again; `card.pr` says what the chip now says. */
    private fun changed(r: Row, projectId: String?) {
        val card = CardRef("update", r.updateId)
        feed.cards.find(card)?.let { feed.cards.announce(card, it) }
        feed.publish("card.pr", buildJsonObject {
            put("updateId", r.updateId); put("projectId", projectId ?: ""); put("repo", r.repo); put("number", r.number)
            r.state?.let { put("state", it) }; put("ci", r.ci); put("review", r.review)
        })
    }

    companion object {
        const val MAX = 5
        private const val CHUNK = 200
        private const val AGENT = "agent:"
        private const val COLUMNS = "id, update_id, repo, number, url, owner, title, state, head_sha, checks, ci, reviews, review, alerted_sha, red_open, review_alerted"
        private val URL = Regex("""^https?://(?:www\.)?github\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})/pull/([0-9]{1,9})(?:[/?#].*)?$""")

        /** `owner/name` (lower case) and the number, from a PR's address on github.com. */
        fun parse(url: String): Pair<String, Long> {
            val m = URL.matchEntire(url.trim()) ?: throw invalid("url must be a pull request on github.com, such as https://github.com/owner/repo/pull/12")
            val repo = "${m.groupValues[1]}/${m.groupValues[2].removeSuffix(".git")}".lowercase()
            val number = m.groupValues[3].toLong()
            if (number <= 0) throw invalid("that pull request number is not one GitHub uses")
            return repo to number
        }

        /** SQL for an update id column: it has a PR that needs someone (CI red, or changes asked for). */
        fun attentionSql(column: String) =
            "$column IN (SELECT p.update_id FROM card_prs AS p WHERE p.team_id = :team AND coalesce(p.state, 'open') IN ('open', 'draft') " +
                "AND (p.ci = 'failing' OR p.review = 'changes_requested'))"

        fun canonical(repo: String, number: Long) = "https://github.com/$repo/pull/$number"

        /** Each update's PRs in [ids], oldest linked first, as reads show them. */
        fun views(db: Database, ids: List<String>): Map<String, List<JsonObject>> {
            if (ids.isEmpty()) return emptyMap()
            // One query for the page, whatever its size: each PR with its owner's name.
            val rows = ids.distinct().chunked(CHUNK).flatMap { chunk ->
                db.query(
                    "SELECT ${COLUMNS.split(", ").joinToString(", ") { "p.$it" }}, a.name AS owner_name FROM card_prs AS p " +
                        "LEFT JOIN agents AS a ON a.team_id = p.team_id AND p.owner = 'agent:' || a.id " +
                        "WHERE p.team_id = :team AND p.update_id IN (${chunk.joinToString(", ") { "?" }}) ORDER BY p.seq",
                    *chunk.toTypedArray(),
                ) { row(it) to it.getString("owner_name") }
            }
            return rows.groupBy { it.first.updateId }.mapValues { (_, prs) -> prs.map { (r, name) -> r.json(name) } }
        }

        internal fun row(rs: ResultSet) = Row(
            id = rs.getString("id"), updateId = rs.getString("update_id"), repo = rs.getString("repo"), number = rs.getLong("number"),
            url = rs.getString("url"), owner = rs.getString("owner"), title = rs.getString("title"), state = rs.getString("state"),
            headSha = rs.getString("head_sha"), checks = PrState.checksOf(Json.parseToJsonElement(rs.getString("checks")).jsonObject),
            ci = rs.getString("ci"), reviews = PrState.reviewsOf(Json.parseToJsonElement(rs.getString("reviews")).jsonObject),
            review = rs.getString("review"), alertedSha = rs.getString("alerted_sha"), redOpen = rs.getLong("red_open") == 1L,
            reviewAlerted = rs.longOrNull("review_alerted"),
        )
    }
}
