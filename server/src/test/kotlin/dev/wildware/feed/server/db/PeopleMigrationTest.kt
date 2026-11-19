package dev.wildware.feed.server.db

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.http.PasswordCheck
import dev.wildware.feed.server.http.checkPassword
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migration 055 (people) on a database that already has a working owner's data, on SQLite and on
 * Postgres: the owner becomes the founder, every row they wrote is theirs, and their password,
 * device keys and agents' tokens all keep working.
 */
class PeopleMigrationTest {
    private val password = "correct horse battery"
    private val hash = Passwords.hash(password)
    private val tokenSecret = "t".repeat(32)
    private val deviceKey = Tokens.random()
    private val agentToken = Tokens.random()

    private fun config(): Config = Config(
        dataDir = "unused", adminPasswordHash = hash, sessionSecret = "s".repeat(32), tokenSecret = tokenSecret,
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    /** What a server at migration 054 has after some use, written as its own code would have. */
    private fun seed(db: Database) {
        val t = 1_700_000_000_000L
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', ?, ?)", t, t)
        db.update("INSERT INTO project_groups (id, slug, name, created_at, updated_at) VALUES ('G1', 'apps', 'Apps', ?, ?)", t, t)
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'scout', ?, ?)", Tokens.hash(agentToken, tokenSecret), t)
        db.update("INSERT INTO owner_devices (id, name, token_hash, created_at) VALUES ('D1', 'Phone', ?, ?)", Tokens.hash(deviceKey, tokenSecret), t)
        db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES ('U1', 'P1', 'A1', 'shipped', ?)", t)
        db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M1', 'P1', 'human', 'nice', ?)", t)
        db.update("INSERT INTO messages (id, project_id, author, body, created_at, reply_to) VALUES ('M2', 'P1', 'agent:A1', 'thanks', ?, 'M1')", t + 1)
        db.update(
            "INSERT INTO approvals (id, agent_id, project_id, question, state, expires_at, decided_at) VALUES ('R1', 'A1', 'P1', 'Ship?', 'approved', ?, ?)",
            t + 10_000, t + 5,
        )
        db.update("INSERT INTO approvals (id, agent_id, project_id, question, state, expires_at) VALUES ('R2', 'A1', 'P1', 'Again?', 'timeout', ?)", t + 10_000)
        db.update("INSERT INTO reactions (id, message_id, actor, emoji, created_at) VALUES ('X1', 'M2', 'human', '👍', ?)", t)
        db.update("INSERT INTO reactions (id, message_id, actor, emoji, created_at) VALUES ('X2', 'M1', 'agent:A1', '👀', ?)", t)
        db.update("INSERT INTO notifications (id, kind, project_id, update_id, agent_id, title, body, created_at) VALUES ('N1', 'update', 'P1', 'U1', 'A1', 't', 'b', ?)", t)
        db.update("INSERT INTO notifications (id, kind, project_id, message_id, agent_id, title, body, created_at, seen_at) VALUES ('N2', 'reply', 'P1', 'M2', 'A1', 't', 'b', ?, ?)", t, t)
        db.update("INSERT INTO project_subscriptions (id, subscriber, project_id, level, created_at, chosen) VALUES ('S1', 'human', 'P1', 'replies', ?, 1)", t)
        db.update("INSERT INTO project_subscriptions (id, subscriber, project_id, created_at) VALUES ('S2', 'agent:A1', 'P1', ?)", t)
        db.update("INSERT INTO group_subscriptions (id, subscriber, group_id, level, created_at) VALUES ('S3', 'human', 'G1', 'muted', ?)", t)
        db.update("INSERT INTO thread_subscriptions (id, subscriber, kind, thread_id, project_id, created_at) VALUES ('S4', 'human', 'message', 'M1', 'P1', ?)", t)
    }

    private fun checkMigrated(db: Database) {
        assertEquals("owner", db.one("SELECT role FROM memberships WHERE id = 'founder' AND founder = 1 AND team_id = 'home'") { it.getString(1) })
        assertEquals("Owner", db.one("SELECT name FROM users WHERE id = 'founder' AND email IS NULL AND password_hash IS NULL") { it.getString(1) })
        assertEquals("founder", db.one("SELECT user_id FROM owner_devices WHERE id = 'D1'") { it.getString(1) })
        assertEquals("founder", db.one("SELECT user_id FROM messages WHERE id = 'M1'") { it.getString(1) })
        assertNull(db.one("SELECT user_id FROM messages WHERE id = 'M2'") { it.getString(1) })
        assertEquals("founder", db.one("SELECT decided_by FROM approvals WHERE id = 'R1'") { it.getString(1) })
        assertNull(db.one("SELECT decided_by FROM approvals WHERE id = 'R2'") { it.getString(1) })
        assertEquals("founder", db.one("SELECT user_id FROM reactions WHERE id = 'X1'") { it.getString(1) })
        assertEquals("", db.one("SELECT user_id FROM reactions WHERE id = 'X2'") { it.getString(1) })
        assertEquals(listOf("founder", "founder"), db.query("SELECT member_id FROM notifications ORDER BY id") { it.getString(1) })
        assertEquals("member:founder", db.one("SELECT subscriber FROM project_subscriptions WHERE id = 'S1'") { it.getString(1) })
        assertEquals("agent:A1", db.one("SELECT subscriber FROM project_subscriptions WHERE id = 'S2'") { it.getString(1) })
        assertEquals("member:founder", db.one("SELECT subscriber FROM group_subscriptions WHERE id = 'S3'") { it.getString(1) })
        assertEquals("member:founder", db.one("SELECT subscriber FROM thread_subscriptions WHERE id = 'S4'") { it.getString(1) })
        assertEquals(0, db.one("SELECT count(*) FROM project_subscriptions WHERE subscriber = 'human'") { it.getInt(1) })
    }

    /** The owner's old ways in still work, and their choices are still theirs. */
    private fun checkStillWorks(db: Database) {
        val app = App(config(), db)
        try {
            val device = assertNotNull(app.devices.authenticate(deviceKey), "an old device key still opens the dashboard")
            assertEquals("founder", device.userId)
            assertNotNull(app.agents.authenticate(agentToken), "an old agent token still works")
            val signedIn = assertIs<PasswordCheck.Ok>(app.checkPassword(password, "10.0.0.1"))
            assertEquals("founder", signedIn.person.id)
            val subs = dev.wildware.feed.server.domain.Subscriptions(db)
            assertEquals("replies", subs.level("member:founder", "P1"))
            assertEquals(true, subs.threadState("member:founder", dev.wildware.feed.server.domain.Subscriptions.Thread("message", "M1")))
            assertEquals(1L, app.notifications.unseen())
            // Two reactions of one emoji by two people are now allowed; one person still has one.
            app.feed.cards.react(dev.wildware.feed.server.domain.CardRef("message", "M2"), "human", "👍", true, "founder")
            assertEquals(1, db.one("SELECT count(*) FROM reactions WHERE message_id = 'M2'") { it.getInt(1) })
            assertTrue(!app.people.isTeam())
        } finally {
            app.close()
        }
    }

    @Test
    fun `SQLite at 054 with an owner's data migrates to people and keeps working`() {
        val db = Database.sqliteMemory(upTo = 54)
        seed(db)
        Migrations.run(db)
        checkMigrated(db)
        checkStillWorks(db)
    }

    @Test
    fun `Postgres at 054 with an owner's data migrates to people and keeps working`() {
        val url = System.getenv("PG_TEST_URL")
        assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        val db = Database.openPostgres(url, migrate = false, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
        PostgresMigrations.run(db, upTo = 54)
        seed(db)
        PostgresMigrations.run(db)
        checkMigrated(db)
        checkStillWorks(db)
    }
}
