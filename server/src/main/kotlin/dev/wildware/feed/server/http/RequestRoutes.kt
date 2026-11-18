package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Apps
import dev.wildware.feed.server.domain.Js
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
            val request = app.requests.answer(call.parameters["id"] ?: "", value)
            call.respondOwner(200, buildJsonObject { put("request", request.json) })
        },
    )
    endpoint(
        "/api/requests/{id}",
        HttpMethod.Delete to {
            val request = app.requests.cancel(call.parameters["id"] ?: "")
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
            val changed = app.notifications.markSeen((ids as JsonArray?)?.mapNotNull { it.jsString })
            call.respondOwner(200, buildJsonObject {
                put("changed", changed)
                put("unseen", app.notifications.unseen())
            })
        },
    )

    endpoint(
        "/api/push",
        HttpMethod.Post to {
            if (app.config.vapid == null) throw conflict("push is not configured on this deployment")
            val body = call.jsonObjectBody()
            val keys = body["keys"] as? JsonObject ?: JsonObject(emptyMap())
            val endpoint = app.pushSubscriptions.subscribe(
                endpoint = jsStringOf(body["endpoint"]),
                p256dh = jsStringOf(keys["p256dh"]),
                auth = jsStringOf(keys["auth"]),
                label = body["label"].jsString,
            )
            call.respondOwner(200, buildJsonObject {
                put("subscribed", true)
                put("endpoint", endpoint)
            })
        },
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val endpoint = body["endpoint"].jsString ?: ""
            if (endpoint == "") throw invalid("endpoint is required")
            val prefs = app.pushSubscriptions.setPrefs(endpoint, body["prefs"])
            call.respondOwner(200, buildJsonObject { put("prefs", prefs ?: JsonNull) })
        },
        HttpMethod.Delete to {
            val removed = app.pushSubscriptions.unsubscribe(call.request.queryParameters["endpoint"] ?: "")
            call.respondOwner(200, buildJsonObject { put("removed", removed) })
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
