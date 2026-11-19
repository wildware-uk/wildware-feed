package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Devices
import dev.wildware.feed.server.domain.People
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
            // With an email, that person's sign-in; without, today's: the founding owner's password.
            val email = if ("email" in body) {
                (body["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("email must be a string")
            } else {
                null
            }
            when (val result = app.checkPassword(password, call.clientAddress(app.config), email)) {
                is PasswordCheck.Refused -> {
                    val code = if (result.status == 429) "rate_limited" else "wrong_password"
                    val headers = result.retryAfterS?.let { arrayOf("Retry-After" to it.toString()) } ?: emptyArray()
                    call.respondJson(result.status, Wire.error(code, result.message), Wire.JSON_UTF8, "Cache-Control" to "no-store", *headers)
                }
                is PasswordCheck.Ok -> {
                    val (device, token) = app.devices.mint(name, result.person.id)
                    // The web app asks for its key as a cookie its code can't read, and is never shown it.
                    if ((body["cookie"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true) {
                        call.response.headers.append("Set-Cookie", OwnerCookie.set(token, app.config, call))
                        call.respondOwner(201, minted(device, ""))
                    } else {
                        call.respondOwner(201, minted(device, token))
                    }
                }
            }
        },
    )
    endpoint(
        "/api/devices/sign-out",
        HttpMethod.Post to {
            // Signs this device out: its key stops working and the web app's cookie is cleared.
            // Public, so a browser whose key is already revoked can still clear its cookie.
            val key = call.ownerKey()
            val device = key?.let { app.devices.authenticate(it) }
            val allowed = !call.keyIsCookie() || call.cookieRequestAllowed(app.config)
            val removed = device != null && allowed && app.devices.remove(device.id)
            call.response.headers.append("Set-Cookie", OwnerCookie.clear(app.config, call))
            call.respondOwner(200, buildJsonObject { put("signedOut", JsonPrimitive(removed)) })
        },
    )
    endpoint(
        "/api/devices",
        HttpMethod.Get to {
            // Your own devices: each person's phones are theirs.
            val slugs = app.reads.projectSlugsById()
            call.respondOwner(200, buildJsonObject {
                put("devices", JsonArray(app.devices.listFor(call.person.id).map { d -> deviceView(d, slugs) }))
            })
        },
        HttpMethod.Post to {
            val (device, token) = app.devices.mint(nameOf(call.jsonObjectBody()), call.person.id)
            call.respondOwner(201, minted(device, token))
        },
    )
    endpoint(
        "/api/devices/{id}",
        HttpMethod.Delete to {
            // Your own; an owner signs out anyone's, an admin a member's (admins never touch owners or admins).
            // Someone else's device you may not touch answers as if it were not there.
            val id = call.parameters["id"]!!
            val me = call.person
            val holder = app.devices.find(id)?.let { app.people.find(it.userId) }
            val allowed = holder != null && (
                holder.id == me.id || me.role == People.Role.OWNER ||
                    (me.role == People.Role.ADMIN && holder.role == People.Role.MEMBER)
                )
            call.respondOwner(200, buildJsonObject { put("removed", JsonPrimitive(allowed && app.devices.remove(id))) })
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
    endpoint(
        "/api/devices/current/alert-prefs",
        HttpMethod.Get to {
            val device = call.currentDevice(app)
            call.respondOwner(200, buildJsonObject { put("prefs", app.devices.find(device)?.alertPrefs ?: JsonNull) })
        },
        HttpMethod.Put to {
            val device = call.currentDevice(app)
            val body = call.jsonObjectBody()
            if ("prefs" !in body) throw invalid("send prefs: { types, levels, priorities }, or null")
            val prefs = app.devices.setAlertPrefs(device, body["prefs"])
            call.respondOwner(200, buildJsonObject { put("ok", JsonPrimitive(true)); put("prefs", prefs ?: JsonNull) })
        },
    )
}

/** The device behind the bearer or the web app's cookie, else the TS server's 400. */
private fun ApplicationCall.currentDevice(app: App): String {
    val device = ownerKey()?.let { app.devices.authenticate(it) }
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
    put("alertPrefs", d.alertPrefs ?: JsonNull)
}
