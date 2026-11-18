package dev.wildware.feed.parity

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The database file both servers must be able to open. Needs PARITY_DATA_DIR, the server's
 * DATA_DIR; it only reads. Spec: server/spec/06-database.md.
 */
class DatabaseTest : ParityTest() {
    private fun <T> database(block: (java.sql.Connection) -> T): T {
        val dir = System.getenv("PARITY_DATA_DIR")
        assumeTrue(!dir.isNullOrBlank(), "set PARITY_DATA_DIR to check the database file")
        val file = File(dir, "agent-dashboard.db")
        assumeTrue(file.exists(), "no database at $file")
        return DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use(block)
    }

    private fun indexes(table: String): Map<String, List<String>> = database { db ->
        val names = db.createStatement().executeQuery("PRAGMA index_list($table)").use { rows ->
            generateSequence { if (rows.next()) rows.getString("name") else null }.toList()
        }
        names.associateWith { name ->
            db.createStatement().executeQuery("PRAGMA index_info($name)").use { rows ->
                generateSequence { if (rows.next()) rows.getString("name") else null }.toList()
            }
        }
    }

    @Test
    fun `migrations are recorded with a checksum each, in order`() = parity {
        val versions = database { db ->
            db.createStatement().executeQuery("SELECT version, name, checksum FROM schema_migrations ORDER BY version").use { rows ->
                generateSequence {
                    if (rows.next()) Triple(rows.getInt(1), rows.getString(2), rows.getString(3)) else null
                }.toList()
            }
        }
        assertEquals((1..versions.size).toList(), versions.map { it.first }, "no gaps")
        assertTrue(versions.size >= 45, "at least migration 045")
        assertTrue(versions.all { Regex("^[0-9a-f]{64}$").matches(it.third) }, "SHA-256 hex checksums")
    }

    @Test
    fun `the media indexes on request_id and task_id exist`() = parity {
        val media = indexes("media")
        assertEquals(listOf("request_id"), media["media_request"])
        assertEquals(listOf("task_id"), media["media_task"])
    }
}
