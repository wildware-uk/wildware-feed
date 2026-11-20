package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.Teams
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

/**
 * Where a request was sent, and so whose it is (docs/HOSTING.md §4). With `TEAM_DOMAIN` unset,
 * every address is the home team's, as on any self-hosted server. With it set:
 *
 * - `<slug>.<TEAM_DOMAIN>` is that team's, while it is open;
 * - `<TEAM_DOMAIN>` itself is the directory: the admin API and `/api/server`, nothing else;
 * - any other address (the home team's own, `localhost`) is the home team's, or, once a team has
 *   taken it over (`feed-server move-home`), that team's.
 */
sealed interface Place {
    data class Team(val id: String) : Place
    data object Directory : Place
    data object Nowhere : Place
}

object Hosts {
    /** Where [call] was sent, by its Host header. */
    fun placeOf(app: App, call: ApplicationCall): Place {
        val domain = app.config.teamDomain ?: return home(app)
        val host = call.request.headers["Host"]?.substringBefore(':')?.trim()?.lowercase()?.trimEnd('.') ?: return home(app)
        if (host == domain || host == app.config.adminHost) return Place.Directory
        if (!host.endsWith(".$domain")) return home(app)
        val slug = host.removeSuffix(".$domain")
        if ('.' in slug) return Place.Nowhere
        // The home team keeps its own address; it is never also at home.<domain>.
        val team = app.teams.bySlug(slug)?.takeIf { it.id != Tenant.HOME } ?: return Place.Nowhere
        return Place.Team(team.id)
    }
}

/** The server's own address: the home team's, or the team's that took it over, so its keys and tokens keep working there. */
private fun home(app: App): Place = Place.Team(app.teams.homeHeir() ?: Tenant.HOME)

/** The team a request is for, set before anything else runs (absent on the directory). */
internal val TeamKey = AttributeKey<Teams.Team>("Team")

/** Set on a request to the directory address. */
internal val DirectoryKey = AttributeKey<Unit>("Directory")

/**
 * A suspended team is read-only (docs/HOSTING.md §7): reads, exports and signing in work, and
 * everything that changes the team answers `403 suspended`. MCP calls are judged tool by tool
 * ([readOnlyTool]).
 */
object Suspension {
    /** Writes that still work: signing in and out, the bell's own housekeeping, a phone's alert settings, MCP (per tool). */
    private val ALLOWED = listOf(
        "/api/devices/sign-in", "/api/devices/sign-out", "/api/devices/ticket", "/auth/ticket", "/oauth/token", "/oauth/revoke",
        "/api/notifications/seen", "/api/notifications/cards-seen", "/api/notifications/handled", "/api/devices/current",
        "/setup", "/mcp",
    )

    /** The MCP tools that only read (or keep an agent's own presence), allowed while suspended. */
    val READ_ONLY_TOOLS = setOf(
        "list_projects", "list_work", "list_tags", "get_card_fields", "get_messages", "await_request", "list_embed_types",
        "list_apps", "list_subscriptions", "list_secrets", "secret_url", "register_session", "heartbeat", "end_session",
        "acknowledge", "await_pairing",
    )

    fun refuses(call: ApplicationCall): Boolean {
        val method = call.request.httpMethod
        if (method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options) return false
        val path = Guard.normalise(call.request.path())
        return ALLOWED.none { path == it || path.startsWith("$it/") }
    }

    fun readOnlyTool(name: String) = name in READ_ONLY_TOOLS

    const val GENERIC = "This team is read-only for now: it is suspended. You can still read everything and export it."

    fun message(team: Teams.Team): String = team.suspendedMessage ?: GENERIC

    fun error(team: Teams.Team) = Wire.error("suspended", message(team))
}

/**
 * At most [limit] open streams per team (docs/HOSTING.md §7), so one team can't hold every
 * connection; the next is told to come back later. Counted in this process.
 */
class TeamStreams(private val limit: Int = LIMIT) {
    private val open = HashMap<String, Int>()

    /** Take a stream for [team]; false when it has [limit] already. */
    @Synchronized
    fun take(team: String): Boolean {
        val n = open[team] ?: 0
        if (n >= limit) return false
        open[team] = n + 1
        return true
    }

    @Synchronized
    fun give(team: String) {
        val n = (open[team] ?: 1) - 1
        if (n <= 0) open.remove(team) else open[team] = n
    }

    companion object {
        const val LIMIT = 50
        const val RETRY_AFTER_S = 30L
    }
}

/**
 * Run a stream in one of its team's slots ([TeamStreams]), with many teams; the 51st is told to
 * come back later (`429`, `Retry-After`). A self-hosted server has no such cap.
 */
suspend fun ApplicationCall.withStreamSlot(app: App, block: suspend () -> Unit) {
    if (!app.config.multiTeam) return block()
    val team = Tenant.id
    if (!app.teamStreams.take(team)) {
        respondJson(
            429, Wire.error("rate_limited", "This team has too many open streams. Try again shortly."), Wire.JSON,
            "Retry-After" to TeamStreams.RETRY_AFTER_S.toString(),
        )
        return
    }
    try {
        block()
    } finally {
        app.teamStreams.give(team)
    }
}
