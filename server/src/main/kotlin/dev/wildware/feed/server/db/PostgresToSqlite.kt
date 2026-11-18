package dev.wildware.feed.server.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * The way back: a Postgres database copied into a new SQLite file that the TS server (or this one)
 * can run on, then checked the same way as the copy forward (row counts and checksums per table).
 * Both sides must be at the same migration.
 */
class PostgresToSqlite(private val log: (String) -> Unit = {}) {
    fun export(source: Database, dataDir: String): List<SqliteToPostgres.TableReport> {
        val file = File(dataDir, Database.FILE_NAME)
        check(!file.exists()) { "$file already exists; export only into a new folder." }
        val pgVersions = source.query("SELECT version FROM ${Database.MIGRATIONS_TABLE} ORDER BY version") { it.getInt(1) }
        check(pgVersions == PostgresMigrations.all.map { it.version }) {
            "The Postgres database is at migration ${pgVersions.lastOrNull()}; this build exports only one at ${PostgresMigrations.all.last().version}."
        }
        check(pgVersions.last() == Migrations.all.last().version) { "this build's SQLite and Postgres migrations end at different versions" }
        // A fresh file at the same migration, with its seed rows (replaced below by the copied ones).
        Database.open(dataDir).close()
        val copier = SqliteToPostgres(log)
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { target ->
            val tables = copier.tables(target)
            target.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
            target.autoCommit = false
            try {
                source.withConnection { pg ->
                    for ((table, columns) in tables) {
                        target.createStatement().use { it.execute("DELETE FROM \"$table\"") }
                        val rows = copyTable(pg, target, table, columns)
                        log("  $table: $rows rows")
                    }
                }
                target.commit()
            } catch (e: Throwable) {
                target.rollback()
                throw e
            } finally {
                target.autoCommit = true
            }
            val dangling = target.createStatement().use { st -> st.executeQuery("PRAGMA foreign_key_check").use { it.next() } }
            check(!dangling) { "the export has rows pointing at nothing" }
        }
        return copier.openSource(file).use { copier.verify(it, source) }
    }

    private fun copyTable(pg: Connection, target: Connection, table: String, columns: List<String>): Long {
        val names = columns.joinToString(", ")
        var rows = 0L
        target.prepareStatement("INSERT INTO \"$table\" ($names) VALUES (${columns.joinToString(", ") { "?" }})").use { insert ->
            pg.createStatement().use { st ->
                st.fetchSize = 1000
                st.executeQuery("SELECT $names FROM \"$table\" ORDER BY ${if ("seq" in columns) "seq" else names}").use { rs ->
                    while (rs.next()) {
                        for (i in columns.indices) {
                            when (val v = rs.getObject(i + 1)) {
                                null -> insert.setNull(i + 1, Types.NULL)
                                is Number -> insert.setLong(i + 1, v.toLong())
                                is String -> insert.setString(i + 1, v)
                                else -> error("$table.${columns[i]}: a ${v::class.simpleName} value")
                            }
                        }
                        insert.addBatch()
                        if (++rows % 500 == 0L) insert.executeBatch()
                    }
                }
            }
            insert.executeBatch()
        }
        return rows
    }
}
