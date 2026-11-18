package dev.wildware.feed.server.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one SQLite connection, used by one caller at a time, as the TS server does
 * (spec/06-database.md §1). Opening runs every pending migration.
 */
class Database private constructor(private val connection: Connection) : AutoCloseable {
    private val lock = ReentrantLock()

    /** Whether this thread is inside [withConnection] or [transaction] right now. */
    val heldByCurrentThread: Boolean get() = lock.isHeldByCurrentThread

    /** Run [block] with the connection, alone. */
    fun <T> withConnection(block: (Connection) -> T): T = lock.withLock { block(connection) }

    /** Run [block] in one transaction: all of it or none. */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = withConnection { c ->
        c.prepareStatement(sql).use { st ->
            st.bind(args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }
    }

    fun <T> one(sql: String, vararg args: Any?, row: (ResultSet) -> T): T? = query(sql, *args, row = row).firstOrNull()

    fun update(sql: String, vararg args: Any?): Int = withConnection { c ->
        c.prepareStatement(sql).use { st -> st.bind(args); st.executeUpdate() }
    }

    override fun close() = lock.withLock { connection.close() }

    companion object {
        const val FILE_NAME = "agent-dashboard.db"
        const val MIGRATIONS_TABLE = "schema_migrations"

        /** `<dataDir>/agent-dashboard.db`, created with its folder if missing, then migrated. */
        fun open(dataDir: String, migrate: Boolean = true): Database {
            val file = File(dataDir, FILE_NAME)
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
            connection.createStatement().use { st ->
                st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("PRAGMA busy_timeout = 5000")
            }
            val db = Database(connection)
            if (migrate) Migrations.run(db)
            return db
        }

        /** An in-memory database, migrated, for tests. */
        fun memory(): Database {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            return Database(connection).also { Migrations.run(it) }
        }
    }
}

fun PreparedStatement.bind(args: Array<out Any?>) {
    args.forEachIndexed { i, value ->
        when (value) {
            null -> setObject(i + 1, null)
            is Boolean -> setInt(i + 1, if (value) 1 else 0)
            is Int -> setInt(i + 1, value)
            is Long -> setLong(i + 1, value)
            is String -> setString(i + 1, value)
            is Double -> setDouble(i + 1, value)
            else -> error("cannot bind ${value::class}")
        }
    }
}

/** Nullable column readers: JDBC's getLong answers 0 for NULL. */
fun ResultSet.longOrNull(column: String): Long? = getLong(column).let { if (wasNull()) null else it }
fun ResultSet.intOrNull(column: String): Int? = getInt(column).let { if (wasNull()) null else it }
fun ResultSet.bool(column: String): Boolean = getInt(column) == 1

/**
 * The migration runner (spec/06-database.md §3). The SQL of each migration is the TS server's,
 * byte for byte, so the recorded checksums match whichever server wrote them.
 */
object Migrations {
    @Serializable
    data class Migration(val version: Int, val name: String, val file: String, val rebuildsTables: Boolean, val checksum: String) {
        val sql: String by lazy {
            Migrations::class.java.getResource("/migrations/$file")!!.readBytes().toString(Charsets.UTF_8)
        }
    }

    val all: List<Migration> by lazy {
        val text = Migrations::class.java.getResource("/migrations/migrations.json")!!.readText()
        Json.decodeFromString<List<Migration>>(text).sortedBy { it.version }
    }

    fun checksum(sql: String): String =
        MessageDigest.getInstance("SHA-256").digest(sql.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun run(db: Database) {
        db.withConnection { c ->
            c.createStatement().use {
                // Whitespace as the TS server writes it, so sqlite_master reads the same either way.
                it.execute(
                    "CREATE TABLE IF NOT EXISTS ${Database.MIGRATIONS_TABLE} (\n\t\t\tversion INTEGER PRIMARY KEY,\n\t\t\tname TEXT NOT NULL,\n" +
                        "\t\t\tchecksum TEXT NOT NULL,\n\t\t\tapplied_at INTEGER NOT NULL\n\t\t) STRICT",
                )
            }
        }
        val applied = db.query("SELECT version, checksum FROM ${Database.MIGRATIONS_TABLE}") { it.getInt(1) to it.getString(2) }.toMap()
        val known = all.associateBy { it.version }
        require(known.size == all.size) { "duplicate migration version" }
        val unknown = applied.keys.filter { it !in known }
        check(unknown.isEmpty()) {
            "Refusing to run against a newer database: it has migrations ${unknown.sorted()} that this build does not know."
        }
        for ((version, stored) in applied) {
            val migration = known.getValue(version)
            check(checksum(migration.sql) == stored) {
                "Migrations are append-only: migration $version (${migration.name}) has changed since it was applied."
            }
        }
        for (migration in all.filter { it.version !in applied }) apply(db, migration)
    }

    private fun apply(db: Database, migration: Migration) = db.withConnection { c ->
        if (migration.rebuildsTables) c.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
        try {
            c.autoCommit = false
            try {
                c.createStatement().use { st -> SqlScript.split(migration.sql).forEach { st.execute(it) } }
                c.prepareStatement("INSERT INTO ${Database.MIGRATIONS_TABLE} (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)").use {
                    it.bind(arrayOf<Any?>(migration.version, migration.name, checksum(migration.sql), System.currentTimeMillis()))
                    it.executeUpdate()
                }
                c.commit()
            } catch (e: Throwable) {
                c.rollback()
                throw IllegalStateException("migration ${migration.version} (${migration.name}) failed: ${e.message}", e)
            } finally {
                c.autoCommit = true
            }
            if (migration.rebuildsTables) {
                val dangling = c.createStatement().use { st -> st.executeQuery("PRAGMA foreign_key_check").use { it.next() } }
                check(!dangling) { "migration ${migration.version} (${migration.name}) left rows pointing at nothing" }
            }
        } finally {
            if (migration.rebuildsTables) c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        }
    }
}
