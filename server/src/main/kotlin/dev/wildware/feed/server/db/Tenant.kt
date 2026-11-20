package dev.wildware.feed.server.db

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Which team the work on this thread is for (docs/HOSTING.md §2). Every team-owned row has a
 * `team_id`, and every statement on one names it: `WHERE team_id = :team AND …`, where [Database]
 * puts this team in for `:team`.
 *
 * A request sets it once, from its address; a job sets it for each team in turn; a bus listener
 * runs in the team of the event it hears. Code that must read across teams says so with
 * [allTeams], and may not use `:team` inside it.
 *
 * A self-hosted server has one team, [HOME]. With no team set, [id] answers [HOME] there, so
 * nothing outside a request can break; when [strict] (tests, and a server with many teams) it
 * fails instead, so a path that forgot to say its team shows up at once.
 */
object Tenant {
    /** The team every self-hosted server has, made by migration 055 with this fixed id. */
    const val HOME = "home"

    /** The marker [allTeams] sets: a statement may then touch every team, and `:team` is refused. */
    const val ALL = "*"

    @PublishedApi internal val current = ThreadLocal<String?>()

    /** Fail rather than fall back to [HOME] when no team is set. */
    @Volatile var strict: Boolean = System.getProperty("feed.strictTenant") == "true"

    /** The team set on this thread, or null (or [ALL]). */
    val currentOrNull: String? get() = current.get()

    /** Whether this thread is inside [allTeams]. */
    val isAllTeams: Boolean get() = current.get() == ALL

    /** This work's team. */
    val id: String
        get() {
            val team = current.get()
            check(team != ALL) { "no single team inside allTeams: name the team in the statement" }
            if (team != null) return team
            check(!strict) { "no team for this work: run it inside Tenant.with(team) or a request" }
            return HOME
        }

    /** Run blocking [block] as [team]. */
    inline fun <T> with(team: String, block: () -> T): T {
        require(team.isNotEmpty() && team != ALL) { "not a team: '$team'" }
        val before = current.get()
        current.set(team)
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    /** Run blocking [block] across every team: the one sanctioned way to skip the `team_id` rule. */
    inline fun <T> allTeams(block: () -> T): T {
        val before = current.get()
        current.set(ALL)
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    /** A coroutine context that carries [team] to whichever thread the coroutine resumes on. */
    fun element(team: String): ThreadContextElement<String?> {
        require(team.isNotEmpty() && team != ALL) { "not a team: '$team'" }
        return current.asContextElement(team)
    }

    /** Run suspending [block] as [team]. */
    suspend fun <T> withTeam(team: String, block: suspend () -> T): T = withContext(element(team)) { block() }
}
