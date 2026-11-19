package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Projects
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.jsTrim
import dev.wildware.feed.server.domain.Cards
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
            )
            call.respondOwner(200, buildJsonObject { put("project", app.feed.projects.update(param("reference"), patch).json) })
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
            val mediaIds = if ("mediaIds" in body) {
                val list = body["mediaIds"] as? JsonArray ?: throw invalid("mediaIds must be a list")
                list.mapIndexed { i, id -> text(id, "mediaIds[$i]") }
            } else {
                null
            }
            val message = app.feed.messages.post(
                Messages.Post(
                    author = Messages.HUMAN, body = text, project = project, updateId = updateId, replyTo = replyTo,
                    answers = answers, requestId = requestId, mediaIds = mediaIds,
                ),
            )
            call.respondOwner(201, buildJsonObject { put("message", message.json) })
        },
    )

    endpoint(
        "/api/messages/{id}",
        HttpMethod.Delete to {
            call.respondOwner(200, buildJsonObject { put("message", app.feed.messages.delete(param("id"), Messages.HUMAN).json) })
        },
    )

    endpoint(
        "/api/messages/{id}/reactions",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val emoji = text(body["emoji"], "emoji")
            val (reactions, on) = app.feed.cards.react(CardRef("message", param("id")), Messages.HUMAN, emoji, body["on"].jsBoolean)
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
            val (reactions, on) = app.feed.cards.react(card(), Messages.HUMAN, emoji, body["on"].jsBoolean)
            call.respondOwner(200, buildJsonObject { put("reactions", JsonArray(reactions.map { it.json })); put("on", on) })
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

    // Subscriptions (migration 052): who follows a thread, and what the owner follows.
    fun ownerView(subs: Subscriptions, thread: Subscriptions.Thread, projectId: String?) = buildJsonObject {
        val state = subs.threadState(Subscriptions.OWNER, thread)
        put("following", state ?: (subs.ownerLevel(projectId) == Subscriptions.ALL))
        put("chosen", state != null)
    }

    fun changed() = app.bus.publish("subscriptions.updated", buildJsonObject { put("subscriber", Subscriptions.OWNER) })

    endpoint(
        "/api/cards/{kind}/{id}/followers",
        HttpMethod.Get to {
            val subs = Subscriptions(app.db, app.clock)
            val (thread, projectId) = subs.threadAt(card())
            call.respondOwner(200, buildJsonObject {
                put("thread", buildJsonObject { put("kind", thread.kind); put("id", thread.id) })
                put("followers", JsonArray(subs.followers(thread).map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
                put("owner", ownerView(subs, thread, projectId))
            })
        },
    )

    endpoint(
        "/api/cards/{kind}/{id}/follow",
        HttpMethod.Put to {
            val following = flag(call.jsonObjectBody()["following"], "following")
            val subs = Subscriptions(app.db, app.clock)
            val (thread, projectId) = subs.threadAt(card())
            if (subs.setOwnerFollow(thread, projectId, following)) changed()
            call.respondOwner(200, buildJsonObject {
                put("thread", buildJsonObject { put("kind", thread.kind); put("id", thread.id) })
                put("owner", ownerView(subs, thread, projectId))
            })
        },
    )

    endpoint(
        "/api/projects/{reference}/subscription",
        HttpMethod.Put to {
            val level = text(call.jsonObjectBody()["level"], "level")
            val project = app.feed.projects.resolve(param("reference"))
            val subs = Subscriptions(app.db, app.clock)
            if (subs.ownerLevel(project.id) != level) {
                subs.setOwnerLevel(project.id, level)
                changed()
            } else if (level !in Subscriptions.LEVELS) {
                throw invalid("level must be one of: ${Subscriptions.LEVELS.joinToString(", ")}")
            }
            call.respondOwner(200, buildJsonObject { put("projectId", project.id); put("level", subs.ownerLevel(project.id)) })
        },
    )

    endpoint(
        "/api/subscriptions",
        HttpMethod.Get to {
            val choices = Subscriptions(app.db, app.clock).ownerChoices()
            call.respondOwner(200, buildJsonObject {
                put("projects", JsonArray(choices.levels.map { (id, level) -> buildJsonObject { put("projectId", id); put("level", level) } }))
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
            if (body.keys.toList() != listOf("name")) throw invalid("an agent patch changes name and nothing else")
            val agent = app.agents.rename(param("id"), text(body["name"], "name"))
            app.bus.publish("agent.renamed", buildJsonObject { put("agentId", agent.id); put("name", agent.name) })
            call.respondOwner(200, buildJsonObject { put("agent", buildJsonObject { put("id", agent.id); put("name", agent.name) }) })
        },
    )
}

private fun text(value: JsonElement?, field: String): String = value.jsString ?: throw invalid("$field must be a string")

private fun nullableText(value: JsonElement?, field: String): String? = if (value is JsonNull) null else text(value, field)

private fun flag(value: JsonElement?, field: String): Boolean = value.jsBoolean ?: throw invalid("$field must be true or false")

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
