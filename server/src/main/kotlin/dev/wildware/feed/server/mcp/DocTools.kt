package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Docs
import dev.wildware.feed.server.domain.DocLinks
import dev.wildware.feed.server.domain.JsText
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.docs
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A channel's docs for agents (migration 076, spec/17): list, read, search, create and edit. */
fun ToolRegistry.registerDocTools(app: App) {
    fun who(names: Map<String, Docs.Who>, principal: String): JsonObject =
        names[principal]?.let { buildJsonObject { put("kind", it.kind); put("name", it.name) } } ?: buildJsonObject { put("name", "someone") }

    fun channelSlug(projectId: String): String = app.feed.projects.byId(projectId)?.slug ?: projectId

    fun summary(doc: Docs.DocRow, names: Map<String, Docs.Who>): JsonObject = buildJsonObject {
        put("id", doc.id)
        put("channel", channelSlug(doc.projectId))
        put("title", doc.title)
        put("index", Docs.isIndex(doc))
        put("version", doc.version)
        put("size", Docs.utf8Size(doc.body))
        put("updated_at", Wire.isoOf(doc.updatedAt))
        put("updated_by", who(names, doc.updatedBy))
    }

    /** The doc a call names: doc_id, or a title in a channel. */
    fun ToolCall.doc(): Docs.DocRow {
        val id = args["doc_id"].jsString?.takeIf { it.isNotBlank() }
        val title = args["title"].jsString?.takeIf { it.isNotBlank() }
        val docs = app.feed.docs
        return when {
            id != null -> docs.get(id)
            title != null -> {
                val channel = channel() ?: throw invalid("name a doc by doc_id, or by title with its channel")
                docs.byTitle(channel, title) ?: throw notFound("no doc called ${JsText.quote(title.trim())} in $channel: list_docs shows the docs there, or create_doc makes it")
            }
            else -> throw invalid("name a doc by doc_id, or by title with its channel")
        }
    }

    fun ToolCall.baseVersion(): Long {
        val n = args["base_version"].jsNumber ?: throw invalid("base_version is required: the version you read (read_doc gives it)")
        if (!Js.isInteger(n) || n < 1) throw invalid("base_version must be a whole number")
        return n.toLong()
    }

    fun conflict(e: Docs.Conflict, names: Map<String, Docs.Who>): ToolResult = ToolResult.failed(
        "conflict",
        "${e.message}. It was last saved by ${names[e.current.updatedBy]?.name ?: "someone"} at ${Wire.isoOf(e.current.updatedAt)}. Nothing was saved.",
    )

    register("list_docs") { call ->
        val docs = app.feed.docs
        val channel = call.requiredChannel()
        val list = docs.list(channel)
        val names = docs.namesFor(list)
        val hasIndex = list.any { Docs.isIndex(it) }
        val count = "${list.size} doc${if (list.size == 1) "" else "s"}"
        val text = when {
            hasIndex -> "$count: the Index first (read it to learn what this channel is for), then newest change first."
            list.isEmpty() -> "No docs in this channel yet, and no Index. ${noIndex(docs.canEdit(app.feed.projects.resolve(channel).id))}"
            else -> "$count, newest change first. This channel has no Index yet. ${noIndex(docs.canEdit(app.feed.projects.resolve(channel).id))}"
        }
        ToolResult.ok(text, buildJsonObject {
            put("docs", JsonArray(list.map { summary(it, names) }))
            put("can_edit", app.feed.projects.resolve(channel).let { docs.canEdit(it.id) })
            if (!hasIndex) put("index_template", docs.indexDraft(channel))
        })
    }

    register("read_doc") { call ->
        val docs = app.feed.docs
        val onlyChannel = call.args["doc_id"].jsString.isNullOrBlank() && call.args["title"].jsString.isNullOrBlank()
        val channel = call.channel()
        if (onlyChannel && channel == null) throw invalid("name a doc by doc_id, by title with its channel, or a channel alone for its Index")
        if (onlyChannel && docs.index(channel!!) == null) {
            val canEdit = docs.canEdit(app.feed.projects.resolve(channel).id)
            return@register ToolResult.ok("This channel has no Index yet. ${noIndex(canEdit)}", buildJsonObject {
                put("doc", JsonNull)
                put("no_index", true)
                put("index_template", docs.indexDraft(channel))
                put("can_edit", canEdit)
            })
        }
        val doc = if (onlyChannel) docs.index(channel!!)!! else call.doc()
        val names = docs.namesFor(listOf(doc))
        val backlinks = docs.backlinks(doc)
        val links = DocLinks.parse(doc.body).map { it.target }.distinctBy { Docs.key(it) }.take(100).map { target ->
            val found = runCatching { docs.byTitle(doc.projectId, target) }.getOrNull()
            buildJsonObject {
                put("title", found?.title ?: target)
                put("id", found?.id?.let(::JsonPrimitive) ?: JsonNull)
                put("exists", found != null)
            }
        }
        ToolResult.ok("\"${doc.title}\" at version ${doc.version}. Pass base_version ${doc.version} to edit_doc.", buildJsonObject {
            put("doc", buildJsonObject {
                summary(doc, names).forEach { (k, v) -> put(k, v) }
                put("created_at", Wire.isoOf(doc.createdAt))
                put("created_by", who(names, doc.createdBy))
                put("body", doc.body)
            })
            put("links", JsonArray(links))
            put("linked_from", JsonArray(backlinks.map { buildJsonObject { put("id", it.id); put("title", it.title) } }))
            put("can_edit", docs.canEdit(doc.projectId))
        })
    }

    register("search_docs") { call ->
        val docs = app.feed.docs
        val query = call.args["query"].jsString ?: throw invalid("query is required")
        val limit = call.args["limit"].jsNumber?.toInt() ?: 20
        val found = docs.search(call.channel(), query, limit)
        val names = docs.namesFor(found)
        val needle = query.trim().lowercase()
        ToolResult.ok(if (found.isEmpty()) "No doc matches." else "${found.size} match${if (found.size == 1) "" else "es"}, title matches first.", buildJsonObject {
            put("docs", JsonArray(found.map { doc ->
                buildJsonObject {
                    summary(doc, names).forEach { (k, v) -> put(k, v) }
                    snippet(doc.body, needle)?.let { put("snippet", it) }
                }
            }))
        })
    }

    register("create_doc") { call ->
        val docs = app.feed.docs
        val doc = docs.create(call.requiredChannel(), call.args["title"].jsString ?: throw invalid("title is required"), call.args["body"].jsString ?: "", call.author)
        ToolResult.ok("Created \"${doc.title}\" at version 1. People see it in the channel's Docs tab.", buildJsonObject {
            put("doc", summary(doc, docs.namesFor(listOf(doc))))
        })
    }

    register("edit_doc") { call ->
        val docs = app.feed.docs
        val base = call.baseVersion()
        val body = call.args["body"].jsString
        val rename = call.args["new_title"].jsString?.takeIf { it.isNotBlank() }
        val edits = (call.args["edits"] as? JsonArray)?.map { e ->
            val o = e as? JsonObject ?: throw invalid("each edit is { old_str, new_str }")
            Docs.Edit(o["old_str"].jsString ?: throw invalid("each edit needs old_str"), o["new_str"].jsString ?: throw invalid("each edit needs new_str"))
        }
        if (edits != null && body != null) throw invalid("pass edits or body, not both")
        if (edits == null && body == null && rename == null) throw invalid("pass edits (find and replace), body (the whole new text) or new_title")
        val doc = call.doc()
        val saved = try {
            when {
                edits != null -> docs.edit(doc.id, base, edits, call.author, rename)
                else -> docs.save(doc.id, base, rename, body, call.author)
            }
        } catch (e: Docs.Conflict) {
            return@register conflict(e, docs.namesFor(listOf(e.current)))
        }
        val text = if (saved.version == base) "Nothing changed: the doc already says that (still version ${saved.version})."
        else "Saved \"${saved.title}\" as version ${saved.version}. Pass base_version ${saved.version} for your next edit."
        ToolResult.ok(text, buildJsonObject { put("doc", summary(saved, docs.namesFor(listOf(saved)))) })
    }
}

/** What to do about a channel with no Index, for someone who [canEdit] it or may only read it. */
private fun noIndex(canEdit: Boolean): String =
    "index_template is a first one, from the channel's name and description, linking its docs. Nothing is saved until someone saves it. " +
        if (canEdit) "Fill it in and save it with create_doc({ channel, title: \"Index\", body })." else "You may only read this channel, so ask someone who can post here to write it."

/** A little of [body] around the first place [needle] is found, on one line. */
private fun snippet(body: String, needle: String): String? {
    val at = body.lowercase().indexOf(needle)
    if (at < 0) return null
    val start = (at - 60).coerceAtLeast(0)
    val end = (at + needle.length + 60).coerceAtMost(body.length)
    val core = body.substring(start, end).replace(Regex("\\s+"), " ").trim()
    return (if (start > 0) "…" else "") + core + (if (end < body.length) "…" else "")
}
