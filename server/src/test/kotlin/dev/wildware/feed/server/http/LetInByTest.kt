package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.People
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a person lets in (agents, OAuth connections) goes when they do: removed, made a member, or deleted. */
class LetInByTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun join(app: App, role: People.Role, name: String): People.Person {
        val (_, token) = app.people.invite(app.people.find(People.FOUNDER)!!, role, "${name.lowercase()}@example.test")
        return app.people.accept(token, name, "${name.lowercase()}@example.test", "password for $name")
    }

    /** An agent [by] lets in through pairing, its id and token. */
    private fun pairedBy(app: App, by: People.Person, name: String): Pair<String, String> {
        val started = app.pairings.start(name, "box", "/work", null, null)
        val approved = app.pairings.approve(started.pairing.id, null, by.id)
        val token = kotlinx.coroutines.runBlocking { app.pairings.await(started.pairing.id, started.secret, 0) }.token!!
        return approved.agentId!! to token
    }

    private fun revoked(app: App, agentId: String) = app.agents.find(agentId)!!.revokedAt != null

    @Test
    fun `removing, demoting or deleting a person revokes the agents and connections they let in`() {
        val app = App(config, Database.memory())
        val owner = app.people.find(People.FOUNDER)!!
        val ada = join(app, People.Role.ADMIN, "Ada")
        val bo = join(app, People.Role.ADMIN, "Bo")
        val cy = join(app, People.Role.ADMIN, "Cy")
        val (adaAgent, adaToken) = pairedBy(app, ada, "ada-laptop")
        val (boAgent, _) = pairedBy(app, bo, "bo-laptop")
        val (cyAgent, _) = pairedBy(app, cy, "cy-laptop")
        val (ownerAgent, _) = pairedBy(app, owner, "owner-laptop")
        val editor = app.oauth.register("Editor", null, listOf("http://127.0.0.1:9999/cb"), null)
        app.oauth.allow(editor, ownerAgent, "http://127.0.0.1/mcp", "mcp", "http://127.0.0.1:9999/cb", true, "c".repeat(43), ada.id)
        fun adaGrants() = app.db.query("SELECT revoked_at FROM oauth_grants WHERE team_id = :team AND created_by = ?", ada.id) { it.getObject(1) }

        testApplication {
            application { feed(app) }
            suspend fun mcp(token: String) = client.request("/mcp") {
                method = HttpMethod.Post
                header("Authorization", "Bearer $token")
                header("Accept", "application/json, text/event-stream")
                header("Content-Type", "application/json")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            }.status.value
            assertEquals(200, mcp(adaToken))

            app.people.remove(owner, ada.id)
            assertEquals(401, mcp(adaToken), "Ada's agent goes with her")
            assertNotNull(adaGrants().single(), "and the connection she allowed")
            assertEquals(false, revoked(app, ownerAgent), "the agent itself was the owner's")
        }

        app.people.setRole(owner, bo.id, People.Role.MEMBER)
        assertEquals(true, revoked(app, boAgent), "a member lets no agents in")

        app.people.deleteAccount(cy, "password for Cy")
        assertEquals(true, revoked(app, cyAgent))

        // An agent from before anyone was recorded stays.
        val (old, _) = app.agents.mint("old-agent")
        app.people.remove(owner, join(app, People.Role.ADMIN, "Dee").id)
        assertNull(app.agents.find(old.id)!!.revokedAt)
        app.close()
    }
}
