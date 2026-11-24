package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.Docs
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.docs
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsNumber
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A channel's docs (migration 076, spec/17): list, search, read, create, save, delete, versions
 * and restore. Reading needs Read on the channel, the rest Post ([Docs]).
 */
fun Route.docRoutes(app: App) {
    fun RoutingContext.param(name: String) = call.parameters[name] ?: ""
    fun RoutingContext.actor() = Viewer.MEMBER + call.person.id

    /** One doc with what its page needs: who saved it, the docs linking to it, and whether you may edit. */
    fun page(docs: Docs, doc: Docs.DocRow): JsonObject {
        val backlinks = docs.backlinks(doc)
        val names = docs.namesFor(listOf(doc))
        return buildJsonObject {
            put("doc", docs.json(doc, names))
            put("backlinks", JsonArray(backlinks.map { buildJsonObject { put("id", it.id); put("title", it.title) } }))
            put("canEdit", docs.canEdit(doc.projectId))
        }
    }

    /** A refused save says what the doc is now, so the app can show both texts to merge. */
    suspend fun RoutingContext.saving(docs: Docs, block: () -> Docs.DocRow) {
        try {
            call.respondOwner(200, page(docs, block()))
        } catch (e: Docs.Conflict) {
            call.respondOwner(409, buildJsonObject {
                Wire.error(e.code, e.message).forEach { (k, v) -> put(k, v) }
                put("baseVersion", e.baseVersion)
                put("doc", docs.json(e.current, docs.namesFor(listOf(e.current))))
            })
        }
    }

    fun version(value: JsonElement?, field: String): Long {
        val n = value.jsNumber ?: throw invalid("$field must be a whole number")
        if (!Js.isInteger(n) || n < 1) throw invalid("$field must be a whole number")
        return n.toLong()
    }

    endpoint(
        "/api/projects/{reference}/docs",
        HttpMethod.Get to {
            val docs = app.feed.docs
            val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
            val list = if (q != null) docs.search(param("reference"), q, 100) else docs.list(param("reference"))
            val project = app.feed.projects.resolve(param("reference"))
            call.respondOwner(200, buildJsonObject {
                put("projectId", project.id)
                put("docs", docs.listJson(list))
                put("canEdit", docs.canEdit(project.id))
                // No Index yet: a first one to write, saved only when someone saves it.
                if (docs.index(project.id) == null) put("indexTemplate", docs.indexDraft(project.id))
            })
        },
        HttpMethod.Post to {
            val body = call.jsonObjectBody(BODY_MAX)
            val title = text(body["title"], "title")
            val text = if ("body" in body) text(body["body"], "body") else ""
            val docs = app.feed.docs
            val doc = docs.create(param("reference"), title, text, actor())
            call.respondOwner(201, page(docs, doc))
        },
    )

    // The doc a [[link]] names, by title, in any case: 404 when there is none yet.
    endpoint(
        "/api/projects/{reference}/docs/by-title",
        HttpMethod.Get to {
            val title = call.request.queryParameters["title"]?.takeIf { it.isNotBlank() } ?: throw invalid("title is required")
            val docs = app.feed.docs
            val doc = docs.byTitle(param("reference"), title)
                ?: throw dev.wildware.feed.server.domain.notFound("no doc called ${dev.wildware.feed.server.domain.JsText.quote(title.trim())} in this channel yet")
            call.respondOwner(200, page(docs, doc))
        },
    )

    endpoint(
        "/api/docs/{id}",
        HttpMethod.Get to {
            val docs = app.feed.docs
            call.respondOwner(200, page(docs, docs.get(param("id"))))
        },
        HttpMethod.Put to {
            val body = call.jsonObjectBody(BODY_MAX)
            val base = version(body["baseVersion"], "baseVersion")
            val title = if ("title" in body) text(body["title"], "title") else null
            val text = if ("body" in body) text(body["body"], "body") else null
            if (title == null && text == null) throw invalid("send a new title, body or both")
            val docs = app.feed.docs
            saving(docs) { docs.save(param("id"), base, title, text, actor()) }
        },
        HttpMethod.Delete to {
            val docs = app.feed.docs
            val doc = docs.delete(param("id"), actor())
            call.respondOwner(200, buildJsonObject { put("doc", buildJsonObject { put("id", doc.id); put("projectId", doc.projectId); put("deleted", true) }) })
        },
    )

    endpoint(
        "/api/docs/{id}/versions",
        HttpMethod.Get to {
            val docs = app.feed.docs
            val versions = docs.versions(param("id"))
            val names = docs.names(versions.map { it.savedBy })
            call.respondOwner(200, buildJsonObject {
                put("versions", JsonArray(versions.map { docs.versionJson(it, names) }))
                put("kept", Docs.KEEP_VERSIONS)
            })
        },
    )

    endpoint(
        "/api/docs/{id}/versions/{version}",
        HttpMethod.Get to {
            val n = param("version").toLongOrNull()?.takeIf { it >= 1 } ?: throw invalid("version must be a whole number")
            val docs = app.feed.docs
            val v = docs.version(param("id"), n)
            call.respondOwner(200, buildJsonObject { put("version", docs.versionJson(v, docs.names(listOf(v.savedBy)))) })
        },
    )

    endpoint(
        "/api/docs/{id}/restore",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val v = version(body["version"], "version")
            val base = version(body["baseVersion"], "baseVersion")
            val docs = app.feed.docs
            saving(docs) { docs.restore(param("id"), v, base, actor()) }
        },
    )
}

/** A doc is at most 1 MB of text; as JSON, with every character escaped, it may be several times that. */
private const val BODY_MAX = 8 * Docs.MAX_BYTES
