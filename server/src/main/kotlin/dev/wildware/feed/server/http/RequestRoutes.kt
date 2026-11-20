package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Apps
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.Requests
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.conflict
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsString
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owner routes for the request area: answering and dismissing requests (spec/03 §2), clearing the
 * bell (§5), Web Push subscriptions (§4), an app's document and its saved state (§8).
 */
fun Route.requestRoutes(app: App) {
    endpoint(
        "/api/requests/{id}/answer",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val value = body["value"] ?: throw invalid("an answer must carry a value")
            // The question's editedAt as the app showed it (null: never edited). An app that sends it
            // is refused when the question changed since; one that doesn't is answered as before.
            val shown = if ("editedAt" in body) {
                val raw = body["editedAt"]
                Requests.Shown(if (raw == null || raw is kotlinx.serialization.json.JsonNull) null else raw.jsNumber?.toLong() ?: throw invalid("editedAt must be a number, or null"))
            } else {
                null
            }
            val request = app.requests.answer(call.parameters["id"] ?: "", value, call.person.id, shown)
            call.respondOwner(200, buildJsonObject { put("request", request.json) })
        },
    )
    endpoint(
        "/api/requests/{id}",
        HttpMethod.Delete to {
            val request = app.requests.cancel(call.parameters["id"] ?: "", call.person.id)
            call.respondOwner(200, buildJsonObject { put("request", request.json) })
        },
    )

    endpoint(
        "/api/notifications/seen",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val ids = body["ids"]
            // Decided fix 9: only a missing `ids` means everything.
            if (ids != null && ids !is JsonArray) {
                throw invalid("ids must be a list of notification ids, or left out to clear them all")
            }
            val changed = app.notifications.markSeen((ids as JsonArray?)?.mapNotNull { it.jsString }, call.person.id)
            call.respondOwner(200, buildJsonObject {
                put("changed", changed)
                put("unseen", app.notifications.unseen(call.person.id))
            })
        },
    )

    // The owner opened these cards on some device: what the bell said about them is seen (spec/03 §5.3).
    endpoint(
        "/api/notifications/cards-seen",
        HttpMethod.Post to {
            val cards = idList(call.lenientObjectBody()["cards"], "cards", "card ids")
            val changed = app.notifications.markCardsSeen(cards, member = call.person.id)
            call.respondOwner(200, buildJsonObject {
                put("changed", changed)
                put("unseen", app.notifications.unseen(call.person.id))
            })
        },
    )

    // Which of the alerts a device still shows are done with (spec/03 §5.4).
    endpoint(
        "/api/notifications/handled",
        HttpMethod.Post to {
            val tags = idList(call.lenientObjectBody()["tags"], "tags", "alert tags")
            call.respondOwner(200, buildJsonObject {
                put("handled", JsonArray(app.notifications.handled(tags, call.person.id).map(::JsonPrimitive)))
            })
        },
    )

    endpoint(
        "/api/apps/{view}/state",
        HttpMethod.Post to {
            val state = call.lenientObjectBody()["state"]
            if (state !is JsonNull && state !is JsonObject) throw invalid("state must be an object, or null to forget it")
            val (viewId, saved) = app.appState.save(call.parameters["view"] ?: "", state as? JsonObject)
            call.respondOwner(200, buildJsonObject {
                put("view_id", viewId)
                put("state", saved ?: JsonNull)
            })
        },
    )

    // One showing's document, for the sandboxed frame that renders it (spec/03 §8, TS `http/apps/serve.ts`).
    endpoint(
        "/apps/{view}",
        HttpMethod.Get to {
            // Only ever drawn in a card's frame: opened as a page of its own, on the dashboard's
            // address, it could pass itself off as the dashboard (a "sign in again" box).
            if (call.request.headers["Sec-Fetch-Dest"] == "document") {
                call.respondRaw(403, "An app opens on its card, not as a page of its own.", "text/plain;charset=utf-8", "Cache-Control" to "no-store")
                return@to
            }
            val document = app.feed.apps.document(call.parameters["view"] ?: "")
            if (document == null) {
                call.respondRaw(404, """{"error":"not_found"}""", Wire.JSON, "Cache-Control" to "no-store")
            } else {
                call.respondRaw(
                    200, document.first, "text/html;profile=mcp-app;charset=utf-8",
                    "Content-Security-Policy" to Apps.policyFor(document.second),
                    "X-Content-Type-Options" to "nosniff",
                    "Referrer-Policy" to "no-referrer",
                    "Cache-Control" to "no-store",
                )
            }
        },
    )
}

/** JavaScript's `String(value ?? '')` for a JSON value. */
internal fun jsStringOf(value: JsonElement?): String = when (value) {
    null, is JsonNull -> ""
    is JsonPrimitive -> if (value.isString) value.content else Js.normalize(value).let { (it as JsonPrimitive).content }
    is JsonArray -> value.joinToString(",") { if (it is JsonNull) "" else jsStringOf(it) }
    is JsonObject -> "[object Object]"
}

/** At most this many ids in one clearing call. */
internal const val MAX_CLEAR_IDS = 500

/** A required list of short strings, such as card ids or alert tags. */
internal fun idList(value: JsonElement?, name: String, what: String): List<String> {
    if (value !is JsonArray) throw invalid("$name must be a list of $what")
    if (value.size > MAX_CLEAR_IDS) throw invalid("$name holds at most $MAX_CLEAR_IDS $what")
    return value.map { item -> item.jsString?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: throw invalid("$name must be a list of $what") }
}
