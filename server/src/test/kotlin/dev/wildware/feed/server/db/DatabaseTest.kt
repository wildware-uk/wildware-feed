package dev.wildware.feed.server.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseTest {
    @Test
    fun `the migrations are the TS server's, byte for byte`() {
        assertEquals((1..47).toList(), Migrations.all.map { it.version })
        for (m in Migrations.all) assertEquals(m.checksum, Migrations.checksum(m.sql), "migration ${m.version}")
        assertEquals(listOf(16, 25, 26, 31, 34, 38), Migrations.all.filter { it.rebuildsTables }.map { it.version })
    }

    @Test
    fun `a fresh database ends with exactly the TS server's schema`() {
        val expected = Json.parseToJsonElement(javaClass.getResource("/ts-schema.json")!!.readText()).let { root ->
            (root as kotlinx.serialization.json.JsonArray).map {
                val o = it.jsonObject
                listOf("type", "name", "table", "sql").map { k -> o[k]?.jsonPrimitive?.content }
            }
        }
        Database.memory().use { db ->
            val actual = db.query(
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
            assertEquals(expected.size, actual.size)
            for ((want, got) in expected.zip(actual)) assertEquals(want, got)
        }
    }

    @Test
    fun `opening again applies nothing, and a changed or unknown migration refuses to start`() {
        val dir = Files.createTempDirectory("feed-db").toFile()
        try {
            Database.open(dir.path).use { db ->
                assertEquals(47, db.one("SELECT count(*) FROM schema_migrations") { it.getInt(1) })
            }
            Database.open(dir.path).use { db ->
                assertEquals(47, db.one("SELECT count(*) FROM schema_migrations") { it.getInt(1) })
                assertEquals("wal", db.one("PRAGMA journal_mode") { it.getString(1) })
                db.update("UPDATE schema_migrations SET checksum = 'x' WHERE version = 3")
            }
            val changed = assertFailsWith<IllegalStateException> { Database.open(dir.path) }
            assertTrue(changed.message!!.startsWith("Migrations are append-only"))

            Database.open(dir.path, migrate = false).use { db ->
                db.update("UPDATE schema_migrations SET checksum = ? WHERE version = 3", Migrations.all[2].checksum)
                db.update("INSERT INTO schema_migrations VALUES (99, 'future', 'x', 0)")
            }
            val newer = assertFailsWith<IllegalStateException> { Database.open(dir.path) }
            assertTrue(newer.message!!.startsWith("Refusing to run against a newer database"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
