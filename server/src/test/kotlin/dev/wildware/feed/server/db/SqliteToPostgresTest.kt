package dev.wildware.feed.server.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The SQLite to Postgres copy, on a real Postgres (PG_TEST_URL). */
class SqliteToPostgresTest {
    private val pg: String? = System.getenv("PG_TEST_URL")?.takeIf { it.isNotBlank() }

    private fun sqliteWithRows(): java.io.File {
        val dir = Files.createTempDirectory("copy").toFile()
        Database.open(dir.path).use { db ->
            db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'rover', 'h1', 10)")
            db.update("INSERT INTO agents (id, name, token_hash, created_at, last_seen_at) VALUES ('A2', 'it''s', 'h2', 11, 99)")
            db.update("INSERT INTO projects (id, slug, name, status, pinned, created_at, updated_at) VALUES ('P1', 'p', 'P', 'active', 0, 1, 1)")
            // A deleted newest row leaves SQLite's high-water mark above max(seq); the copy must keep it.
            db.update("INSERT INTO tags (id, project_id, name, slug, created_at) VALUES ('T1', 'P1', 'gone', 'gone', 1)")
            db.update("DELETE FROM tags WHERE id = 'T1'")
        }
        // The TS server could store a NUL; Kotlin's own writes can't reach SQLite with one either way.
        DriverManager.getConnection("jdbc:sqlite:${dir.path}/${Database.FILE_NAME}").use { c ->
            c.createStatement().use { it.execute("UPDATE agents SET name = 'nul' || char(0) || 'here' WHERE id = 'A1'") }
        }
        return java.io.File(dir, Database.FILE_NAME)
    }

    @Test
    fun `a copy matches its source table by table, with NULs replaced and counted, and new rows get fresh seqs`() {
        assumeTrue(pg != null, "PG_TEST_URL not set")
        val file = sqliteWithRows()
        val tool = SqliteToPostgres()
        Database.memory().use { target ->
            val copied = tool.openSource(file).use { tool.copy(it, target) }
            assertEquals(2, copied.single { it.table == "agents" }.rows)
            assertEquals(1, copied.sumOf { it.nulsReplaced })
            assertEquals(5, copied.single { it.table == "embed_types" }.rows)
            tool.openSource(file).use { tool.verify(it, target) }
            assertEquals("nul␀here", target.one("SELECT name FROM agents WHERE id = 'A1'") { it.getString(1) })
            target.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A3', 'new', 'h3', 12)")
            val seqs = target.query("SELECT seq FROM agents ORDER BY seq") { it.getLong(1) }
            assertEquals(3, seqs.size)
            assertTrue(seqs[2] > seqs[1])
            target.update("INSERT INTO tags (id, project_id, name, slug, created_at) VALUES ('T2', 'P1', 'new', 'new', 2)")
            assertEquals(2L, target.one("SELECT seq FROM tags WHERE id = 'T2'") { it.getLong(1) }, "the next tag continues past the deleted one, as in SQLite")

            target.update("UPDATE agents SET name = 'changed' WHERE id = 'A2'")
            val e = assertFailsWith<SqliteToPostgres.Mismatch> { tool.openSource(file).use { tool.verify(it, target) } }
            assertTrue(e.message!!.contains("agents (SQLite 2 rows, Postgres 3;"), e.message)
        }
    }

    @Test
    fun `the way back gives a SQLite file with the same rows, at the same migration, and refuses an existing file`() {
        assumeTrue(pg != null, "PG_TEST_URL not set")
        val file = sqliteWithRows()
        val tool = SqliteToPostgres()
        Database.memory().use { target ->
            tool.openSource(file).use { tool.copy(it, target) }
            target.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A3', 'after', 'h3', 12)")
            val dir = Files.createTempDirectory("back").toFile()
            val back = PostgresToSqlite().export(target, dir.path)
            assertEquals(3, back.single { it.table == "agents" }.rows)
            Database.openReadOnly(dir.path).use { db ->
                assertEquals(listOf("A1", "A2", "A3"), db.query("SELECT id FROM agents ORDER BY seq") { it.getString(1) })
                assertEquals(5, db.one("SELECT count(*) FROM embed_types") { it.getInt(1) })
            }
            Database.open(dir.path).use { db ->
                db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A4', 'next', 'h4', 13)")
                val seqs = db.query("SELECT seq FROM agents ORDER BY seq") { it.getLong(1) }
                assertTrue(seqs.last() > seqs[2], "new rows get new seqs after an export")
            }
            val again = assertFailsWith<IllegalStateException> { PostgresToSqlite().export(target, dir.path) }
            assertTrue(again.message!!.endsWith("already exists; export only into a new folder."))
        }
    }

    @Test
    fun `it refuses a target that already has data`() {
        assumeTrue(pg != null, "PG_TEST_URL not set")
        val file = sqliteWithRows()
        val tool = SqliteToPostgres()
        Database.memory().use { target ->
            target.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('X', 'x', 'hx', 1)")
            val e = assertFailsWith<IllegalStateException> { tool.openSource(file).use { tool.copy(it, target) } }
            assertEquals("The Postgres database isn't empty: agents has 1 rows. Copy only into a new database.", e.message)
        }
    }

    @Test
    fun `an owner key from before people still opens the dashboard after copying an older file`() {
        assumeTrue(pg != null, "PG_TEST_URL not set")
        val secret = "t".repeat(32)
        val key = dev.wildware.feed.server.auth.Tokens.random()
        val dir = Files.createTempDirectory("copy-old").toFile()
        // A server at 054, before people: the owner's phone, words, reaction, bell row and follows.
        val db = Database.open(dir.path, migrate = false)
        Migrations.run(db, upTo = 54)
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'p', 'P', 1, 1)")
        db.update("INSERT INTO owner_devices (id, name, token_hash, created_at) VALUES ('D1', 'Phone', ?, 1)", dev.wildware.feed.server.auth.Tokens.hash(key, secret))
        db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M1', 'P1', 'human', 'hi', 1)")
        db.update("INSERT INTO reactions (id, message_id, actor, emoji, created_at) VALUES ('X1', 'M1', 'human', '👍', 1)")
        db.update("INSERT INTO notifications (id, kind, title, body, created_at) VALUES ('N1', 'reply', 't', 'b', 1)")
        db.update("INSERT INTO project_subscriptions (id, subscriber, project_id, level, created_at, chosen) VALUES ('S1', 'human', 'P1', 'muted', 1, 1)")
        db.close()
        val file = java.io.File(dir, Database.FILE_NAME)
        val tool = SqliteToPostgres()
        Database.memory().use { target ->
            tool.openSource(file).use { tool.copy(it, target) }
            tool.openSource(file).use { tool.verify(it, target) }
            val device = dev.wildware.feed.server.domain.Devices(target, secret).authenticate(key)
            assertEquals("founder", device?.userId, "the old key still opens the dashboard, as the founder")
            assertEquals("founder", target.one("SELECT user_id FROM messages WHERE id = 'M1'") { it.getString(1) })
            assertEquals("founder", target.one("SELECT user_id FROM reactions WHERE id = 'X1'") { it.getString(1) })
            assertEquals("founder", target.one("SELECT member_id FROM notifications WHERE id = 'N1'") { it.getString(1) })
            assertEquals("muted", dev.wildware.feed.server.domain.Subscriptions(target).level("member:founder", "P1"))
        }
    }
}
