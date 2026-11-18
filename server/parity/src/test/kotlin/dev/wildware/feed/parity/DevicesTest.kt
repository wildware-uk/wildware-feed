package dev.wildware.feed.parity

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's phones, Web Push settings and the bell. Spec: server/spec/03-owner-api-requests-devices.md §3-5. */
class DevicesTest : ParityTest() {
    private suspend fun HttpResponse.expectError(status: Int, error: String, message: String) {
        val text = bodyAsText()
        assertEquals(status, this.status.value, text)
        assertEquals("""{"error":"$error","message":${obj("m" to message)["m"]}}""", text)
    }

    private suspend fun Device.view() = get("/api/devices").obj()["devices"].arr.single { it["id"].s == id }.o

    @Test
    fun `a signed-in owner registers a device, which lists itself`() = parity {
        val made = owner.post("/api/devices", obj("name" to "  Tablet  "))
        assertEquals(201, made.status.value)
        val body = made.obj()
        assertEquals(listOf("device", "token"), body.keys.toList())
        assertEquals(listOf("id", "name", "createdAt"), body["device"].o.keys.toList())
        val device = Device(body["device"]["id"].s, body["token"].s)

        val view = device.view()
        assertEquals(listOf("id", "name", "createdAt", "lastSeenAt", "hasApns", "hasLiveActivity", "alertProjects"), view.keys.toList())
        assertEquals("Tablet", view["name"].s)
        assertEquals(false, view["hasApns"].bool)
        assertEquals(false, view["hasLiveActivity"].bool)
        assertTrue(view["alertProjects"].isNull)
        // lastSeenAt is written at most once a minute.
        val seen = view["lastSeenAt"]
        assertEquals(seen, device.view()["lastSeenAt"])

        owner.post("/api/devices", obj("name" to 5)).expectError(400, "invalid_argument", "name must be a string")
        owner.post("/api/devices", obj("name" to "  ")).expectError(400, "invalid_argument", "name is required")
        owner.post("/api/devices", obj("name" to "x".repeat(101))).expectError(400, "invalid_argument", "name must be at most 100 characters")
    }

    @Test
    fun `sign-in refuses an empty or wrong password`() = parity {
        call(HttpMethod.Post, "/api/devices/sign-in", obj("password" to "", "name" to "x"))
            .expectError(400, "wrong_password", "Enter your password.")
        call(HttpMethod.Post, "/api/devices/sign-in", obj("password" to Parity.adminPassword, "name" to "   "))
            .expectError(400, "invalid_argument", "name is required")
        // One wrong guess (the limiter allows five; RateLimitTest spends the rest).
        call(HttpMethod.Post, "/api/devices/sign-in", obj("password" to "wrong", "name" to "x"))
            .expectError(400, "wrong_password", "That password is not right.")
        Device.signIn() // a success resets the count
    }

    @Test
    fun `the phone registers its APNs and Live Activity tokens`() = parity {
        val device = Device.signIn()
        val apns = device.send(HttpMethod.Put, "/api/devices/current/apns", obj("token" to "ABCDEF", "environment" to "sandbox"))
        assertEquals("""{"ok":true}""", apns.bodyAsText())
        assertEquals(true, device.view()["hasApns"].bool)
        device.send(HttpMethod.Put, "/api/devices/current/apns", obj("token" to "xyz", "environment" to "sandbox"))
            .expectError(400, "invalid_argument", "token must be a hex string")
        device.send(HttpMethod.Put, "/api/devices/current/apns", obj("token" to "abc", "environment" to "beta"))
            .expectError(400, "invalid_argument", "environment must be sandbox or production")

        device.send(HttpMethod.Put, "/api/devices/current/live-activity", obj())
            .expectError(400, "invalid_argument", "send updateToken, pushToStartToken, or both (null clears one)")
        assertEquals("""{"ok":true}""", device.send(HttpMethod.Put, "/api/devices/current/live-activity", obj("updateToken" to "a1b2")).bodyAsText())
        assertEquals(true, device.view()["hasLiveActivity"].bool)
        device.send(HttpMethod.Put, "/api/devices/current/live-activity", obj("updateToken" to null))
        assertEquals(false, device.view()["hasLiveActivity"].bool)
        device.send(HttpMethod.Put, "/api/devices/current/live-activity", obj("pushToStartToken" to "zz"))
            .expectError(400, "invalid_argument", "pushToStartToken must be a hex string")

        // Only one device holds a given APNs token.
        val other = Device.signIn()
        other.send(HttpMethod.Put, "/api/devices/current/apns", obj("token" to "abcdef", "environment" to "production"))
        assertEquals(false, device.view()["hasApns"].bool)
        assertEquals(true, other.view()["hasApns"].bool)
    }

    @Test
    fun `the phone chooses which projects may alert it`() = parity {
        val device = Device.signIn()
        val project = Fixtures.project()
        device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj())
            .expectError(400, "invalid_argument", "send projects: an array of slugs, or null")
        device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to arr("nope-zz")))
            .expectError(400, "invalid_argument", "unknown projects: nope-zz")
        device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to "x"))
            .expectError(400, "invalid_argument", "projects must be an array of project slugs, or null")
        assertEquals(
            """{"ok":true}""",
            device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to arr(project.slug.uppercase()))).bodyAsText(),
        )
        assertEquals(arr(project.slug), device.view()["alertProjects"])
        device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to arr()))
        assertEquals(arr(), device.view()["alertProjects"])
        device.send(HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to null))
        assertTrue(device.view()["alertProjects"].isNull)
    }

    @Test
    fun `web push is off without VAPID keys, and its inputs are still checked`() = parity {
        val status = owner.get("/api/push").obj()
        assertEquals(false, status["enabled"].bool)
        assertTrue(status["publicKey"].isNull)
        owner.post("/api/push", obj()).expectError(409, "conflict", "push is not configured on this deployment")
        owner.patch("/api/push", obj("endpoint" to "https://x", "prefs" to obj("types" to arr("bogus"))))
            .expectError(400, "invalid_argument", "types must be any of: request, update, message, comment")
        owner.delete("/api/push").expectError(400, "invalid_argument", "endpoint is required")
        assertEquals("""{"removed":false}""", owner.delete("/api/push?endpoint=https://nowhere.example/x").bodyAsText())
    }

    @Test
    fun `the bell lists and clears notifications`() = parity {
        val project = Fixtures.project()
        Fixtures.update(project, body = "ring")
        val list = owner.get("/api/notifications?limit=5").obj()
        assertEquals(listOf("seq", "at", "notifications", "unseen"), list.keys.toList())
        assertTrue(list["notifications"].arr.size <= 5)
        assertTrue(list["unseen"].int >= 1)
        val first = list["notifications"][0].o
        assertEquals("update", first["kind"].s)

        val none = owner.post("/api/notifications/seen", obj("ids" to arr())).obj()
        assertEquals(0, none["changed"].int)
        val one = owner.post("/api/notifications/seen", obj("ids" to arr(first["id"].s))).obj()
        assertEquals(1, one["changed"].int)
        assertEquals(list["unseen"].int - 1, one["unseen"].int)
        val bad = owner.post("/api/notifications/seen", obj("ids" to "x"))
        assertEquals(400, bad.status.value)
        assertEquals("invalid_argument", bad.obj()["error"].s)
        // No ids at all means "all of them".
        val all = owner.post("/api/notifications/seen", obj()).obj()
        assertEquals(0, all["unseen"].int)
        assertEquals(arr(), owner.get("/api/notifications?unseen=true").obj()["notifications"])
    }
}
