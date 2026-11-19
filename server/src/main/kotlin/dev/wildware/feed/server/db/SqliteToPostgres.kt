package dev.wildware.feed.server.db

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/**
 * Copies a SQLite database (the TS server's file, or this server's) into a fresh Postgres one, then
 * proves the copy: every table's row count and a checksum over every value, in `seq` order, must
 * match. Postgres can't hold U+0000 in text, so any found is replaced with [NUL_STAND_IN] (U+2400,
 * "␀"), counted, and reported; the checksum takes the replacement into account.
 */
class SqliteToPostgres(private val log: (String) -> Unit = {}) {
    data class TableReport(val table: String, val rows: Long, val checksum: String, val nulsReplaced: Int)

    class Mismatch(message: String) : IllegalStateException(message)

    /** Opens [sqliteFile] read-only. Refuses a file whose migrations this build doesn't know exactly. */
    fun openSource(sqliteFile: java.io.File): Connection {
        require(sqliteFile.isFile) { "no database at $sqliteFile" }
        val c = DriverManager.getConnection("jdbc:sqlite:file:${sqliteFile.absolutePath}?mode=ro")
        val applied = c.createStatement().use { st ->
            st.executeQuery("SELECT version, checksum FROM ${Database.MIGRATIONS_TABLE} ORDER BY version").use { rs ->
                buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) }
            }
        }
        // Any database this build knows: an older one (the TS server's, at 047) copies into the newer
        // Postgres schema with the newer columns left empty.
        val known = Migrations.all.associate { it.version to Migrations.checksum(it.sql) }
        check(applied.isNotEmpty() && applied.all { (v, sum) -> known[v] == sum }) {
            "The SQLite database has migrations this build does not know, or that changed (it is at " +
                "${applied.lastOrNull()?.first}); copy it with the build that last ran on it."
        }
        return c
    }

    /** The tables to copy, in SQLite's order, with their columns. */
    fun tables(source: Connection): List<Pair<String, List<String>>> {
        val names = source.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name <> '${Database.MIGRATIONS_TABLE}' ORDER BY rowid",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        return names.map { t ->
            t to source.prepareStatement("SELECT name FROM pragma_table_info(?) ORDER BY cid").use { st ->
                st.setString(1, t)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
    }

    /**
     * Copies every row, in one transaction with foreign keys checked at the end, into [target], which
     * must be freshly migrated: empty apart from the baseline's seed rows (which the source has too).
     */
    fun copy(source: Connection, target: Database): List<TableReport> {
        val tables = tables(source)
        // Rows a migration inserts itself (the baseline's, the home team and its founder): the source has them too.
        val seeds = PostgresMigrations.all.joinToString("\n") { it.sql }
        return target.transaction { pg ->
            for ((table, _) in tables) {
                val rows = pg.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM \"$table\"").use { it.next(); it.getLong(1) } }
                val seeded = seeds.contains("INSERT INTO \"$table\"")
                check(rows == 0L || seeded) { "The Postgres database isn't empty: $table has $rows rows. Copy only into a new database." }
            }
            pg.createStatement().use { it.execute("SET CONSTRAINTS ALL DEFERRED") }
            tables.map { (table, columns) ->
                if (seeds.contains("INSERT INTO \"$table\"")) pg.createStatement().use { it.execute("DELETE FROM \"$table\"") }
                copyTable(source, pg, table, columns).also {
                    log("  ${it.table}: ${it.rows} rows" + if (it.nulsReplaced > 0) ", ${it.nulsReplaced} NUL characters replaced" else "")
                }
            }.also {
                catchUp(source, pg)
                // The next seq continues from SQLite's high-water mark, which deleted rows leave above max(seq).
                val next = nextSeqs(source)
                for ((table, columns) in tables) {
                    if ("seq" in columns) pg.prepareStatement("SELECT setval(pg_get_serial_sequence('\"$table\"', 'seq'), ?, false)").use { st ->
                        st.setLong(1, next[table] ?: 1L)
                        st.executeQuery().close()
                    }
                }
            }
        }
    }

    private fun copyTable(source: Connection, pg: Connection, table: String, columns: List<String>): TableReport {
        val names = columns.joinToString(", ")
        val marks = columns.joinToString(", ") { "?" }
        var rows = 0L
        var nuls = 0
        val digest = MessageDigest.getInstance("SHA-256")
        pg.prepareStatement("INSERT INTO \"$table\" ($names) VALUES ($marks)").use { insert ->
            source.createStatement().use { st ->
                st.executeQuery("SELECT $names FROM \"$table\" ORDER BY ${order(columns)}").use { rs ->
                    while (rs.next()) {
                        for (i in columns.indices) {
                            when (val v = rs.getObject(i + 1)) {
                                null -> { insert.setNull(i + 1, Types.NULL); feed(digest, null) }
                                is Number -> { insert.setLong(i + 1, v.toLong()); feed(digest, v.toLong()) }
                                is String -> {
                                    val count = v.count { it == '\u0000' }
                                    nuls += count
                                    val text = if (count == 0) v else v.replace('\u0000', NUL_STAND_IN)
                                    insert.setString(i + 1, text)
                                    feed(digest, text)
                                }
                                else -> error("$table.${columns[i]}: a ${v::class.simpleName} value; the schema holds only integers and text")
                            }
                        }
                        insert.addBatch()
                        rows++
                        if (rows % BATCH == 0L) insert.executeBatch()
                    }
                }
            }
            insert.executeBatch()
        }
        return TableReport(table, rows, hex(digest.digest()), nuls)
    }

    /** The seq SQLite would give each table's next row: one past the larger of its high-water mark and max(seq). */
    fun nextSeqs(sqlite: Connection): Map<String, Long> {
        val high = sqlite.createStatement().use { st ->
            st.executeQuery("SELECT name, seq FROM sqlite_sequence").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) } }
        }
        return tables(sqlite).filter { "seq" in it.second }.associate { (table, _) ->
            val max = sqlite.createStatement().use { st -> st.executeQuery("SELECT coalesce(max(seq), 0) FROM \"$table\"").use { it.next(); it.getLong(1) } }
            table to maxOf(max, high[table] ?: 0L) + 1
        }
    }

    /** The seq Postgres will give each table's next row. */
    fun nextSeqs(pg: Connection, tables: List<String>): Map<String, Long> = tables.associateWith { table ->
        val name = pg.createStatement().use { st ->
            st.executeQuery("SELECT pg_get_serial_sequence('\"$table\"', 'seq')").use { it.next(); it.getString(1) }
        }
        pg.createStatement().use { st ->
            st.executeQuery("SELECT last_value, is_called FROM $name").use { it.next(); if (it.getBoolean(2)) it.getLong(1) + 1 else it.getLong(1) }
        }
    }

    /** The newest migration the SQLite file has. */
    private fun sourceVersion(source: Connection): Int = source.createStatement().use { st ->
        st.executeQuery("SELECT coalesce(max(version), 0) FROM ${Database.MIGRATIONS_TABLE}").use { it.next(); it.getInt(1) }
    }

    /**
     * A source older than the target leaves the target's newer columns empty. Where a later
     * migration filled them in for existing rows, do the same here, so the copy is what migrating
     * the file in place would have made. Migration 055 (people): everything the owner did is the
     * founder's, and their device keys, bell and follows with it.
     */
    private fun catchUp(source: Connection, pg: Connection) {
        val version = sourceVersion(source)
        for ((since, statements) in CATCH_UP) {
            if (version >= since) continue
            pg.createStatement().use { st -> statements.forEach { st.executeUpdate(it) } }
        }
    }

    /** A source value as the copy holds it after [catchUp]. */
    private fun caughtUp(version: Int, table: String, column: String, value: String): String =
        if (version < 55 && column == "subscriber" && table in SUBSCRIPTION_TABLES && value == "human") "member:founder" else value

    /** Counts and checksums on both sides; throws [Mismatch] naming every table that differs. */
    fun verify(source: Connection, target: Database): List<TableReport> {
        val tables = tables(source)
        val wrong = mutableListOf<String>()
        val sqliteNext = nextSeqs(source)
        val pgNext = target.withConnection { nextSeqs(it, sqliteNext.keys.toList()) }
        for ((table, next) in sqliteNext) {
            if (pgNext[table] != next) wrong += "$table (next seq: SQLite $next, Postgres ${pgNext[table]})"
        }
        val version = sourceVersion(source)
        val reports = tables.map { (table, columns) ->
            val a = checksum(source, table, columns, replaceNul = true) { column, v -> caughtUp(version, table, column, v) }
            val b = target.withConnection { checksum(it, table, columns, replaceNul = false) }
            if (a.rows != b.rows || a.checksum != b.checksum) {
                wrong += "$table (SQLite ${a.rows} rows, Postgres ${b.rows}; checksums ${if (a.checksum == b.checksum) "match" else "differ"})"
            }
            log("  $table: ${a.rows} rows, ${if (a == b.copy(nulsReplaced = a.nulsReplaced)) "identical" else "DIFFERENT"}")
            a
        }
        if (wrong.isNotEmpty()) throw Mismatch("The copy differs from the source: " + wrong.joinToString("; "))
        return reports
    }

    private fun checksum(c: Connection, table: String, columns: List<String>, replaceNul: Boolean, map: (String, String) -> String = { _, v -> v }): TableReport {
        val digest = MessageDigest.getInstance("SHA-256")
        var rows = 0L
        var nuls = 0
        c.createStatement().use { st ->
            st.executeQuery("SELECT ${columns.joinToString(", ")} FROM \"$table\" ORDER BY ${order(columns)}").use { rs ->
                while (rs.next()) {
                    for (i in columns.indices) {
                        when (val v = rs.getObject(i + 1)) {
                            null -> feed(digest, null)
                            is Number -> feed(digest, v.toLong())
                            is String -> {
                                nuls += v.count { it == '\u0000' }
                                feed(digest, map(columns[i], if (replaceNul) v.replace('\u0000', NUL_STAND_IN) else v))
                            }
                            else -> error("$table.${columns[i]}: unexpected ${v::class.simpleName}")
                        }
                    }
                    rows++
                }
            }
        }
        return TableReport(table, rows, hex(digest.digest()), nuls)
    }

    /** `seq` where there is one; otherwise every column, so the order is the same on both engines. */
    private fun order(columns: List<String>) = if ("seq" in columns) "seq" else columns.joinToString(", ")

    private fun feed(digest: MessageDigest, value: Any?) {
        val text = when (value) {
            null -> "N;"
            is Long -> "I$value;"
            is String -> "T${value.length}:$value;"
            else -> error("unreachable")
        }
        digest.update(text.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val NUL_STAND_IN = '␀'
        private const val BATCH = 500

        private val SUBSCRIPTION_TABLES = setOf("project_subscriptions", "group_subscriptions", "thread_subscriptions")

        /** What a migration did to existing rows, by its version, for a source from before it ([catchUp]). */
        private val CATCH_UP: List<Pair<Int, List<String>>> = listOf(
            55 to listOf(
                "UPDATE owner_devices SET user_id = 'founder' WHERE user_id IS NULL",
                "UPDATE messages SET user_id = 'founder' WHERE author = 'human' AND user_id IS NULL",
                "UPDATE approvals SET decided_by = 'founder' WHERE state IN ('approved', 'rejected') AND decided_by IS NULL",
                "UPDATE reactions SET user_id = 'founder' WHERE actor = 'human' AND user_id = ''",
                "UPDATE notifications SET member_id = 'founder' WHERE member_id IS NULL",
                "UPDATE project_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human'",
                "UPDATE group_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human'",
                "UPDATE thread_subscriptions SET subscriber = 'member:founder' WHERE subscriber = 'human'",
            ),
        )
    }
}
