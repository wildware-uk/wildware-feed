package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * What a post carries besides its words: uploaded media, embeds and app views, checked before the
 * post is written and attached after (TS `src/domain/media.ts`, `embeds.ts`, `apps.ts`).
 */
object FeedAttachments {
    const val MEDIA_MAX = 24
    const val EMBEDS_MAX = 8
    const val APPS_MAX = 4
    const val APP_DATA_MAX = 100_000
    const val APP_TITLE_MAX = 120
    const val APP_URI_MAX = 120
    private val APP_URI_PATH = Regex("^[a-z0-9]+(?:[-.][a-z0-9]+)*(?:/[a-z0-9]+(?:[-.][a-z0-9]+)*)*$")
    private val ANCHORS = listOf("update_id", "message_id", "request_id", "task_id")

    class EmbedInput(val type: String, val data: JsonElement?, val sourceUrl: JsonElement?)
    class CheckedEmbed(val typeId: String, val data: String, val sourceUrl: String?)
    class AppInput(val app: JsonElement?, val data: JsonElement?, val title: JsonElement?)
    class CheckedApp(val appId: String, val data: String, val title: String?)
    class AppViewRef(val id: String, val uri: String, val title: String?)

    private class MediaRow(val agentId: String?, val author: String, val anchors: List<String?>, val sha256: String)

    private fun media(db: Database, id: String) = db.one("SELECT agent_id, author, update_id, message_id, request_id, sha256 FROM media WHERE id = ?", id) {
        MediaRow(it.getString(1), it.getString(2), listOf(it.getString(3), it.getString(4), it.getString(5)), it.getString(6))
    }

    /** `checkedMediaIds`: de-duplicated, at most 24, each id-shaped. */
    private fun checkedIds(ids: List<String>): List<String> {
        val unique = ids.distinct()
        if (unique.isEmpty()) throw invalid("media_ids must name at least one upload")
        if (unique.size > MEDIA_MAX) throw invalid("an update may carry at most $MEDIA_MAX media items")
        for (id in unique) if (!Reads.ID.matches(id)) throw invalid("not a media id: ${JsText.quote(id)}")
        return unique
    }

    /** `assertAttachable` (an agent's update): its own, not on an update yet, with bytes. */
    fun assertAttachableToUpdate(db: Database, ids: List<String>, agentId: String): List<String> {
        val unique = checkedIds(ids)
        for (id in unique) {
            val row = media(db, id) ?: throw notFound("no such media: $id")
            if (row.agentId != agentId) throw invalid("media $id belongs to another agent")
            if (row.anchors[0] != null) throw invalid("media $id is already on another update")
            if (row.sha256.isEmpty()) throw invalid("media $id has no bytes yet: PUT the file to its upload_url first")
        }
        return unique
    }

    /** `assertAttachableToMessage`: uploaded by this author, attached to nothing, with bytes. */
    fun assertAttachableToMessage(db: Database, ids: List<String>, author: String): List<String> {
        val unique = checkedIds(ids)
        for (id in unique) {
            val row = media(db, id) ?: throw notFound("no such media: $id")
            if (row.author != author) throw invalid("media $id was uploaded by somebody else")
            val on = when {
                row.anchors[0] != null -> "an update"
                row.anchors[1] != null -> "another message"
                row.anchors[2] != null -> "an approval"
                else -> null
            }
            if (on != null) throw invalid("media $id is already on $on")
            if (row.sha256.isEmpty()) throw invalid("media $id has no bytes yet: upload the file first")
        }
        return unique
    }

    /** Point unattached media at a card; anything already attached or not the owner's is skipped. */
    fun attachMedia(db: Database, ids: List<String>, anchor: String, cardId: String, ownerColumn: String, owner: String?) {
        if (ids.isEmpty()) return
        db.update(
            "UPDATE media SET $anchor = ? WHERE id IN (${ids.joinToString(", ") { "?" }}) AND " +
                ANCHORS.joinToString(" AND ") { "$it IS NULL" } + " AND (? IS NULL OR $ownerColumn = ?)",
            cardId, *ids.toTypedArray(), owner, owner,
        )
    }

    fun checkedEmbeds(db: Database, embeds: List<EmbedInput>): List<CheckedEmbed> {
        if (embeds.size > EMBEDS_MAX) throw invalid("a post may carry at most $EMBEDS_MAX embeds")
        return embeds.mapIndexed { at, embed ->
            val slug = embed.type.jsTrim().lowercase()
            val typeId = db.one("SELECT id FROM embed_types WHERE slug = ?", slug) { it.getString(1) }
                ?: throw invalid(
                    "embeds[$at]: no embed type \"$slug\". Call list_embed_types to see what is registered, or register_embed_type to add it.",
                )
            CheckedEmbed(
                typeId,
                Wire.encode(Embeds.parseData(embed.data, "embeds[$at].data")),
                sourceUrl(embed.sourceUrl, "embeds[$at].source_url"),
            )
        }
    }

    /** `parseSourceUrl`: an absolute http(s) URL, as `new URL(raw).toString()` prints it. */
    private fun sourceUrl(raw: JsonElement?, field: String): String? {
        if (raw == null || raw is JsonNull || raw.jsString == "") return null
        val text = raw.jsString ?: throw invalid("$field must be a URL")
        val url = WebUrl.parse(text.jsTrim())
        if (url?.host == null || (url.scheme != "http" && url.scheme != "https")) {
            throw invalid("$field must be an absolute http or https URL")
        }
        return url.href
    }

    fun attachEmbeds(db: Database, embeds: List<CheckedEmbed>, anchor: String, cardId: String, agentId: String?, author: String, at: Long) {
        for (embed in embeds) {
            db.update(
                "INSERT INTO embeds (id, type_id, $anchor, agent_id, author, data, source_url, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(), embed.typeId, cardId, agentId, author, embed.data, embed.sourceUrl, at,
            )
        }
    }

    fun checkedApps(db: Database, apps: List<AppInput>): List<CheckedApp> {
        if (apps.size > APPS_MAX) throw invalid("a post may carry at most $APPS_MAX apps")
        return apps.mapIndexed { at, entry ->
            val uri = appUri(entry.app, "apps[$at].app")
            val appId = db.one("SELECT id FROM apps WHERE uri = ?", uri) { it.getString(1) }
                ?: throw invalid("apps[$at]: no app \"$uri\". Call register_app to ship it, or list_apps to see what is already registered.")
            CheckedApp(appId, appData(entry.data, "apps[$at].data"), appTitle(entry.title, "apps[$at].title"))
        }
    }

    internal fun appUri(raw: JsonElement?, field: String): String {
        val text = raw.jsString ?: throw invalid("$field must be a ui:// URI")
        val uri = text.jsTrim().lowercase()
        if (!uri.startsWith("ui://")) throw invalid("$field must start with ui://, e.g. \"ui://review-board\"")
        if (uri.length > APP_URI_MAX) throw invalid("$field may be at most $APP_URI_MAX characters")
        if (!APP_URI_PATH.matches(uri.substring(5))) {
            throw invalid("$field must be ui:// followed by lowercase words, e.g. \"ui://review-board\" or \"ui://acme/review-board\"")
        }
        return uri
    }

    internal fun appData(raw: JsonElement?, field: String): String {
        val value = if (raw == null || raw is JsonNull) JsonObject(emptyMap()) else raw
        if (value !is JsonObject) throw invalid("$field must be a JSON object")
        val text = Wire.encode(Js.normalize(value))
        if (text.length > APP_DATA_MAX) throw invalid("$field may be at most $APP_DATA_MAX characters of JSON")
        return text
    }

    internal fun appTitle(raw: JsonElement?, field: String): String? {
        if (raw == null || raw is JsonNull || raw.jsString == "") return null
        val text = raw.jsString ?: throw invalid("$field must be text")
        val title = text.jsTrim()
        if (title.length > APP_TITLE_MAX) throw invalid("$field may be at most $APP_TITLE_MAX characters")
        return title.ifEmpty { null }
    }

    fun attachApps(db: Database, apps: List<CheckedApp>, card: CardRef, agentId: String?, author: String, at: Long) {
        for (app in apps) {
            db.update(
                "INSERT INTO app_views (id, app_id, ${card.anchor}, agent_id, author, data, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Ulid.next(), app.appId, card.id, agentId, author, app.data, app.title, at, at,
            )
        }
    }

    /** `appsOn`: the card's views whose app still exists, oldest first. */
    fun appsOn(db: Database, card: CardRef): List<AppViewRef> = db.query(
        "SELECT v.id, a.uri, v.title FROM app_views AS v JOIN apps AS a ON a.id = v.app_id WHERE v.${card.anchor} = ? ORDER BY v.seq",
        card.id,
    ) { AppViewRef(it.getString(1), it.getString(2), it.getString(3)) }
}
