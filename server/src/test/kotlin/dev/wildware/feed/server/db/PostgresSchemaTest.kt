package dev.wildware.feed.server.db

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Postgres schema says what SQLite's says: the same tables, columns, types (INTEGER as bigint,
 * TEXT as text), NOT NULLs, defaults, indexes (name, unique, partial) and foreign keys. Runs when
 * `PG_TEST_URL` points at a Postgres server.
 */
class PostgresSchemaTest {
    private val url: String? = System.getenv("PG_TEST_URL")?.takeIf { it.isNotBlank() }

    data class Column(val table: String, val name: String, val type: String, val notNull: Boolean, val default: String?)
    data class Index(val table: String, val name: String, val unique: Boolean, val partial: Boolean)
    data class ForeignKey(val table: String, val column: String, val target: String, val targetColumn: String, val onDelete: String)

    private fun sqlite(): Triple<Set<Column>, Set<Index>, Set<ForeignKey>> = Database.sqliteMemory().use { db ->
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'schema_migrations'") { it.getString(1) }
        val columns = tables.flatMap { t ->
            db.query("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?)", t) {
                val type = when (it.getString(2)) { "INTEGER" -> "bigint"; "TEXT" -> "text"; else -> it.getString(2) }
                Column(t, it.getString(1), type, it.getInt(3) == 1 || it.getInt(5) == 1, if (it.getString(1) == "seq" && it.getInt(5) == 1) null else it.getString(4))
            }
        }.toSet()
        val indexes = tables.flatMap { t ->
            db.query("SELECT name, \"unique\", partial, origin FROM pragma_index_list(?)", t) {
                if (it.getString(4) != "c") null else Index(t, it.getString(1), it.getInt(2) == 1, it.getInt(3) == 1)
            }.filterNotNull()
        }.toSet()
        val keys = tables.flatMap { t ->
            db.query("SELECT \"from\", \"table\", \"to\", on_delete FROM pragma_foreign_key_list(?)", t) {
                ForeignKey(t, it.getString(1), it.getString(2), it.getString(3), it.getString(4))
            }
        }.toSet()
        Triple(columns, indexes, keys)
    }

    private fun postgres(): Triple<Set<Column>, Set<Index>, Set<ForeignKey>> = Database.memory().use { db ->
        val columns = db.query(
            "SELECT table_name, column_name, data_type, is_nullable, column_default FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name <> 'schema_migrations'",
        ) {
            val default = it.getString(5)?.replace(Regex("::(text|bigint|integer)$"), "")
            Column(it.getString(1), it.getString(2), it.getString(3), it.getString(4) == "NO", default)
        }.toSet()
        val indexes = db.query(
            "SELECT t.relname, i.relname, x.indisunique, x.indpred IS NOT NULL, " +
                "EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid) " +
                "FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class t ON t.oid = x.indrelid " +
                "JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = current_schema() AND t.relname <> 'schema_migrations'",
        ) { if (it.getBoolean(5)) null else Index(it.getString(1), it.getString(2), it.getBoolean(3), it.getBoolean(4)) }.filterNotNull().toSet()
        val keys = db.query(
            "SELECT c.conrelid::regclass::text, a.attname, c.confrelid::regclass::text, af.attname, " +
                "CASE c.confdeltype WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL' WHEN 'r' THEN 'RESTRICT' ELSE 'NO ACTION' END " +
                "FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] " +
                "JOIN pg_attribute af ON af.attrelid = c.confrelid AND af.attnum = c.confkey[1] " +
                "WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace",
        ) { ForeignKey(it.getString(1).trim('"'), it.getString(2), it.getString(3).trim('"'), it.getString(4), it.getString(5)) }.toSet()
        Triple(columns, indexes, keys)
    }

    @Test
    fun `the Postgres schema matches SQLite's, table by table`() {
        assumeTrue(url != null, "PG_TEST_URL not set")
        val (sc, si, sk) = sqlite()
        val (pc, pi, pk) = postgres()
        assertEquals(sc.sortedBy { "${it.table}.${it.name}" }, pc.sortedBy { "${it.table}.${it.name}" })
        assertEquals(si.sortedBy { it.name }, pi.sortedBy { it.name })
        assertEquals(sk.sortedBy { "${it.table}.${it.column}" }, pk.sortedBy { "${it.table}.${it.column}" })
    }

    @Test
    fun `the Postgres baseline is the SQLite migration count, and every SQLite migration after it has a Postgres twin`() {
        val lastSqlite = Migrations.all.maxOf { it.version }
        val pg = PostgresMigrations.all.map { it.version }
        assertEquals(47, pg.first())
        assertEquals((47..lastSqlite).toList(), pg)
    }
}

class PostgresTextTest {
    @Test
    fun `Postgres refuses text with a NUL character as a bad argument`() {
        assumeTrue(!System.getenv("PG_TEST_URL").isNullOrBlank(), "PG_TEST_URL not set")
        Database.memory().use { db ->
            val error = kotlin.test.assertFailsWith<dev.wildware.feed.server.domain.DomainError> {
                db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('a', ?, 'h', 1)", "bad\u0000name")
            }
            assertEquals(400, error.status)
            assertEquals("Text can't contain the NUL character (U+0000).", error.message)
        }
    }

    @Test
    fun `Heroku's DATABASE_URL becomes a JDBC URL with SSL, and local hosts skip SSL`() {
        val (jdbc, props) = Database.postgresJdbc("postgres://u%40x:p%3Aw@db.example.com:5433/feed")
        assertEquals("jdbc:postgresql://db.example.com:5433/feed", jdbc)
        assertEquals("u@x", props["user"])
        assertEquals("p:w", props["password"])
        assertEquals("require", props["sslmode"])
        assertEquals("disable", Database.postgresJdbc("postgres://u:p@localhost/feed").second["sslmode"])
    }
}
