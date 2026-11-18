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

            target.update("UPDATE agents SET name = 'changed' WHERE id = 'A2'")
            val e = assertFailsWith<SqliteToPostgres.Mismatch> { tool.openSource(file).use { tool.verify(it, target) } }
            assertTrue(e.message!!.contains("agents (SQLite 2 rows, Postgres 3;"), e.message)
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
}
