package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.AckRow
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.BlockReads
import dev.wildware.feed.server.domain.CardRefs
import dev.wildware.feed.server.domain.Cards
import dev.wildware.feed.server.domain.FeedAttachments
import dev.wildware.feed.server.domain.FieldRow
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.JsText
import dev.wildware.feed.server.domain.MessageRow
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.ProjectRow
import dev.wildware.feed.server.domain.Projects
import dev.wildware.feed.server.domain.GroupRow
import dev.wildware.feed.server.domain.groups
import dev.wildware.feed.server.domain.ReactionRow
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.UpdateRow
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.truthy
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The feed's MCP tools (spec/05-mcp.md §3, §7). Arguments arrive already checked against the advertised schema. */
fun ToolRegistry.registerFeedTools(app: App) {
    register("create_project") { call ->
        val (project, created) = app.feed.projects.create(call.text("name")!!, call.text("slug"), call.text("description"))
        // Setting up in a channel follows it, from now, as a top-level post there does. It never
        // undoes an unsubscribe or the owner's removal.
        Subscriptions(app.db, app.clock).followProject(Subscriptions.agent(call.agent.id), project.id)
        val summary = if (created) {
            "Created project \"${project.name}\" (slug ${project.slug})."
        } else {
            "Project \"${project.name}\" already exists (slug ${project.slug}); returning it unchanged."
        }
        ToolResult.ok(summary, buildJsonObject { put("project", FeedViews.project(project)); put("created", created) })
    }

    register("list_projects") { call ->
        val projects = app.feed.projects.list(call.text("status"))
        val groups = app.feed.groups.list()
        val byId = groups.associateBy { it.id }
        val summary = if (projects.isEmpty()) {
            "No channels yet. Create one with create_project."
        } else {
            "${projects.size} channel${plural(projects.size)}: ${projects.joinToString(", ") { p ->
                p.slug + (p.groupId?.let(byId::get)?.let { " (in ${it.slug})" } ?: "")
            }}."
        }
        ToolResult.ok(summary, buildJsonObject {
            // Each channel says which group (a project, in the app) it is in (migration 053).
            put("projects", JsonArray(projects.map { p ->
                JsonObject(FeedViews.project(p) + ("group" to (p.groupId?.let(byId::get)?.let(FeedViews::groupRef) ?: JsonNull)))
            }))
            put("count", projects.size)
            put("groups", JsonArray(groups.map { g ->
                buildJsonObject {
                    put("id", g.id)
                    put("slug", g.slug)
                    put("name", g.name)
                    put("status", g.status)
                    put("channels", JsonArray(projects.filter { it.groupId == g.id }.map { JsonPrimitive(it.slug) }))
                }
            }))
        })
    }

    register("set_project_theme") { call ->
        val theme = Projects.ThemeInput(
            background = call.given("background") { it.jsString },
            accent = call.given("accent") { it.jsString },
            logoMediaId = call.given("logo_media_id") { it.jsString },
            logoReplacesName = call.given("logo_replaces_name") { it.jsBoolean },
        )
        val project = app.feed.projects.update(call.requiredChannel(), Projects.Patch(theme = Given(theme)))
        ToolResult.ok("Styled project ${project.slug}.", buildJsonObject { put("project", FeedViews.project(project)) })
    }

    register("post_update") { call ->
        val update = app.feed.updates.post(
            Updates.Post(
                project = call.requiredChannel(),
                agentId = call.agent.id,
                body = call.text("body")!!,
                title = call.text("title"),
                level = call.text("level"),
                priority = call.text("priority"),
                sessionId = call.text("session_id"),
                mediaIds = call.texts("media_ids"),
                embeds = call.embeds(),
                apps = call.apps(),
                refs = refsArg(call.args),
                blocks = call.list("blocks"),
            ),
        )
        val apps = if (call.list("apps").isNullOrEmpty()) emptyList() else FeedAttachments.appsOn(app.db, CardRef("update", update.id))
        ToolResult.ok(
            "Posted a ${update.level} update to project ${call.channel()} as agent \"${call.agent.name}\".",
            buildJsonObject {
                put("update", FeedViews.update(update))
                if (apps.isNotEmpty()) put("apps", FeedViews.apps(apps))
                refsView(app, CardRef("update", update.id), call.args)?.let { put("refs", it) }
                blocksView(app, CardRef("update", update.id), call.args)?.let { put("blocks", it) }
            },
        )
    }

    register("edit_update") { call ->
        val update = app.feed.updates.edit(
            call.text("update_id")!!,
            call.agent.id,
            Updates.Edit(
                title = call.given("title") { it.jsString },
                body = call.text("body"),
                level = call.text("level"),
                priority = call.text("priority"),
                refs = refsArg(call.args),
            ),
        )
        ToolResult.ok("Edited update ${update.id}.", buildJsonObject {
            put("update", FeedViews.update(update))
            refsView(app, CardRef("update", update.id), call.args)?.let { put("refs", it) }
        })
    }

    register("set_status") { call ->
        val card = Cards.oneCard(update = call.text("update_id"), message = call.text("message_id"))
        val status = app.feed.cards.setStatus(card, call.text("status"))
        val summary = if (status == null) {
            "Unmarked. It is an ordinary card again."
        } else {
            "Marked ${status.replaceFirst('_', ' ')}. Your owner sees it on the card."
        }
        ToolResult.ok(summary, buildJsonObject { put("card", card.json); put("status", status) })
    }

    register("list_work") { call ->
        val project = call.channel()
        val projectIds = if (!project.isNullOrEmpty()) listOf(app.feed.projects.resolve(project).id) else app.feed.messages.projectsForAgent(call.agent.id)
        val statuses = call.texts("status")
        // The statuses are checked first, as `listWorkInScope` does; the enum schema makes that moot.
        val work = app.feed.cards.listWork(projectIds, statuses, call.args["limit"].jsNumber?.toInt() ?: 50)
        val counts = LinkedHashMap<String, Int>()
        for (item in work) counts[item.status] = (counts[item.status] ?: 0) + 1
        val summary = if (work.isEmpty()) {
            "No work matches. Nothing is marked in that state."
        } else {
            "${work.size} card${plural(work.size)}: ${counts.entries.joinToString(", ") { (status, n) -> "$n ${status.replaceFirst('_', ' ')}" }}."
        }
        ToolResult.ok(summary, buildJsonObject {
            put("work", JsonArray(work.map { item ->
                buildJsonObject {
                    put("kind", item.kind)
                    put("id", item.id)
                    put("project_id", item.projectId)
                    put("status", item.status)
                    put("title", item.title)
                    put("summary", item.summary)
                    put("author", item.author)
                    put("created_at", Wire.isoOf(item.createdAt))
                    put("status_at", item.statusAt?.let(Wire::isoOf))
                }
            }))
            put("count", work.size)
        })
    }

    register("list_tags") { call ->
        val tags = app.feed.tags.list(call.channel())
        val summary = if (tags.isEmpty()) {
            "No tags yet. tag_card with create: true decides the first one."
        } else {
            "${tags.size} tag${plural(tags.size)}: ${tags.joinToString(", ") { it.name }}."
        }
        ToolResult.ok(summary, buildJsonObject {
            put("tags", JsonArray(tags.map { tag ->
                buildJsonObject {
                    put("id", tag.id)
                    put("project_id", tag.projectId)
                    put("name", tag.name)
                    put("colour", tag.colour)
                    put("agent_id", tag.agentId)
                    put("created_at", Wire.isoOf(tag.createdAt))
                }
            }))
            put("count", tags.size)
        })
    }

    register("tag_card") { call ->
        val card = call.anyCard()
        val (tag, on) = app.feed.tags.tagCard(card, call.text("tag")!!, call.author, call.args["on"].jsBoolean, call.args["create"].jsBoolean == true)
        val summary = if (on) "Tagged ${JsText.quote(tag.name)}. Your owner sees it on the card." else "Took ${JsText.quote(tag.name)} off."
        ToolResult.ok(summary, buildJsonObject {
            put("card", card.json)
            put("tag", buildJsonObject { put("id", tag.id); put("name", tag.name); put("colour", tag.colour) })
            put("on", on)
        })
    }

    register("set_card_field") { call ->
        val card = call.anyCard()
        val name = call.text("name")!!
        val (fields, set) = app.feed.fields.set(card, name, call.text("value"), call.author)
        val summary = if (set) {
            "Noted ${JsText.quote(name)} on the card. Your owner can see it under Details."
        } else {
            "Took ${JsText.quote(name)} off the card."
        }
        ToolResult.ok(summary, buildJsonObject {
            put("card", card.json)
            put("fields", JsonArray(fields.map(FeedViews::field)))
            put("set", set)
        })
    }

    register("get_card_fields") { call ->
        val card = call.anyCard()
        val fields = app.feed.fields.on(card)
        val summary = if (fields.isEmpty()) {
            "Nothing noted on that card yet."
        } else {
            "${fields.size} field${plural(fields.size)}: ${fields.joinToString(", ") { it.name }}."
        }
        ToolResult.ok(summary, buildJsonObject {
            put("card", card.json)
            put("fields", JsonArray(fields.map(FeedViews::field)))
            put("count", fields.size)
        })
    }

    register("get_messages") { call ->
        val messages = app.feed.messages
        val page = messages.read(call.agent.id, call.text("since"), call.channel(), call.args["mark_read"].jsBoolean)
        val count = page.messages.size
        val attachments = Attachments.forMessages(app, page.messages.map { it.id })
        val refs = CardRefs(app.db, app.clock).summaries("message", page.messages.map { it.id }, reader = call.author)
        val read = when {
            count == 0 -> "No new messages. Carry on."
            page.unread == 0L -> "$count new message${plural(count)}, oldest first."
            else -> "$count new message${plural(count)}, oldest first. ${page.unread} still unread; call again for the rest."
        }
        val summary = if (count == 0 || attachments.notes.isEmpty()) read else read + "\n" + attachments.notes.joinToString("\n")
        ToolResult.ok(summary, buildJsonObject {
            put("messages", JsonArray(page.messages.map { message ->
                val parent = messages.quoted(message)
                val name = messages.authorName(message.author, message.userId)
                buildJsonObject {
                    FeedViews.message(message).forEach { (k, v) -> put(k, v) }
                    if (name != null) put("author_name", name)
                    // On a team, which person (migration 055); a team of one adds nothing.
                    if (name != null && message.author == Messages.HUMAN) put("author_user_id", message.userId)
                    if (parent != null) {
                        put("in_reply_to", buildJsonObject {
                            put("id", parent.id); put("author", parent.author)
                            if (parent.author == Messages.HUMAN) messages.authorName(parent.author, parent.userId)?.let { put("author_name", it) }
                            put("body", parent.body); put("truncated", parent.truncated)
                        })
                    }
                    attachments.views[message.id]?.let { put("attachments", JsonArray(it)) }
                    refs[message.id]?.let { put("refs", JsonArray(it.map(FeedViews::ref))) }
                }
            }))
            put("count", count)
            put("cursor", page.cursor)
            put("unread", page.unread)
            put("marked_read", page.markedRead)
        }, attachments.blocks)
    }

    register("acknowledge") { call ->
        val card = Cards.oneCard(message = call.text("message_id"), update = call.text("update_id"), request = call.text("request_id"))
        val ack = app.feed.cards.acknowledge(call.agent.id, card, call.text("state")!!)
        val summary = when (ack.state) {
            "done" -> "Marked done. Your owner sees a tick on it."
            "read" -> "Marked read. Your owner sees that it reached you."
            else -> "Acknowledged. Your owner sees that you are on it."
        }
        ToolResult.ok(summary, buildJsonObject { put("ack", FeedViews.ack(ack)) })
    }

    register("post_message") { call ->
        val message = app.feed.messages.post(
            Messages.Post(
                author = call.author,
                body = call.text("body")!!,
                project = call.channel(),
                updateId = call.text("update_id"),
                replyTo = call.text("message_id"),
                answers = call.text("answers"),
                requestId = call.text("request_id"),
                mediaIds = call.texts("media_ids"),
                embeds = call.embeds(),
                apps = call.apps(),
                refs = refsArg(call.args),
                blocks = call.list("blocks"),
            ),
        )
        val apps = if (call.list("apps").isNullOrEmpty()) emptyList() else FeedAttachments.appsOn(app.db, CardRef("message", message.id))
        ToolResult.ok("Replied. It is on your owner’s screen now.", buildJsonObject {
            put("message", FeedViews.message(message))
            if (apps.isNotEmpty()) put("apps", FeedViews.apps(apps))
            refsView(app, CardRef("message", message.id), call.args)?.let { put("refs", it) }
            blocksView(app, CardRef("message", message.id), call.args)?.let { put("blocks", it) }
        })
    }

    register("edit_message") { call ->
        val message = app.feed.messages.edit(call.text("message_id")!!, call.author, call.text("body")!!, refsArg(call.args))
        ToolResult.ok("Edited. Your owner sees the new text.", buildJsonObject {
            put("message", FeedViews.message(message))
            refsView(app, CardRef("message", message.id), call.args)?.let { put("refs", it) }
        })
    }

    register("delete_message") { call ->
        val message = app.feed.messages.delete(call.text("message_id")!!, call.author)
        ToolResult.ok("Unsent. It is gone from your owner’s screen.", buildJsonObject { put("message", FeedViews.message(message)) })
    }

    register("react") { call ->
        val card = Cards.oneCard(message = call.text("message_id"), update = call.text("update_id"), request = call.text("request_id"))
        val (reactions, on) = app.feed.cards.react(card, call.author, call.text("emoji")!!, call.args["on"].jsBoolean)
        val summary = if (on) "Reacted. Your owner sees it on the card." else "Reaction removed. It is gone from the card."
        ToolResult.ok(summary, buildJsonObject {
            put("reactions", JsonArray(reactions.map(FeedViews::reaction)))
            put("on", on)
        })
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/** A write's blocks (migration 057), for its result: only when the call gave `blocks`. */
internal fun blocksView(app: App, card: CardRef, args: JsonObject): JsonArray? {
    if ((args["blocks"] as? JsonArray).isNullOrEmpty()) return null
    return JsonArray(BlockReads(app.db, app.clock).viewsOn(card))
}

/** The `refs` argument (migration 056): `[{ kind, id }]`, or null when it was left out. */
internal fun refsArg(args: JsonObject): List<CardRef>? = (args["refs"] as? JsonArray)?.map {
    val o = it as JsonObject
    CardRef(o["kind"].jsString ?: "", o["id"].jsString ?: "")
}

/** What a write now points at, for its result: only when the call gave `refs`. */
internal fun refsView(app: App, card: CardRef, args: JsonObject): JsonArray? {
    if (args["refs"] !is JsonArray) return null
    val summaries = CardRefs(app.db, app.clock).summaries(card.kind, listOf(card.id))[card.id].orEmpty()
    return JsonArray(summaries.map(FeedViews::ref))
}

private fun ToolCall.text(key: String): String? = args[key].jsString

private fun ToolCall.list(key: String): JsonArray? = args[key] as? JsonArray

private fun ToolCall.texts(key: String): List<String>? = list(key)?.map { it.jsString ?: "" }

/** A key that was sent, null included, as opposed to one left out. */
private fun <T> ToolCall.given(key: String, read: (JsonElement) -> T?): Given<T?>? =
    args[key]?.let { Given(if (it is JsonNull) null else read(it)) }

private fun ToolCall.anyCard(): CardRef =
    Cards.oneCard(update = text("update_id"), message = text("message_id"), request = text("request_id"))

private fun ToolCall.embeds(): List<FeedAttachments.EmbedInput>? = list("embeds")?.map {
    val o = it as JsonObject
    FeedAttachments.EmbedInput(o["type"].jsString ?: "", o["data"], o["source_url"])
}

private fun ToolCall.apps(): List<FeedAttachments.AppInput>? = list("apps")?.map {
    val o = it as JsonObject
    FeedAttachments.AppInput(o["app"], o["data"], o["title"])
}

/** The MCP shapes (spec/05 §3.1): snake_case, ISO times, keys in the TS order. */
internal object FeedViews {
    /** A group (a project, in the app), as a channel names it. */
    fun groupRef(g: GroupRow): JsonObject = buildJsonObject { put("id", g.id); put("slug", g.slug); put("name", g.name) }

    fun project(p: ProjectRow): JsonObject = buildJsonObject {
        put("id", p.id)
        put("slug", p.slug)
        put("name", p.name)
        put("description", p.description)
        put("status", p.status)
        put("pinned", p.pinned)
        put("created_at", Wire.isoOf(p.createdAt))
        put("updated_at", Wire.isoOf(p.updatedAt))
        put("theme", theme(p.theme))
    }

    /** Only the set keys among background, accent, logo; `logoReplacesName` is not shown. */
    private fun theme(theme: JsonElement): JsonElement {
        if (!theme.truthy()) return JsonNull
        val t = theme as? JsonObject ?: return JsonObject(emptyMap())
        return buildJsonObject {
            if (t["background"].truthy()) put("background", t["background"]!!)
            if (t["accent"].truthy()) put("accent", t["accent"]!!)
            if (t["logoMediaId"].truthy()) put("logo_media_id", t["logoMediaId"]!!)
        }
    }

    fun update(u: UpdateRow): JsonObject = buildJsonObject {
        put("id", u.id)
        put("project_id", u.projectId)
        put("agent_id", u.agentId)
        put("session_id", u.sessionId)
        put("title", u.title)
        put("level", u.level)
        put("pinned", u.pinned)
        put("body_chars", u.body.length)
        put("created_at", Wire.isoOf(u.createdAt))
        put("edited_at", u.editedAt?.let(Wire::isoOf))
        put("status", u.status)
    }

    fun message(m: MessageRow): JsonObject = buildJsonObject {
        put("id", m.id)
        put("project_id", m.projectId)
        put("update_id", m.updateId)
        put("task_id", m.taskId)
        put("reply_to", m.replyTo)
        m.answers?.let { put("answers", it) }
        m.requestId?.let { put("request_id", it) }
        put("author", m.author)
        put("body", m.body)
        put("created_at", Wire.isoOf(m.createdAt))
        m.deletedAt?.let { put("deleted_at", Wire.isoOf(it)) }
        m.editedAt?.let { put("edited_at", Wire.isoOf(it)) }
    }

    fun reaction(r: ReactionRow): JsonObject = buildJsonObject {
        put("id", r.id)
        put("card_kind", r.card["kind"]!!)
        put("card_id", r.card["id"]!!)
        put("message_id", r.messageId)
        put("actor", r.actor)
        put("emoji", r.emoji)
        put("created_at", Wire.isoOf(r.createdAt))
    }

    fun ack(a: AckRow): JsonObject = buildJsonObject {
        put("id", a.id)
        put("card_kind", a.card["kind"]!!)
        put("card_id", a.card["id"]!!)
        put("message_id", a.messageId)
        put("task_id", a.taskId)
        put("state", a.state)
        put("created_at", Wire.isoOf(a.createdAt))
        put("updated_at", Wire.isoOf(a.updatedAt))
    }

    fun field(f: FieldRow): JsonObject = buildJsonObject {
        put("name", f.name)
        put("value", f.value)
        put("author", f.author)
        put("created_at", Wire.isoOf(f.createdAt))
        put("updated_at", Wire.isoOf(f.updatedAt))
    }

    /** A card ref (migration 056) as an agent reads it, from the owner's summary. */
    fun ref(summary: JsonObject): JsonObject = buildJsonObject {
        fun text(key: String) = (summary[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        put("kind", summary["kind"]!!)
        put("id", summary["id"]!!)
        put("project_id", text("projectId"))
        put("author", text("author"))
        text("title")?.let { put("title", it) }
        text("text")?.let { put("text", it) }
        (summary["mediaCount"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }?.let { put("media_count", it) }
        put("removed", summary["deleted"]!!)
    }

    fun apps(views: List<FeedAttachments.AppViewRef>) = JsonArray(views.map { v ->
        buildJsonObject { put("view_id", v.id); put("uri", v.uri); put("title", v.title) }
    })
}
