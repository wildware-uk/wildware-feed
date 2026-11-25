package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.BlockReads
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.Groups
import dev.wildware.feed.server.domain.groups
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Projects
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.agentMembers
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.domain.Profiles
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.jsTrim
import dev.wildware.feed.server.domain.Cards
import dev.wildware.feed.server.domain.Board
import dev.wildware.feed.server.domain.board
import dev.wildware.feed.server.domain.Assignees
import dev.wildware.feed.server.domain.assignees
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.Js
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The owner's writes on the feed (spec/02-owner-api-feed.md). */
fun Route.feedRoutes(app: App) {
    fun RoutingContext.param(name: String) = call.parameters[name] ?: ""

    endpoint(
        "/api/projects",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val name = text(body["name"], "name")
            val description = if ("description" in body) nullableText(body["description"], "description") else null
            val slug = if ("slug" in body) text(body["slug"], "slug") else null
            val (project, created) = app.feed.projects.create(name, slug, description)
            call.respondOwner(if (created) 201 else 200, buildJsonObject { put("project", project.json); put("created", created) })
        },
    )

    endpoint(
        "/api/projects/{reference}",
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val patch = Projects.Patch(
                name = if ("name" in body) text(body["name"], "name") else null,
                description = if ("description" in body) Given(nullableText(body["description"], "description")) else null,
                slug = if ("slug" in body) text(body["slug"], "slug") else null,
                status = if ("status" in body) projectStatus(body["status"]) else null,
                pinned = if ("pinned" in body) flag(body["pinned"], "pinned") else null,
                theme = if ("theme" in body) Given(themePatch(body["theme"]!!)) else null,
                board = if ("board" in body) Given(body["board"]!!) else null,
                group = if ("groupId" in body) Given(nullableText(body["groupId"], "groupId")) else null,
            )
            call.respondOwner(200, buildJsonObject { put("project", app.feed.projects.update(param("reference"), patch).json) })
        },
    )

    // Migration 069: the order people drag channels into, and into which project.
    endpoint(
        "/api/projects/order",
        HttpMethod.Put to {
            val body = call.jsonObjectBody()
            val ids = idList(body["ids"], "ids")
            val group = if ("groupId" in body) Given(nullableText(body["groupId"], "groupId")) else null
            val projects = app.feed.projects.arrange(ids, group)
            call.respondOwner(200, buildJsonObject { put("projects", JsonArray(projects.map { it.json })) })
        },
    )

    endpoint(
        "/api/groups/order",
        HttpMethod.Put to {
            val ids = idList(call.jsonObjectBody()["ids"], "ids")
            call.respondOwner(200, buildJsonObject { put("groups", JsonArray(app.feed.groups.arrange(ids).map { it.json })) })
        },
    )

    endpoint(
        "/api/projects/{reference}/tags",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val name = body["name"].jsString ?: throw invalid("name must be a string")
            val colour = body["colour"]
            if (colour != null && colour !is JsonNull && colour.jsString == null) throw invalid("colour must be a palette name, or null")
            val tag = app.feed.tags.define(param("reference"), name, colour.jsString, null)
            call.respondOwner(200, buildJsonObject { put("tag", tag.json) })
        },
    )

    endpoint(
        "/api/tags/{id}",
        HttpMethod.Patch to {
            val body = call.lenientObjectBody()
            val name = body["name"]
            if (name != null && name.jsString == null) throw invalid("name must be a string")
            val colour = body["colour"]
            if (colour != null && colour !is JsonNull && colour.jsString == null) throw invalid("colour must be a palette name, or null")
            val tag = app.feed.tags.rename(param("id"), name?.jsString, colour?.let { Given(it.jsString) })
            call.respondOwner(200, buildJsonObject { put("tag", tag.json) })
        },
        HttpMethod.Delete to {
            val (id, projectId) = app.feed.tags.retire(param("id"))
            call.respondOwner(200, buildJsonObject { put("tag", buildJsonObject { put("id", id); put("projectId", projectId) }) })
        },
    )

    endpoint(
        "/api/updates/{id}",
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            if (body.keys.toList() != listOf("pinned")) throw invalid("an update patch changes pinned and nothing else")
            val pinned = flag(body["pinned"], "pinned")
            call.respondOwner(200, buildJsonObject { put("update", app.feed.updates.setPinned(param("id"), pinned).json) })
        },
        HttpMethod.Delete to {
            call.respondOwner(200, buildJsonObject { put("update", app.feed.updates.delete(param("id")).json) })
        },
    )

    endpoint(
        "/api/messages",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val text = text(body["body"], "body")
            fun optional(key: String) = if (key in body) text(body[key], key) else null
            val updateId = optional("update")
            val replyTo = optional("replyTo")
            val project = optional("project")
            val answers = optional("answers")
            val requestId = optional("request")
            val sendKey = optional("sendKey")?.also {
                if (it.isEmpty() || it.length > SEND_KEY_MAX || it.any { c -> c < ' ' || c > '~' }) {
                    throw invalid("sendKey must be 1 to $SEND_KEY_MAX printable ASCII characters")
                }
            }
            val source = optional("source")
            val mediaIds = if ("mediaIds" in body) {
                val list = body["mediaIds"] as? JsonArray ?: throw invalid("mediaIds must be a list")
                list.mapIndexed { i, id -> text(id, "mediaIds[$i]") }
            } else {
                null
            }
            val message = app.feed.messages.post(
                Messages.Post(
                    author = Messages.HUMAN, body = text, project = project, updateId = updateId, replyTo = replyTo,
                    answers = answers, requestId = requestId, mediaIds = mediaIds, userId = call.person.id, sendKey = sendKey,
                    source = source, guardRepeats = true,
                ),
            )
            call.respondOwner(201, buildJsonObject { put("message", message.json) })
        },
    )

    endpoint(
        "/api/messages/{id}",
        // Kotlin only: a person edits their own message, as an agent does with edit_message.
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val text = text(body["body"], "body")
            call.respondOwner(200, buildJsonObject { put("message", app.feed.messages.edit(param("id"), Messages.HUMAN, text, person = call.person).json) })
        },
        HttpMethod.Delete to {
            call.respondOwner(200, buildJsonObject { put("message", app.feed.messages.delete(param("id"), Messages.HUMAN, call.person).json) })
        },
    )

    endpoint(
        "/api/messages/{id}/reactions",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val emoji = text(body["emoji"], "emoji")
            val (reactions, on) = app.feed.cards.react(CardRef("message", param("id")), Messages.HUMAN, emoji, body["on"].jsBoolean, call.person.id)
            call.respondOwner(200, buildJsonObject { put("reactions", JsonArray(reactions.map { it.json })); put("on", on) })
        },
    )

    fun RoutingContext.card(): CardRef {
        val kind = param("kind")
        if (kind !in CardRef.KINDS) throw invalid("no such kind of card: $kind")
        val id = param("id")
        if (id.isEmpty()) throw invalid("which card?")
        return CardRef(kind, id)
    }

    // Media retention (migration 048): how many days media of which kinds is kept. Owner only.
    endpoint(
        "/api/settings/media-retention",
        HttpMethod.Get to { call.respondOwner(200, app.retention.settings().json()) },
        HttpMethod.Put to { call.respondOwner(200, app.retention.set(call.lenientObjectBody()).json()) },
    )

    // The board (migration 070): cards with a work state, a column per state, in a channel, a
    // project (group) or everything the viewer can read.
    endpoint(
        "/api/board",
        HttpMethod.Get to {
            val params = call.request.queryParameters
            val board = app.feed.board
            val scope = board.scope(params["channel"], params["group"])
            val asked = Js.number(params["limit"] ?: "")
            val limit = if (Js.isInteger(asked) && asked > 0) minOf(asked, Board.PAGE_MAX.toDouble()).toInt() else Board.PAGE
            val column = params["column"]?.takeIf { it.isNotEmpty() }
            val removed = params["removed"] == "1" || params["removed"] == "true"
            // Assigned to me, to nobody, or to one person or agent (migration 074).
            val assigned = params["assigned"]?.takeIf { it.isNotBlank() }?.let { asked ->
                when (asked) {
                    Board.UNASSIGNED -> Board.UNASSIGNED
                    Assignees.ME -> Viewer.currentOrNull?.principal ?: Viewer.MEMBER + call.person.id
                    else -> app.feed.assignees.resolve(asked, Viewer.MEMBER + call.person.id).principal
                }
            }
            // Only cards whose pull request needs someone: CI failing or changes asked for (migration 080).
            val attention = params["attention"] == "1" || params["attention"] == "true"
            call.respondOwner(200, board.read(scope, column, Board.Cursor.parse(params["after"]), limit, removed, assigned, attention))
        },
    )

    // Who a card is assigned to (migration 074): read them with everyone who could be, or change them.
    endpoint(
        "/api/cards/{kind}/{id}/assignees",
        HttpMethod.Get to {
            val card = card()
            val assignees = app.feed.assignees
            val now = assignees.on(card)
            call.respondOwner(200, buildJsonObject {
                put("card", card.json)
                put("assignees", JsonArray(now.map { it.json }))
                put("candidates", JsonArray(assignees.candidates(card).map { (who, canRead) -> JsonObject(who.json + ("canRead" to JsonPrimitive(canRead))) }))
                put("max", Assignees.MAX)
            })
        },
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val names = (body["assignees"] as? JsonArray)?.map { it.jsString ?: throw invalid("assignees must be a list of names or ids") }
                ?: throw invalid("assignees must be a list of names or ids")
            val mode = body["mode"].jsString ?: Assignees.MODE_SET
            val card = card()
            val change = app.feed.assignees.change(card, names, mode, Viewer.MEMBER + call.person.id, app.teams.address())
            call.respondOwner(200, buildJsonObject {
                put("card", card.json)
                put("assignees", JsonArray(change.assignees.map { it.json }))
                put("added", JsonArray(change.added.map { it.json }))
                put("removed", JsonArray(change.removed.map { it.json }))
                put("status", change.status)
            })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/board",
        HttpMethod.Post to {
            val removed = call.lenientObjectBody()["removed"].jsBoolean ?: throw invalid("removed must be true or false")
            val card = card()
            val result = app.feed.board.setRemoved(card, removed)
            call.respondOwner(200, buildJsonObject { put("card", card.json); put("removed", result) })
        },
    )

    // How long a finished card stays on the board: 1, 3, 7 or 30 days, or null for never. The team's.
    endpoint(
        "/api/settings/board",
        HttpMethod.Get to { call.respondOwner(200, buildJsonObject { put("autoRemoveDays", app.feed.board.autoRemoveDays()) }) },
        HttpMethod.Put to {
            val raw = call.lenientObjectBody()["autoRemoveDays"]
            val days = when {
                raw == null -> throw invalid("autoRemoveDays is required: 1, 3, 7 or 30, or null for never")
                raw is JsonNull -> null
                raw is JsonPrimitive && !raw.isString && raw.content.toIntOrNull() != null -> raw.content.toInt()
                else -> throw invalid("autoRemoveDays must be 1, 3, 7 or 30, or null for never")
            }
            call.respondOwner(200, buildJsonObject { put("autoRemoveDays", app.feed.board.setAutoRemoveDays(days)) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/pin",
        HttpMethod.Post to {
            val pinned = call.lenientObjectBody()["pinned"].jsBoolean ?: throw invalid("pinned must be true or false")
            val card = card()
            val result = app.feed.cards.pin(card, pinned)
            call.respondOwner(200, buildJsonObject { put("card", card.json); put("pinned", result) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/status",
        HttpMethod.Post to {
            val raw = call.lenientObjectBody()["status"]
            val status = when {
                raw is JsonNull -> null
                raw.jsString in Cards.STATUSES -> raw.jsString
                else -> throw invalid("status must be one of the five work states, or null")
            }
            val card = card()
            val result = app.feed.cards.setStatus(card, status)
            call.respondOwner(200, buildJsonObject { put("card", card.json); put("status", result) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/reactions",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val emoji = body["emoji"].jsString ?: throw invalid("emoji must be a string")
            val (reactions, on) = app.feed.cards.react(card(), Messages.HUMAN, emoji, body["on"].jsBoolean, call.person.id)
            call.respondOwner(200, buildJsonObject { put("reactions", JsonArray(reactions.map { it.json })); put("on", on) })
        },
    )

    // Blocks (migration 057, docs/BLOCKS.md): a card's blocks as they are now, a tick and a vote.
    endpoint(
        "/api/cards/{kind}/{id}/blocks",
        HttpMethod.Get to {
            val card = card()
            app.feed.cards.resolve(card)
            call.respondOwner(200, buildJsonObject {
                put("card", card.json)
                put("blocks", JsonArray(BlockReads(app.db, app.clock).viewsOn(card)))
            })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/blocks/{block}/check",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val item = body["item"].jsString?.takeIf { it.isNotEmpty() } ?: throw invalid("item must be the id of a line")
            val done = flag(body["done"], "done")
            val block = app.feed.blocks.tick(card(), param("block"), item, done, call.person.id)
            call.respondOwner(200, buildJsonObject { put("block", block) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/blocks/{block}/vote",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val options = (body["options"] as? JsonArray)?.map { it.jsString ?: throw invalid("options must be a list of option ids") }
                ?: throw invalid("options must be a list of option ids")
            val block = app.feed.blocks.vote(card(), param("block"), options, call.person.id)
            call.respondOwner(200, buildJsonObject { put("block", block) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/tags",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val tag = body["tag"].jsString?.takeIf { it.jsTrim().isNotEmpty() } ?: throw invalid("tag must be a tag id, or the word itself")
            val card = card()
            val (row, on) = app.feed.tags.tagCard(card, tag, Messages.HUMAN, body["on"].jsBoolean, body["create"].jsBoolean == true)
            call.respondOwner(200, buildJsonObject { put("card", card.json); put("tag", row.json); put("on", on) })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/fields",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val name = body["name"].jsString ?: throw invalid("name must be a string")
            val raw = body["value"]
            if (raw !is JsonNull && raw.jsString == null) throw invalid("value must be a string, or null to remove the field")
            val card = card()
            val (fields, set) = app.feed.fields.set(card, name, raw.jsString, Messages.HUMAN)
            call.respondOwner(200, buildJsonObject {
                put("card", card.json); put("fields", JsonArray(fields.map { it.json })); put("set", set)
            })
        },
    )

    // Subscriptions (migration 052): who follows a thread, and what each person follows (migration 055).
    // The key stays "owner": it is the caller's own view, as it was the one owner's.
    fun ownerView(subs: Subscriptions, who: String, thread: Subscriptions.Thread, projectId: String?) = buildJsonObject {
        val state = subs.threadState(who, thread)
        put("following", state ?: (subs.level(who, projectId) == Subscriptions.ALL))
        put("chosen", state != null)
    }

    fun RoutingContext.who() = People.subscriber(call.person.id)

    fun RoutingContext.changed() = app.bus.publish("subscriptions.updated", People.followsChanged(app.db, call.person.id))

    endpoint(
        "/api/cards/{kind}/{id}/followers",
        HttpMethod.Get to {
            val subs = Subscriptions(app.db, app.clock)
            val (thread, projectId) = subs.threadAt(card())
            call.respondOwner(200, buildJsonObject {
                put("thread", buildJsonObject { put("kind", thread.kind); put("id", thread.id) })
                put("followers", JsonArray(subs.followers(thread).map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
                put("owner", ownerView(subs, who(), thread, projectId))
            })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/follow",
        HttpMethod.Put to {
            val following = flag(call.jsonObjectBody()["following"], "following")
            val subs = Subscriptions(app.db, app.clock)
            val (thread, projectId) = subs.threadAt(card())
            if (subs.setFollow(who(), thread, projectId, following)) changed()
            call.respondOwner(200, buildJsonObject {
                put("thread", buildJsonObject { put("kind", thread.kind); put("id", thread.id) })
                put("owner", ownerView(subs, who(), thread, projectId))
            })
        },
    )

    // A level, or null to clear it: a channel then follows its group, a group the default (spec/12).
    fun level(body: JsonObject): String? {
        if ("level" !in body) throw invalid("level must be a string")
        val level = nullableText(body["level"], "level")
        if (level != null && level !in Subscriptions.LEVELS) throw invalid("level must be one of: ${Subscriptions.LEVELS.joinToString(", ")}")
        return level
    }

    endpoint(
        "/api/projects/{reference}/subscription",
        HttpMethod.Put to {
            val level = level(call.jsonObjectBody())
            val project = app.feed.projects.resolve(param("reference"))
            val subs = Subscriptions(app.db, app.clock)
            if (subs.channelLevel(who(), project.id) != level) {
                subs.setLevel(who(), project.id, level)
                changed()
            }
            call.respondOwner(200, buildJsonObject {
                put("projectId", project.id)
                put("level", subs.level(who(), project.id))
                put("inherited", subs.channelLevel(who(), project.id) == null)
            })
        },
    )

    // Groups of channels (migration 053): the app calls them projects.
    endpoint(
        "/api/groups",
        HttpMethod.Get to {
            call.respondOwner(200, buildJsonObject { put("groups", JsonArray(app.feed.groups.list().map { it.json })) })
        },
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val name = text(body["name"], "name")
            val slug = if ("slug" in body) text(body["slug"], "slug") else null
            val (group, created) = app.feed.groups.create(name, slug)
            call.respondOwner(if (created) 201 else 200, buildJsonObject { put("group", group.json); put("created", created) })
        },
    )

    endpoint(
        "/api/groups/{reference}",
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val patch = Groups.Patch(
                name = if ("name" in body) text(body["name"], "name") else null,
                slug = if ("slug" in body) text(body["slug"], "slug") else null,
                status = if ("status" in body) projectStatus(body["status"]) else null,
                theme = if ("theme" in body) Given(themePatch(body["theme"]!!)) else null,
            )
            call.respondOwner(200, buildJsonObject { put("group", app.feed.groups.update(param("reference"), patch).json) })
        },
        HttpMethod.Delete to {
            val group = app.feed.groups.delete(param("reference"))
            // Everyone's choices for it went with it.
            app.bus.publish("subscriptions.updated", People.followsChanged(app.db, call.person.id).let { it - "memberId" }.let(::JsonObject))
            call.respondOwner(200, buildJsonObject { put("deleted", true); put("groupId", group.id) })
        },
    )

    endpoint(
        "/api/groups/{reference}/subscription",
        HttpMethod.Put to {
            val level = level(call.jsonObjectBody())
            val group = app.feed.groups.resolve(param("reference"))
            val subs = Subscriptions(app.db, app.clock)
            if (subs.groupLevel(who(), group.id) != level) {
                subs.setGroupLevel(who(), group.id, level)
                changed()
            }
            call.respondOwner(200, buildJsonObject { put("groupId", group.id); put("level", subs.groupLevel(who(), group.id)) })
        },
    )

    // Which agents hear a channel or a project, and the owner adding or removing one (migration 060).
    suspend fun RoutingContext.hears(): Boolean = flag(call.jsonObjectBody()["hears"], "hears")

    endpoint(
        "/api/projects/{reference}/agents",
        HttpMethod.Get to {
            val project = app.feed.projects.resolve(param("reference"))
            call.respondOwner(200, buildJsonObject {
                put("projectId", project.id)
                put("agents", JsonArray(app.feed.agentMembers.ofChannel(project.id).map { it.json }))
            })
        },
    )

    endpoint(
        "/api/projects/{reference}/agents/{agentId}",
        HttpMethod.Put to {
            val hears = hears()
            val project = app.feed.projects.resolve(param("reference"))
            val member = app.feed.agentMembers.setChannel(project, param("agentId"), hears, call.person.id)
            call.respondOwner(200, buildJsonObject { put("projectId", project.id); put("agent", member.json) })
        },
    )

    endpoint(
        "/api/groups/{reference}/agents",
        HttpMethod.Get to {
            val group = app.feed.groups.resolve(param("reference"))
            call.respondOwner(200, buildJsonObject {
                put("groupId", group.id)
                put("agents", JsonArray(app.feed.agentMembers.ofGroup(group.id).map { it.json }))
            })
        },
    )

    endpoint(
        "/api/groups/{reference}/agents/{agentId}",
        HttpMethod.Put to {
            val hears = hears()
            val group = app.feed.groups.resolve(param("reference"))
            val member = app.feed.agentMembers.setGroup(group, param("agentId"), hears, call.person.id)
            call.respondOwner(200, buildJsonObject { put("groupId", group.id); put("agent", member.json) })
        },
    )

    endpoint(
        "/api/subscriptions",
        HttpMethod.Get to {
            val seq = app.bus.lastSeq
            val choices = Subscriptions(app.db, app.clock).choices(who())
            call.respondOwner(200, buildJsonObject {
                put("seq", seq)
                put("at", Wire.isoOf(app.clock()))
                put("projects", JsonArray(choices.levels.map { (id, level) -> buildJsonObject { put("projectId", id); put("level", level) } }))
                put("groups", JsonArray(choices.groups.map { (id, level) -> buildJsonObject { put("groupId", id); put("level", level) } }))
                put("threads", JsonArray(choices.threads.map { (t, on) ->
                    buildJsonObject { put("kind", t.kind); put("id", t.id); put("following", on) }
                }))
            })
        },
    )

    endpoint(
        "/api/agents",
        HttpMethod.Get to {
            // Revoked ones too, marked: old cards still name them.
            call.respondOwner(200, buildJsonObject {
                put("agents", JsonArray(app.agents.list(includeRevoked = true).map { a ->
                    buildJsonObject {
                        put("id", a.id)
                        put("name", a.name)
                        put("createdAt", Wire.isoOf(a.createdAt))
                        put("lastSeenAt", a.lastSeenAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
                        put("revokedAt", a.revokedAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
                        put("profileRole", a.profileRole)
                        put("description", a.description)
                        put("avatarMediaId", a.avatarMediaId)
                    }
                }))
            })
        },
    )

    endpoint(
        "/api/agents/{id}",
        HttpMethod.Delete to {
            // Removing an agent revokes it: its token and every OAuth connection stop working at once,
            // its sessions end (so it leaves the agent lists), and what it posted stays.
            val id = param("id")
            val revoked = app.agents.revoke(id)
            if (revoked) {
                app.sessions.endAll(id)
                app.bus.publish("agent.revoked", buildJsonObject { put("agentId", id) })
            }
            call.respondOwner(200, buildJsonObject { put("revoked", revoked) })
        },
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val unknown = body.keys - setOf("name", "profileRole", "description", "avatarMediaId")
            if (body.isEmpty() || unknown.isNotEmpty()) throw invalid("an agent patch changes name, profileRole, description and avatarMediaId, and nothing else")
            // Owners and admins say what an agent does (migration 077), as the agent can itself with set_profile.
            val profile = profileChange(body)
            val id = param("id")
            val setter = Profiles.Setter.ForAgent(id)
            if (!profile.empty) app.profiles.check(profile, setter)
            if ("name" in body) {
                val renamed = app.agents.rename(id, text(body["name"], "name"))
                app.bus.publish("agent.renamed", buildJsonObject { put("agentId", renamed.id); put("name", renamed.name) })
            }
            if (!profile.empty) {
                app.profiles.setAgent(id, profile, setter)
                app.bus.publish("agent.profile", buildJsonObject { put("agentId", id) })
            }
            val agent = app.agents.find(id) ?: throw notFound("no such agent: $id")
            call.respondOwner(200, buildJsonObject {
                put("agent", buildJsonObject {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("profileRole", agent.profileRole)
                    put("description", agent.description)
                    put("avatarMediaId", agent.avatarMediaId)
                })
            })
        },
    )
}

internal fun text(value: JsonElement?, field: String): String = value.jsString ?: throw invalid("$field must be a string")

internal fun nullableText(value: JsonElement?, field: String): String? = if (value is JsonNull) null else text(value, field)

/** A list of ids or slugs, each a string. */
private fun idList(value: JsonElement?, field: String): List<String> =
    (value as? JsonArray ?: throw invalid("$field must be a list")).map { it.jsString ?: throw invalid("$field must hold strings") }

internal fun flag(value: JsonElement?, field: String): Boolean = value.jsBoolean ?: throw invalid("$field must be true or false")

private fun projectStatus(value: JsonElement?): String =
    value.jsString?.takeIf { it == "active" || it == "archived" } ?: throw invalid("status must be active or archived")

/** The theme half of a project patch: only the four keys, each a string (or boolean) or null. */
private fun themePatch(value: JsonElement): Projects.ThemeInput? {
    if (value is JsonNull) return null
    if (value !is JsonObject) throw invalid("theme must be an object, or null to clear it")
    return Projects.ThemeInput(
        background = if ("background" in value) Given(nullableText(value["background"], "background")) else null,
        accent = if ("accent" in value) Given(nullableText(value["accent"], "accent")) else null,
        logoMediaId = if ("logoMediaId" in value) Given(nullableText(value["logoMediaId"], "logoMediaId")) else null,
        logoReplacesName = if ("logoReplacesName" in value) {
            Given(value["logoReplacesName"].let { if (it is JsonNull) null else flag(it, "logoReplacesName") })
        } else {
            null
        },
    )
}

/** The longest send key an app may give a message: room for a UUID and then some. */
private const val SEND_KEY_MAX = 200
