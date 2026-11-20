package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Tenant

/**
 * `feed-server move-home --to <team id>`: the home team's everything becomes a hosted team's, in one
 * transaction. For a server that ran before it had many teams, whose owner then signed up for a team
 * of their own and wants their old dashboard in it.
 *
 * Nothing is copied, so nothing can be missed or doubled: every team table's home rows get the new
 * team's id. Ids, keys and tokens stay what they were, so agents, devices and share links keep
 * working. The target must be fresh (only its owner and its built-in apps and embed types); those go,
 * and the home team's founding membership becomes the target owner's, so their account owns the old
 * work and the founder's devices stay signed in. The target is marked as having taken over the home
 * team ([Teams.homeHeir]): it answers at the server's own address too, and its media files stay at
 * the root of the store, where the home team's always were. The home team is left empty.
 */
class HomeMove(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** How many rows of each team table moved (tables with none left out). */
    data class Report(val target: String, val moved: Map<String, Int>)

    private class DryRun(val report: Report) : RuntimeException()

    /** Move the home team into [to]. With [dryRun], everything is checked and counted, then rolled back. */
    fun run(to: String, dryRun: Boolean = false): Report = try {
        Tenant.allTeams { db.transaction { c ->
            c.createStatement().use { it.execute(if (db.dialect == Dialect.POSTGRES) "SET CONSTRAINTS ALL DEFERRED" else "PRAGMA defer_foreign_keys = ON") }
            val report = move(to)
            if (dryRun) throw DryRun(report)
            report
        } }
    } catch (e: DryRun) {
        e.report
    }

    private fun count(table: String, team: String, where: String = "") =
        db.one("SELECT count(*) FROM \"$table\" WHERE team_id = ?$where", team) { it.getLong(1) } ?: 0

    private fun refuse(message: String): Nothing = throw DomainError("invalid_request", message, 409)

    private fun move(to: String): Report {
        if (to == Tenant.HOME) refuse("The home team can't take itself over.")
        val target = db.one("SELECT state FROM teams WHERE id = ?", to) { it.getString(1) } ?: refuse("There is no team $to.")
        if (target != "active") refuse("Team $to is not open.")
        if (db.one("SELECT id FROM teams WHERE took_over_home = 1") { it.getString(1) } != null) refuse("A team has taken over the home team already.")
        if (count("memberships", Tenant.HOME, " AND id = '${People.FOUNDER}' AND removed_at IS NULL") == 0L) {
            refuse("The home team has no founder to hand over: nothing to move.")
        }

        // Fresh: its owner, its built-in apps and embed types, any one-time codes, and nothing else.
        val owners = db.query("SELECT user_id FROM memberships WHERE team_id = ? AND role = 'owner' AND removed_at IS NULL", to) { it.getString(1) }
        if (owners.size != 1 || count("memberships", to) != 1L) refuse("Team $to must have exactly one person, its owner.")
        val used = TeamTables.ALL.filter { t ->
            when (t) {
                "memberships", "auth_codes" -> false
                "apps", "embed_types" -> count(t, to, " AND agent_id IS NOT NULL") > 0
                else -> count(t, to) > 0
            }
        }
        if (used.isNotEmpty()) refuse("Team $to is in use already (${used.joinToString(", ")}): only a fresh team can take over the home team.")
        val owner = owners.single()

        db.update("DELETE FROM auth_codes WHERE team_id = ?", to)
        db.update("DELETE FROM apps WHERE team_id = ? AND agent_id IS NULL", to)
        db.update("DELETE FROM embed_types WHERE team_id = ? AND agent_id IS NULL", to)
        db.update("DELETE FROM memberships WHERE team_id = ?", to)

        val moved = LinkedHashMap<String, Int>()
        for (table in TeamTables.ALL) {
            val n = db.update("UPDATE \"$table\" SET team_id = ? WHERE team_id = ?", to, Tenant.HOME)
            if (n > 0) moved[table] = n
        }
        db.update("UPDATE memberships SET user_id = ? WHERE team_id = ? AND id = ?", owner, to, People.FOUNDER)
        db.update("UPDATE teams SET took_over_home = 1, updated_at = ? WHERE id = ?", clock(), to)
        return Report(to, moved)
    }
}
