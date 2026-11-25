package dev.wildware.feed.server.github

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64

/**
 * GitHub on this server (docs/GITHUB.md): the app's slug and webhook secret, what it asks GitHub
 * ([api]), and the `state` that carries a team through GitHub's install page and back.
 */
class GitHub(
    val slug: String,
    val webhookSecret: String,
    val api: GitHubApi,
    private val stateKey: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** GitHub's install page for the app, coming back with [state] for [team] and [member]. */
    fun installUrl(team: String, member: String): String =
        "https://github.com/apps/${URLEncoder.encode(slug, Charsets.UTF_8)}/installations/new?state=${URLEncoder.encode(state(team, member), Charsets.UTF_8)}"

    /** `<base64url(team.member.expiry)>.<base64url(hmac)>`, good for [STATE_TTL_MS]. */
    fun state(team: String, member: String): String {
        val body = Tokens.base64url("$team\n$member\n${clock() + STATE_TTL_MS}".toByteArray(Charsets.UTF_8))
        return "$body.${Tokens.base64url(Tokens.hmac(stateKey, "github-state:$body"))}"
    }

    /** The team and member a [state] names, or null when it is forged, garbled or old. */
    fun readState(state: String?): Pair<String, String>? {
        val (body, mac) = state?.split('.')?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        if (!Tokens.same(mac, Tokens.base64url(Tokens.hmac(stateKey, "github-state:$body")))) return null
        val parts = runCatching { String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8) }.getOrNull()?.split('\n') ?: return null
        if (parts.size != 3) return null
        val expires = parts[2].toLongOrNull() ?: return null
        if (clock() > expires) return null
        return parts[0] to parts[1]
    }

    companion object {
        const val STATE_TTL_MS = 10 * 60_000L
    }
}

/** The GitHub App installations each team has linked (migration 079). */
class GitHubInstalls(private val db: Database, private val clock: () -> Long) {
    data class Linked(val installationId: Long, val account: String, val addedAt: Long) {
        val json: JsonObject get() = buildJsonObject { put("installationId", installationId); put("account", account); put("addedAt", addedAt) }
    }

    /** This team's, oldest first. */
    fun list(): List<Linked> = db.query(
        "SELECT installation_id, account, added_at FROM github_installations WHERE team_id = :team ORDER BY seq",
    ) { Linked(it.getLong(1), it.getString(2), it.getLong(3)) }

    fun ids(): Set<Long> = list().map { it.installationId }.toSet()

    /** Link [installation] to this team; a second time only renames the account. */
    fun add(installation: Installation, by: String) {
        val updated = db.update(
            "UPDATE github_installations SET account = ? WHERE team_id = :team AND installation_id = ?", installation.account, installation.id,
        )
        if (updated == 0) {
            db.update(
                "INSERT INTO github_installations (team_id, id, installation_id, account, added_by, added_at) VALUES (:team, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (team_id, installation_id) DO NOTHING",
                Ulid.next(), installation.id, installation.account, by, clock(),
            )
        }
    }

    /** Unlink it from this team. False when it wasn't linked. */
    fun remove(installationId: Long): Boolean =
        db.update("DELETE FROM github_installations WHERE team_id = :team AND installation_id = ?", installationId) > 0

    /** Every team that linked [installationId]: the one read across teams, to route a webhook. */
    fun teamsFor(installationId: Long): List<String> = Tenant.allTeams {
        db.query("SELECT DISTINCT team_id FROM github_installations WHERE installation_id = ? ORDER BY team_id", installationId) { it.getString(1) }
    }

    /** The app was uninstalled: gone for every team. Answers the teams it was linked to. */
    fun removeEverywhere(installationId: Long): List<String> {
        val teams = teamsFor(installationId)
        Tenant.allTeams { db.update("DELETE FROM github_installations WHERE installation_id = ?", installationId) }
        return teams
    }
}
