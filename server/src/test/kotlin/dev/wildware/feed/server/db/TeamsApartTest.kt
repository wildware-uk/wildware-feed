package dev.wildware.feed.server.db

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migration 058 (each team's rows apart) on a database already in use, on SQLite and Postgres:
 * every row becomes the home team's, and old keys and tokens still work, but only there. Then the
 * rules that keep teams apart: the `:team` mark, the lint, and Postgres row security.
 */
class TeamsApartTest {
    private val tokenSecret = "t".repeat(32)
    private val deviceKey = Tokens.random()
    private val agentToken = Tokens.random()

    private fun config(): Config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("pw pw pw pw"), sessionSecret = "s".repeat(32), tokenSecret = tokenSecret,
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    /** A server at migration 056 after some use. */
    private fun seed(db: Database) {
        val t = 1_700_000_000_000L
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', ?, ?)", t, t)
        db.update("INSERT INTO project_groups (id, slug, name, created_at, updated_at) VALUES ('G1', 'apps', 'Apps', ?, ?)", t, t)
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'scout', ?, ?)", Tokens.hash(agentToken, tokenSecret), t)
        db.update("INSERT INTO owner_devices (id, name, token_hash, created_at, user_id) VALUES ('D1', 'Phone', ?, ?, 'founder')", Tokens.hash(deviceKey, tokenSecret), t)
        db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES ('U1', 'P1', 'A1', 'shipped', ?)", t)
        db.update("INSERT INTO messages (id, project_id, author, body, created_at, user_id) VALUES ('M1', 'P1', 'human', 'nice', ?, 'founder')", t)
        db.update("INSERT INTO approvals (id, agent_id, project_id, question, state, expires_at) VALUES ('R1', 'A1', 'P1', 'Ship?', 'pending', ?)", t + 10_000)
        db.update("INSERT INTO server_settings (key, value, updated_at) VALUES ('media_retention_days', '30', ?)", t)
    }

    private fun checkMigrated(db: Database) {
        Tenant.allTeams {
            for (table in TeamTables.ALL) {
                val notHome = db.one("SELECT count(*) FROM \"$table\" WHERE team_id <> 'home'") { it.getLong(1) }
                assertEquals(0L, notHome, "every $table row is the home team's")
            }
            assertEquals(1L, db.one("SELECT count(*) FROM messages WHERE team_id = 'home'") { it.getLong(1) })
        }
    }

    private fun checkStillWorks(db: Database) {
        val app = App(config(), db)
        try {
            assertNotNull(app.devices.authenticate(deviceKey), "an old device key still opens the home team")
            assertNotNull(app.agents.authenticate(agentToken).first, "an old agent token still works")
            db.update("INSERT INTO teams (id, slug, name, created_at) VALUES ('other', 'other', 'Other', 1)")
            Tenant.with("other") {
                assertNull(app.devices.authenticate(deviceKey), "the home team's key opens no other team")
                assertNull(app.agents.authenticate(agentToken).first, "nor its agents' tokens")
                assertEquals(emptyList(), app.reads.listProjects())
            }
            assertEquals(listOf("web"), app.reads.listProjects().map { it["slug"].toString().trim('"') })
        } finally {
            app.close()
        }
    }

    @Test
    fun `SQLite at 057 moves into the home team and keeps working`() {
        val db = Database.sqliteMemory(upTo = 57)
        seed(db)
        Migrations.run(db)
        checkMigrated(db)
        checkStillWorks(db)
    }

    @Test
    fun `Postgres at 057 moves into the home team and keeps working`() {
        val url = System.getenv("PG_TEST_URL")
        assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        val db = Database.openPostgres(url, migrate = false, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
        PostgresMigrations.run(db, upTo = 57)
        seed(db)
        PostgresMigrations.run(db)
        checkMigrated(db)
        checkStillWorks(db)
    }

    @Test
    fun `the team mark is bound where it stands, between the other arguments`() {
        val (sql, args) = Tenant.with("acme") { TeamTables.expand("SELECT 1 FROM messages WHERE id = ? AND team_id = :team AND seq > ?", arrayOf<Any?>("M1", 5)) }
        assertEquals("SELECT 1 FROM messages WHERE id = ? AND team_id = ? AND seq > ?", sql)
        assertEquals(listOf<Any?>("M1", "acme", 5), args.toList())
        // `::team` (a cast) and `:team_id` are not the mark.
        assertEquals("SELECT x::team, :team_id FROM t", TeamTables.expand("SELECT x::team, :team_id FROM t", arrayOf<Any?>()).first)
        assertFails { Tenant.allTeams { TeamTables.expand("SELECT 1 FROM messages WHERE team_id = :team", arrayOf<Any?>()) } }
    }

    @Test
    fun `the lint fails a statement on a team table that never names its team`() {
        val e = assertFailsWith<IllegalStateException> { TeamTables.lint("SELECT body FROM messages WHERE id = ?", exemptTests = false) }
        assertTrue(e.message!!.contains("[messages]"))
        assertFailsWith<IllegalStateException> { TeamTables.lint("INSERT INTO tags (id, slug) VALUES (?, ?)", exemptTests = false) }
        assertFailsWith<IllegalStateException> { TeamTables.lint("UPDATE owner_devices SET name = ? WHERE id = ?", exemptTests = false) }
        assertFailsWith<IllegalStateException> { TeamTables.lint("SELECT 1 FROM teams t JOIN memberships m ON m.user_id = ?", exemptTests = false) }
        TeamTables.lint("SELECT body FROM messages WHERE team_id = :team AND id = ?", exemptTests = false)
        TeamTables.lint("SELECT id FROM teams", exemptTests = false)
        TeamTables.lint("SELECT email FROM users WHERE id = ?", exemptTests = false)
        Tenant.allTeams { TeamTables.lint("SELECT count(*) FROM messages", exemptTests = false) }
    }

    @Test
    fun `no team for the work is an error when strict, and the home team when not`() {
        val before = Tenant.strict
        try {
            Tenant.strict = true
            assertFails { Tenant.id }
            assertEquals("acme", Tenant.with("acme") { Tenant.id })
            Tenant.strict = false
            assertEquals(Tenant.HOME, Tenant.id)
        } finally {
            Tenant.strict = before
        }
    }

    @Test
    fun `row security shows a team only its own rows, and refuses a write into another`() {
        assumeTrue(!System.getenv("PG_TEST_URL").isNullOrBlank(), "PG_TEST_URL not set")
        Database.memory().use { db ->
            assertTrue(db.rowSecurity)
            db.update("INSERT INTO teams (id, slug, name, created_at) VALUES ('acme', 'acme', 'Acme', 1)")
            db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
            Tenant.with("acme") {
                // Not even a statement that forgets its team sees the home team's project.
                assertEquals(0L, db.one("SELECT count(*) FROM projects") { it.getLong(1) })
                assertFails { db.update("INSERT INTO projects (id, slug, name, created_at, updated_at, team_id) VALUES ('P2', 'x', 'X', 1, 1, 'home')") }
                db.update("INSERT INTO projects (id, slug, name, created_at, updated_at, team_id) VALUES ('P3', 'acme-web', 'Web', 1, 1, :team)")
                assertEquals(1L, db.one("SELECT count(*) FROM projects") { it.getLong(1) })
            }
            assertEquals(listOf("P1"), db.query("SELECT id FROM projects") { it.getString(1) })
            assertEquals(2L, Tenant.allTeams { db.one("SELECT count(*) FROM projects") { it.getLong(1) } })
        }
    }
}
