package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Devices
import dev.wildware.feed.server.domain.invalid
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The owner's phones (spec/03-owner-api-requests-devices.md §3). */
fun Route.deviceRoutes(app: App) {
    fun minted(device: Devices.Device, token: String) = buildJsonObject {
        put("device", buildJsonObject {
            put("id", JsonPrimitive(device.id))
            put("name", JsonPrimitive(device.name))
            put("createdAt", JsonPrimitive(Wire.isoOf(device.createdAt)))
        })
        put("token", JsonPrimitive(token))
    }

    fun nameOf(body: kotlinx.serialization.json.JsonObject): String {
        val name = body["name"]
        if (name !is JsonPrimitive || !name.isString) throw invalid("name must be a string")
        return name.content
    }

    endpoint(
        "/api/devices/sign-in",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val name = nameOf(body)
            dev.wildware.feed.server.domain.requiredText(name, "name", 100)
            val password = (body["password"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (val result = app.checkPassword(password, call.clientAddress())) {
                is PasswordCheck.Refused -> {
                    val code = if (result.status == 429) "rate_limited" else "wrong_password"
                    val headers = result.retryAfterS?.let { arrayOf("Retry-After" to it.toString()) } ?: emptyArray()
                    call.respondJson(result.status, Wire.error(code, result.message), Wire.JSON_UTF8, "Cache-Control" to "no-store", *headers)
                }
                PasswordCheck.Ok -> {
                    val (device, token) = app.devices.mint(name)
                    call.respondOwner(201, minted(device, token))
                }
            }
        },
    )
    endpoint(
        "/api/devices",
        HttpMethod.Get to {
            val slugs = app.reads.projectSlugsById()
            call.respondOwner(200, buildJsonObject {
                put("devices", JsonArray(app.devices.list().map { d -> deviceView(d, slugs) }))
            })
        },
        HttpMethod.Post to {
            val (device, token) = app.devices.mint(nameOf(call.jsonObjectBody()))
            call.respondOwner(201, minted(device, token))
        },
    )
    endpoint(
        "/api/devices/{id}",
        HttpMethod.Delete to {
            call.respondOwner(200, buildJsonObject { put("removed", JsonPrimitive(app.devices.remove(call.parameters["id"]!!))) })
        },
    )
    endpoint(
        "/api/devices/current/apns",
        HttpMethod.Put to {
            val device = call.currentDevice(app)
            val body = call.jsonObjectBody()
            app.devices.setApns(device, body["token"].jsValue(), body["environment"].jsValue())
            call.respondOwner(200, buildJsonObject { put("ok", JsonPrimitive(true)) })
        },
    )
    endpoint(
        "/api/devices/current/live-activity",
        HttpMethod.Put to {
            val device = call.currentDevice(app)
            val body = call.jsonObjectBody()
            fun opt(key: String) = if (key in body) Devices.Optional.Present(body[key].jsValue()) else Devices.Optional.Absent
            app.devices.setLiveActivity(device, opt("updateToken"), opt("pushToStartToken"))
            call.respondOwner(200, buildJsonObject { put("ok", JsonPrimitive(true)) })
        },
    )
    endpoint(
        "/api/devices/current/alert-projects",
        HttpMethod.Put to {
            val device = call.currentDevice(app)
            val body = call.jsonObjectBody()
            if ("projects" !in body) throw invalid("send projects: an array of slugs, or null")
            app.devices.setAlertProjects(device, body["projects"].jsValue()) { slug -> app.reads.projectIdBySlug(slug) }
            call.respondOwner(200, buildJsonObject { put("ok", JsonPrimitive(true)) })
        },
    )
}

/** The device behind the bearer, or the TS server's 400 for a cookie caller. */
private fun ApplicationCall.currentDevice(app: App): String {
    val bearer = Guard.bearer(request.headers["Authorization"])
    val device = bearer?.let { app.devices.authenticate(it) }
        ?: throw invalid("this needs a device key: send Authorization: Bearer <device token>")
    return device.id
}

fun deviceView(d: Devices.Device, slugsById: Map<String, String>): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(d.id))
    put("name", JsonPrimitive(d.name))
    put("createdAt", JsonPrimitive(Wire.isoOf(d.createdAt)))
    put("lastSeenAt", d.lastSeenAt?.let { JsonPrimitive(Wire.isoOf(it)) } ?: JsonNull)
    put("hasApns", JsonPrimitive(d.apnsToken != null))
    put("hasLiveActivity", JsonPrimitive(d.liveActivityToken != null))
    put("alertProjects", d.alertProjectIds?.let { ids -> JsonArray(ids.mapNotNull { slugsById[it] }.map(::JsonPrimitive)) } ?: JsonNull)
}
