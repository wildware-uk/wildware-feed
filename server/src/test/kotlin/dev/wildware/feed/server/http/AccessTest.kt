package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.People
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who may call what (docs/TEAMS.md §1, §10): every owner route has a rule, a member can't do an
 * admin's things, an admin can't do an owner's, nobody can raise their own role, and a removed
 * person's keys stop working at once.
 */
class AccessTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    class Team(val app: App, val owner: String, val admin: String, val member: String, val adminId: String, val memberId: String, val other: String, val otherId: String)

    /** A team: the founder (owner), an admin and two members, each with a key. */
    private fun withTeam(block: suspend ApplicationTestBuilder.(Team) -> Unit) {
        val app = App(config, Database.memory())
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        val founder = app.people.find(People.FOUNDER)!!
        fun join(role: People.Role, name: String): Pair<String, String> {
            val (_, token) = app.people.invite(founder, role, null)
            val person = app.people.accept(token, name, "$name@example.com", "a long password")
            return person.id to app.devices.mint("$name's phone", person.id).second
        }
        val ownerKey = app.devices.mint("Phone").second
        val (adminId, adminKey) = join(People.Role.ADMIN, "ada")
        val (memberId, memberKey) = join(People.Role.MEMBER, "sam")
        val (otherId, otherKey) = join(People.Role.MEMBER, "kim")
        testApplication {
            application { feed(app) }
            block(Team(app, ownerKey, adminKey, memberKey, adminId, memberId, otherKey, otherId))
        }
        app.close()
    }

    private suspend fun ApplicationTestBuilder.call(key: String?, method: String, path: String, body: String = "{}"): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (key != null) header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            if (method != "GET") setBody(body)
        }
        return when (method) {
            "GET" -> client.get(path, build)
            "POST" -> client.post(path, build)
            "PUT" -> client.put(path, build)
            "PATCH" -> client.patch(path, build)
            else -> client.delete(path, build)
        }
    }

    private fun fill(pattern: String) = pattern.replace(Regex("\\{[^}]+}"), "x")

    @Test
    fun `every owner route has a rule, and every rule a route`() = withTeam { _ ->
        val owned = registeredEndpoints.filter { Guard.requiresSession(it.substringAfter(' ')) }.filter { !it.startsWith("OPTIONS ") }
        // Routes a test registers for itself are not the server's.
        val server = owned.filterNot { it.contains("/boom") }
        val missing = server.filter { Access.rule(it.substringBefore(' '), it.substringAfter(' ')) == null }
        assertEquals(emptyList(), missing, "owner routes with no rule in Access")
        val stale = Access.rules.keys.filter { it !in registeredEndpoints }
        assertEquals(emptyList(), stale, "rules for routes that don't exist")
    }

    @Test
    fun `a member can't do an admin's things, nor an admin an owner's`() = withTeam { team ->
        for ((route, role) in Access.rules) {
            val (method, pattern) = route.split(' ', limit = 2)
            val path = fill(pattern)
            when (role) {
                People.Role.ADMIN -> {
                    val r = call(team.member, method, path)
                    assertEquals(403, r.status.value, "a member calling $route")
                    assertEquals("forbidden", Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
                }
                People.Role.OWNER -> {
                    assertEquals(403, call(team.admin, method, path).status.value, "an admin calling $route")
                    assertEquals(403, call(team.member, method, path).status.value, "a member calling $route")
                }
                People.Role.MEMBER -> {
                    // Allowed past the door: what the route then says is its own business.
                    if (method == "GET" && pattern == "/api/stream") continue
                    assertNotEquals(403, call(team.member, method, path).status.value, "a member calling $route")
                }
            }
            assertEquals(401, call(null, method, path).status.value, "nobody calling $route")
        }
    }

    @Test
    fun `nobody raises their own role or gets past their rank`() = withTeam { team ->
        // A member can't change any role, their own included.
        assertEquals(403, call(team.member, "PATCH", "/api/people/${team.memberId}", """{"role":"owner"}""").status.value)
        // An admin can't make someone an admin or touch roles at all.
        assertEquals(403, call(team.admin, "PATCH", "/api/people/${team.memberId}", """{"role":"admin"}""").status.value)
        assertEquals(403, call(team.admin, "PATCH", "/api/people/${team.adminId}", """{"role":"owner"}""").status.value)
        // An owner can't change their own role.
        val self = call(team.owner, "PATCH", "/api/people/founder", """{"role":"member"}""")
        assertEquals(403, self.status.value)
        assertTrue(self.bodyAsText().contains("nobody changes their own role"))
        // An admin invites members only.
        assertEquals(403, call(team.admin, "POST", "/api/invites", """{"role":"admin"}""").status.value)
        assertEquals(403, call(team.admin, "POST", "/api/invites", """{"role":"owner"}""").status.value)
        assertEquals(201, call(team.admin, "POST", "/api/invites", """{"role":"member"}""").status.value)
        // A member invites nobody.
        assertEquals(403, call(team.member, "POST", "/api/invites", """{"role":"member"}""").status.value)
        // An admin removes members, not owners or admins.
        assertEquals(403, call(team.admin, "DELETE", "/api/people/founder").status.value)
        // A member removes nobody else.
        assertEquals(403, call(team.member, "DELETE", "/api/people/${team.otherId}").status.value)
        // The last owner can't leave.
        assertEquals(409, call(team.owner, "DELETE", "/api/people/founder").status.value)
        // Nothing changed.
        assertEquals("member", team.app.people.find(team.memberId)!!.role.wire)
        assertEquals("admin", team.app.people.find(team.adminId)!!.role.wire)
        assertEquals("owner", team.app.people.find(People.FOUNDER)!!.role.wire)
    }

    @Test
    fun `a member unsends only their own words, an admin anyone's`() = withTeam { team ->
        fun id(r: String) = Wire.json.parseToJsonElement(r).jsonObject["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val mine = id(call(team.member, "POST", "/api/messages", """{"body":"mine","project":"web"}""").bodyAsText())
        val theirs = id(call(team.other, "POST", "/api/messages", """{"body":"theirs","project":"web"}""").bodyAsText())
        assertEquals(403, call(team.member, "DELETE", "/api/messages/$theirs").status.value)
        assertEquals(200, call(team.member, "DELETE", "/api/messages/$mine").status.value)
        assertEquals(200, call(team.admin, "DELETE", "/api/messages/$theirs").status.value)
    }

    @Test
    fun `devices are each person's own`() = withTeam { team ->
        val list = Wire.json.parseToJsonElement(call(team.member, "GET", "/api/devices").bodyAsText()).jsonObject["devices"].toString()
        assertTrue(list.contains("sam's phone") && !list.contains("kim's phone"), list)
        val kimsPhone = team.app.devices.listFor(team.otherId).single().id
        // A member can't sign out someone else's phone; an admin can a member's, not an owner's or an admin's.
        assertEquals("""{"removed":false}""", call(team.member, "DELETE", "/api/devices/$kimsPhone").bodyAsText())
        val ownersPhone = team.app.devices.listFor(People.FOUNDER).single().id
        assertEquals("""{"removed":false}""", call(team.admin, "DELETE", "/api/devices/$ownersPhone").bodyAsText())
        val adminsOwn = team.app.devices.mint("Ada's laptop", team.adminId).first.id
        assertEquals("""{"removed":false}""", call(team.member, "DELETE", "/api/devices/$adminsOwn").bodyAsText())
        assertEquals("""{"removed":true}""", call(team.owner, "DELETE", "/api/devices/$adminsOwn").bodyAsText())
        assertEquals("""{"removed":true}""", call(team.admin, "DELETE", "/api/devices/$kimsPhone").bodyAsText())
        assertEquals(401, call(team.other, "GET", "/api/me").status.value)
    }

    @Test
    fun `removing someone kills their keys at once, and their invites`() = withTeam { team ->
        // An admin's waiting invite is withdrawn with them, so they can't let themselves back in.
        val invite = Wire.json.parseToJsonElement(call(team.admin, "POST", "/api/invites", """{"role":"member"}""").bodyAsText()).jsonObject
        val token = invite["token"]!!.jsonPrimitive.content
        assertEquals(200, call(team.admin, "GET", "/api/me").status.value)
        assertEquals(200, call(team.owner, "DELETE", "/api/people/${team.adminId}").status.value)
        assertEquals(401, call(team.admin, "GET", "/api/me").status.value)
        assertEquals(401, call(team.admin, "GET", "/api/snapshot").status.value)
        assertNull(team.app.people.peek(token))
        // Their name still labels what they did.
        assertEquals("ada", team.app.people.find(team.adminId)!!.name)
        // Leaving is removing yourself.
        assertEquals(200, call(team.member, "DELETE", "/api/people/${team.memberId}").status.value)
        assertEquals(401, call(team.member, "GET", "/api/me").status.value)
    }

    @Test
    fun `a demoted admin's admin-only invites go with the role`() = withTeam { team ->
        val founder = team.app.people.find(People.FOUNDER)!!
        team.app.people.setRole(founder, team.memberId, People.Role.OWNER)
        val asOwner = team.app.people.find(team.memberId)!!
        val (_, token) = team.app.people.invite(asOwner, People.Role.OWNER, null)
        team.app.people.setRole(founder, team.memberId, People.Role.MEMBER)
        assertNull(team.app.people.peek(token), "an owner invite made while an owner is withdrawn when they are not one any more")
    }
}
