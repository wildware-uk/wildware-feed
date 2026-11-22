package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Apps an agent registers and pushes data into (TS `src/domain/apps.ts`, spec/05 §7.10-7.12).
 * Showing one on a card is [FeedAttachments.checkedApps]; the owner's saved state is [AppState].
 */
class Apps(private val feed: Feed) {
    private val db get() = feed.db

    class Register(
        val agentId: String,
        val uri: String,
        val name: String,
        val description: String? = null,
        val html: String? = null,
        val url: String? = null,
        val csp: JsonElement? = null,
    )

    /** `AppSummary`. [csp] is `{connect, resource, frame}`. */
    class Summary(
        val uri: String,
        val name: String,
        val description: String?,
        val csp: JsonObject,
        val url: String?,
        val bytes: Int,
        val mine: Boolean,
        val updatedAt: Long,
    )

    /** The parts of `AppViewData` the tools answer with. */
    class View(val id: String, val uri: String, val title: String?, val updatedAt: Long)

    private class Row(val agentId: String?, val uri: String, val name: String, val description: String?, val html: String, val url: String?, val csp: String, val updatedAt: Long)

    private val columns = "agent_id, uri, name, description, html, url, csp, updated_at"

    private fun row(rs: java.sql.ResultSet) = Row(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
    )

    /** `registerApp`: an upsert on the URI, refused when somebody else's. No event. */
    fun register(input: Register): Summary = feed.op {
        val uri = FeedAttachments.appUri(JsonPrimitive(input.uri), "uri")

        val name = input.name.jsTrim()
        if (name.isEmpty() || name.length > NAME_MAX) throw invalid("name is required, and at most $NAME_MAX characters")

        val description = (input.description ?: "").jsTrim()
        if (description.length > DESCRIPTION_MAX) throw invalid("description may be at most $DESCRIPTION_MAX characters")

        val wantsHtml = input.html != null && input.html.jsTrim() != ""
        val wantsUrl = input.url != null && input.url.jsTrim() != ""
        if (wantsHtml && wantsUrl) throw invalid("give html or url, not both: an app is a document, or a page to frame")
        if (!wantsHtml && !wantsUrl) throw invalid("an app needs html — a self-contained document — or url, an https page to frame")

        val html = if (wantsHtml) input.html!! else ""
        if (html.length > HTML_MAX) {
            throw invalid(
                "html may be at most $HTML_MAX characters. An app is one self-contained document, so split the work " +
                    "between the app and tool calls rather than shipping a bundle.",
            )
        }

        val url = if (wantsUrl) parsePage(input.url!!, "url") else null
        val csp = parseCsp(input.csp, "csp")

        val existing = db.one("SELECT agent_id FROM apps WHERE team_id = :team AND uri = ?", uri) { Given(it.getString(1)) }
        if (existing != null && existing.value != input.agentId) {
            throw conflict(
                if (existing.value == null) {
                    "\"$uri\" is one of the apps this dashboard ships, so it cannot be redefined."
                } else {
                    "\"$uri\" was registered by another agent. Pick a different URI."
                },
            )
        }

        val at = feed.now()
        val saved = db.one(
            "INSERT INTO apps (team_id, id, uri, name, description, agent_id, html, url, csp, created_at, updated_at) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (team_id, uri) DO UPDATE SET name = excluded.name, description = excluded.description, " +
                "html = excluded.html, url = excluded.url, csp = excluded.csp, updated_at = excluded.updated_at " +
                "RETURNING $columns",
            Ulid.next(), uri, name, description.ifEmpty { null }, input.agentId, html, url, Wire.encode(csp), at, at,
            row = ::row,
        )!!
        summary(saved, input.agentId)
    }

    /** `listAppSummaries`: every app, by name. */
    fun list(agentId: String?): List<Summary> =
        db.query("SELECT $columns FROM apps WHERE team_id = :team ORDER BY ${db.asciiFold("name")}, uri", row = ::row).map { summary(it, agentId) }

    private fun summary(row: Row, agentId: String?) = Summary(
        uri = row.uri,
        name = row.name,
        description = row.description,
        csp = cspOf(row.csp),
        url = row.url,
        bytes = row.html.toByteArray(Charsets.UTF_8).size,
        mine = agentId != null && row.agentId == agentId,
        updatedAt = row.updatedAt,
    )

    /**
     * `setAppData`: replace what one showing was handed. [title] null leaves it alone; a given
     * empty or null title clears it. Publishes the card's change event, then `apps.changed`.
     */
    fun setData(viewId: String, data: JsonElement?, title: Given<JsonElement?>?): View = feed.op {
        val notFoundView = { notFound("no app view ${JsText.quote(viewId)}") }
        val card = db.one("SELECT update_id, message_id, request_id FROM app_views WHERE team_id = :team AND id = ?", viewId) { rs ->
            rs.getString(1)?.takeIf { it.isNotEmpty() }?.let { CardRef("update", it) }
                ?: rs.getString(2)?.takeIf { it.isNotEmpty() }?.let { CardRef("message", it) }
                ?: CardRef("request", rs.getString(3))
        } ?: throw notFoundView()

        val cardRow = feed.cards.resolveToWrite(card)
        val text = FeedAttachments.appData(data, "data")
        val newTitle = title?.let { FeedAttachments.appTitle(it.value, "title") }

        val now = feed.now()
        val updated = if (title == null) {
            db.one("UPDATE app_views SET data = ?, updated_at = ? WHERE team_id = :team AND id = ? RETURNING app_id, title, updated_at", text, now, viewId, row = ::viewRow)
        } else {
            db.one("UPDATE app_views SET data = ?, title = ?, updated_at = ? WHERE team_id = :team AND id = ? RETURNING app_id, title, updated_at", text, newTitle, now, viewId, row = ::viewRow)
        } ?: throw notFoundView()

        feed.cards.announce(card, cardRow)
        feed.publish("apps.changed", buildJsonObject { put("projectId", cardRow.projectId) })

        val uri = db.one("SELECT uri FROM apps WHERE team_id = :team AND id = ?", updated.first) { it.getString(1) }
        checkNotNull(uri) { "app view $viewId names a missing app" }
        View(viewId, uri, updated.second, updated.third)
    }

    private fun viewRow(rs: java.sql.ResultSet) = Triple(rs.getString(1), rs.getString(2), rs.getLong(3))

    /**
     * `appDocument`: the html and declared origins one showing renders with, or null for an unknown
     * view, a missing app, or an app that frames a page elsewhere. A deleted card is not checked.
     */
    fun document(viewId: String): Pair<String, JsonObject>? {
        val (appId, card) = db.one("SELECT app_id, update_id, message_id, request_id FROM app_views WHERE team_id = :team AND id = ?", viewId) {
            it.getString(1) to (it.getString(2)?.let { id -> CardRef("update", id) } ?: it.getString(3)?.let { id -> CardRef("message", id) } ?: it.getString(4)?.let { id -> CardRef("request", id) })
        } ?: return null
        // A showing on a card in a channel this viewer can't read is missing (spec/14).
        if (card != null && feed.cards.find(card) == null) return null
        val app = db.query("SELECT $columns FROM apps WHERE team_id = :team AND id = ?", appId, row = ::row).firstOrNull() ?: return null
        if (app.url != null) return null
        return app.html to cspOf(app.csp)
    }

    companion object {
        const val NAME_MAX = 80
        const val DESCRIPTION_MAX = 400
        const val HTML_MAX = 250_000
        const val URL_MAX = 2_000
        const val CSP_ORIGINS_MAX = 10
        private val CSP_KEYS = listOf("connect", "resource", "frame")

        private val EMPTY_CSP: JsonObject get() = buildJsonObject { for (key in CSP_KEYS) put(key, JsonArray(emptyList())) }

        /** `cspOf`: the stored origins re-checked, or none when they do not hold up. */
        private fun cspOf(stored: String): JsonObject =
            runCatching { parseCsp(Js.parse(stored), "csp") }.getOrElse { EMPTY_CSP }

        /** `parseAppPage`: an https page, as `url.href` prints it. */
        internal fun parsePage(raw: String, field: String): String {
            val url = WebUrl.parse(raw.jsTrim())
            if (url == null || (url.scheme == "https" && url.host == null)) {
                throw invalid("$field must be an absolute https URL, e.g. \"https://fix.example.com/embed\"")
            }
            if (url.scheme != "https") {
                throw invalid("$field must use https: the dashboard is served over it, and a browser refuses to frame an insecure page inside a secure one")
            }
            if (url.hasCredentials) throw invalid("$field must not carry credentials")
            if (url.href.length > URL_MAX) throw invalid("$field may be at most $URL_MAX characters")
            return url.href
        }

        /** `parseAppCsp`: three lists of plain https origins. */
        internal fun parseCsp(raw: JsonElement?, field: String): JsonObject {
            if (raw == null || raw is JsonNull) return EMPTY_CSP
            if (raw !is JsonObject) throw invalid("$field must be an object of { connect, resource, frame }")
            return buildJsonObject {
                for (key in CSP_KEYS) {
                    val list = raw[key]
                    if (list == null || list is JsonNull) {
                        put(key, JsonArray(emptyList()))
                        continue
                    }
                    if (list !is JsonArray) throw invalid("$field.$key must be a list of https origins")
                    if (list.size > CSP_ORIGINS_MAX) throw invalid("$field.$key may name at most $CSP_ORIGINS_MAX origins")
                    put(key, JsonArray(list.mapIndexed { at, entry -> JsonPrimitive(parseOrigin(entry, "$field.$key[$at]")) }))
                }
            }
        }

        /** This dashboard's own origins: an app may not name them in its csp (set by the app at start). */
        @Volatile var ownOrigins: Set<String> = emptySet()

        /** With many teams, the domain every team's address is under (TEAM_DOMAIN): no app may name it either. */
        @Volatile var ownDomain: String? = null

        private fun parseOrigin(raw: JsonElement, field: String): String {
            val text = raw.jsString ?: throw invalid("$field must be an https origin")
            val url = WebUrl.parse(text.jsTrim())
            if (url == null || (url.scheme == "https" && url.host == null)) {
                throw invalid("$field must be an https origin, e.g. \"https://api.example.com\"")
            }
            if (url.scheme != "https") throw invalid("$field must use https")
            if (url.hasCredentials) throw invalid("$field must not carry credentials")
            if (url.path != "/" || !url.query.isNullOrEmpty() || !url.fragment.isNullOrEmpty()) {
                throw invalid("$field must be an origin — a host, with no path")
            }
            val host = url.host?.lowercase()?.trimEnd('.')
            val domain = ownDomain
            if (url.origin in ownOrigins || (domain != null && host != null && (host == domain || host.endsWith(".$domain")))) {
                throw invalid("$field can't be this dashboard's own address")
            }
            return url.origin
        }

        /** `policyFor`: the Content-Security-Policy an app's document is served under. */
        fun policyFor(csp: JsonObject): String {
            fun origins(key: String) = (csp[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            val connect = origins("connect").ifEmpty { listOf("'none'") }.joinToString(" ")
            val resource = origins("resource").joinToString(" ")
            val frame = origins("frame").ifEmpty { listOf("'none'") }.joinToString(" ")
            return listOf(
                "default-src 'none'",
                "script-src 'unsafe-inline'",
                "style-src 'unsafe-inline' $resource".jsTrim(),
                "img-src data: blob: $resource".jsTrim(),
                "font-src data: $resource".jsTrim(),
                "media-src data: blob: $resource".jsTrim(),
                "connect-src $connect",
                "frame-src $frame",
                "form-action 'none'",
                "base-uri 'none'",
                "frame-ancestors 'self'",
                // The page runs as nobody, opened in its own tab as much as in the card's frame: no
                // cookies, no storage, no reach into the dashboard (sandbox="allow-scripts", no
                // allow-same-origin, as the tool's description promises the agent).
                "sandbox allow-scripts",
            ).joinToString("; ")
        }
    }
}
