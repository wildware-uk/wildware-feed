package dev.wildware.feed.server.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Which database engine a [Database] talks to. */
enum class Dialect { SQLITE, POSTGRES }

/**
 * The one database connection, used by one caller at a time, as the TS server does
 * (spec/06-database.md §1). Opening runs every pending migration.
 *
 * SQLite is the TS server's file and the desktop app's store; Postgres is for hosts with no lasting
 * disk (Heroku). The SQL is shared: the few things SQLite spells differently go through [dialect]
 * ([greatest], [asciiFold], [jsonText]), and on Postgres an untyped `? IS NULL` is given a type.
 */
class Database private constructor(
    private var connection: Connection,
    val dialect: Dialect,
    private val reconnect: (() -> Connection)? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private val lock = ReentrantLock()

    /**
     * Postgres row security (docs/HOSTING.md §2), the backstop behind every statement naming its
     * team: when on, each statement first tells the connection whose rows it may see
     * (`app.team`), and the policies migration 058 made hide every other team's rows. Set by
     * [TeamTables.setRowSecurity]. One connection behind one lock, so the setting is the
     * connection's own, changed only when the team changes, and forgotten on reconnect.
     */
    @Volatile var rowSecurity: Boolean = false
    private var teamOnConnection: String? = null

    /** How many statements [query] and [update] have run, for tests that keep reads from growing per row. */
    @Volatile var statements: Long = 0L
        private set

    /** Whether this thread is inside [withConnection] or [transaction] right now. */
    val heldByCurrentThread: Boolean get() = lock.isHeldByCurrentThread

    /** When the connection was last handed out ([System.nanoTime]), and whether a statement on it failed since. */
    private var lastUsed = System.nanoTime()
    private var suspect = false

    /**
     * The connection, opened again first if the server dropped it (Postgres only). It is checked
     * (a round trip) only after a statement failed or after [IDLE_CHECK_MS] unused, not before
     * every statement: with one connection, that round trip was paid by every request.
     */
    private fun live(): Connection {
        val again = reconnect ?: return connection
        val idle = (System.nanoTime() - lastUsed) / 1_000_000 >= IDLE_CHECK_MS
        if (connection.autoCommit && (connection.isClosed || ((suspect || idle) && !connection.isValid(5)))) {
            runCatching { connection.close() }
            connection = again()
            teamOnConnection = null
        }
        suspect = false
        lastUsed = System.nanoTime()
        return connection
    }

    /**
     * Hold the one connection's lock for [block]. A wait of [SLOW_WAIT_MS] or more is logged: every
     * request queues here, so a long wait means something held the connection too long.
     */
    private inline fun <T> locked(block: () -> T): T {
        if (lock.isHeldByCurrentThread) return lock.withLock(block)
        val start = System.nanoTime()
        lock.lock()
        try {
            val waited = (System.nanoTime() - start) / 1_000_000
            if (waited >= SLOW_WAIT_MS) System.err.println("database: waited $waited ms for the connection")
            return try {
                block()
            } catch (e: SQLException) {
                suspect = true
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    /** Before a statement: tell Postgres whose rows it may touch, when that changed. */
    private fun setTeam(c: Connection) {
        if (!rowSecurity) return
        val team = Tenant.currentOrNull ?: (if (Tenant.strict) "" else Tenant.HOME)
        if (team == teamOnConnection) return
        c.prepareStatement("SELECT set_config('app.team', ?, false)").use { st ->
            st.setString(1, team)
            st.executeQuery().close()
        }
        teamOnConnection = team
    }

    /** Run [block] with the connection, alone. */
    fun <T> withConnection(block: (Connection) -> T): T = locked { live().let { c -> setTeam(c); block(c) } }

    /** How deep this thread is in [transaction]: an inner one joins the outer. */
    private var depth = 0

    /** Run [block] in one transaction: all of it or none. Inside another, it is part of that one. */
    fun <T> transaction(block: (Connection) -> T): T = locked {
        if (depth > 0) {
            depth++
            try {
                return@locked block(connection)
            } finally {
                depth--
            }
        }
        depth = 1
        try {
            outerTransaction(block)
        } finally {
            depth = 0
        }
    }

    private fun <T> outerTransaction(block: (Connection) -> T): T {
        val c = live()
        c.autoCommit = false
        return try {
            setTeam(c)
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            c.rollback()
            // A setting made inside the transaction is rolled back with it.
            teamOnConnection = null
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    /** SQL as this engine needs it: on Postgres, `? IS NULL` gets a type it can't infer itself. */
    fun sql(text: String): String =
        if (dialect == Dialect.SQLITE) text else UNTYPED_NULL_TEST.replace(text) { "CAST(? AS TEXT) IS ${it.groupValues[1]}NULL" }

    /**
     * Postgres can't hold U+0000 in text; SQLite can. On Postgres such a write is refused as a bad
     * argument (400) before it reaches the database, rather than failing as a server error.
     */
    private fun checkText(args: Array<out Any?>) {
        if (dialect == Dialect.POSTGRES && args.any { it is String && it.indexOf('\u0000') >= 0 }) {
            throw dev.wildware.feed.server.domain.invalid("Text can't contain the NUL character (U+0000).")
        }
    }

    fun <T> query(sql: String, vararg args: Any?, row: (ResultSet) -> T): List<T> = withConnection { c ->
        statements++
        val (text, bound) = TeamTables.expand(sql, args)
        checkText(bound)
        setTeam(c)
        timed(text) {
            c.prepareStatement(sql(text)).use { st ->
                st.bind(bound)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
            }
        }
    }

    /** Run one statement, logging its first words (never its values) when it takes [SLOW_WAIT_MS] or more. */
    private inline fun <T> timed(text: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ms >= SLOW_WAIT_MS) System.err.println("database: a statement took $ms ms: ${text.replace(Regex("\\s+"), " ").take(120)}")
        }
    }

    fun <T> one(sql: String, vararg args: Any?, row: (ResultSet) -> T): T? = query(sql, *args, row = row).firstOrNull()

    fun update(sql: String, vararg args: Any?): Int = withConnection { c ->
        statements++
        val (text, bound) = TeamTables.expand(sql, args)
        checkText(bound)
        setTeam(c)
        timed(text) { c.prepareStatement(sql(text)).use { st -> st.bind(bound); st.executeUpdate() } }
    }

    /** SQLite's two-argument `max(a, b)`. */
    fun greatest(a: String, b: String) = if (dialect == Dialect.SQLITE) "max($a, $b)" else "GREATEST($a, $b)"

    /** SQLite's `COLLATE NOCASE` for ordering: ASCII letters folded, everything else by byte. */
    fun asciiFold(expr: String) =
        if (dialect == Dialect.SQLITE) "$expr COLLATE NOCASE"
        else "translate($expr, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz') COLLATE \"C\""

    private var closed = false

    /** Closes once; a second close (an App and a test both holding it) does nothing. */
    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        try {
            connection.close()
        } finally {
            onClose()
        }
    }

    companion object {
        /** A connection unused this long is checked before it is used again. */
        private const val IDLE_CHECK_MS = 30_000L

        /** A wait for the connection, or a statement, this long is logged. */
        private const val SLOW_WAIT_MS = 1_000L

        const val FILE_NAME = "agent-dashboard.db"
        const val MIGRATIONS_TABLE = "schema_migrations"
        private val UNTYPED_NULL_TEST = Regex("""\?\s+IS\s+(NOT\s+)?NULL""", RegexOption.IGNORE_CASE)

        /** Postgres when [Config.databaseUrl] is set, else the SQLite file in [Config.dataDir]. */
        fun open(config: dev.wildware.feed.server.Config): Database =
            config.databaseUrl?.let { openPostgres(it) } ?: open(config.dataDir)

        /** `<dataDir>/agent-dashboard.db`, read-only and not migrated (another server may be running on it). */
        fun openReadOnly(dataDir: String): Database {
            val file = File(dataDir, FILE_NAME)
            require(file.isFile) { "no database at $file" }
            val connection = DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro")
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            return Database(connection, Dialect.SQLITE)
        }

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
            val db = Database(connection, Dialect.SQLITE)
            if (migrate) Migrations.run(db)
            return db
        }

        /**
         * Postgres at [url]: Heroku's `postgres://user:password@host:port/name` (SSL required unless
         * the host is local) or a `jdbc:postgresql:` URL. [schema] picks a schema other than `public`.
         */
        fun openPostgres(url: String, migrate: Boolean = true, schema: String? = null, onClose: () -> Unit = {}, role: String? = null): Database {
            val connect = { postgresConnection(url, schema, role) }
            val db = Database(connect(), Dialect.POSTGRES, connect, onClose)
            if (migrate) PostgresMigrations.run(db)
            // Row security switched on by a server with many teams stays on in the database; any
            // other program opening it (the admin commands, export) then says its team too.
            db.rowSecurity = TeamTables.rowSecurityForced(db)
            return db
        }

        internal fun postgresConnection(url: String, schema: String?, role: String? = null): Connection {
            val (jdbc, props) = postgresJdbc(url)
            val c = DriverManager.getConnection(jdbc, props)
            if (schema != null) c.createStatement().use { it.execute("SET search_path TO \"$schema\"") }
            if (role != null) c.createStatement().use { it.execute("SET ROLE \"$role\"") }
            return c
        }

        /**
         * The role the Postgres tests work as: not a superuser and not allowed to skip row security,
         * like Heroku's database user, so the policies of migration 058 really apply to them.
         */
        private const val TEST_ROLE = "feed_test_app"

        /** A JDBC URL and properties from Heroku's DATABASE_URL form. */
        fun postgresJdbc(url: String): Pair<String, java.util.Properties> {
            val props = java.util.Properties()
            if (url.startsWith("jdbc:")) return url to props
            val uri = java.net.URI(url.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            uri.userInfo?.split(":", limit = 2)?.let { parts ->
                props["user"] = java.net.URLDecoder.decode(parts[0], Charsets.UTF_8)
                if (parts.size > 1) props["password"] = java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
            }
            val local = uri.host in setOf("localhost", "127.0.0.1", "::1")
            props["sslmode"] = if (local) "disable" else "require"
            val port = if (uri.port > 0) uri.port else 5432
            return "jdbc:postgresql://${uri.host}:$port${uri.path}" to props
        }

        /**
         * A fresh database for tests: SQLite in memory, or, when `PG_TEST_URL` is set, a new schema on
         * that Postgres server (dropped again on close), so the whole suite can run on either engine.
         */
        fun memory(): Database {
            val pg = System.getenv("PG_TEST_URL")
            if (!pg.isNullOrBlank()) {
                val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
                postgresConnection(pg, null).use { c ->
                    c.createStatement().use {
                        it.execute(
                            "DO $$ BEGIN CREATE ROLE $TEST_ROLE NOLOGIN NOSUPERUSER NOBYPASSRLS; " +
                                "EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END $$",
                        )
                        it.execute("CREATE SCHEMA \"$schema\"")
                        it.execute("GRANT ALL ON SCHEMA \"$schema\" TO $TEST_ROLE")
                    }
                }
                // Every test on Postgres runs with row security on: the server must say its team everywhere.
                return openPostgres(pg, schema = schema, role = TEST_ROLE, onClose = {
                    postgresConnection(pg, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
                }).also { TeamTables.setRowSecurity(it, true) }
            }
            return sqliteMemory()
        }

        /** An in-memory SQLite database, migrated (up to [upTo]), whatever `PG_TEST_URL` says. */
        fun sqliteMemory(upTo: Int = Int.MAX_VALUE): Database {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            return Database(connection, Dialect.SQLITE).also { Migrations.run(it, upTo) }
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
            is String -> setString(i + 1, dev.wildware.feed.server.domain.Js.replaceLoneSurrogates(value))
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

    fun run(db: Database, upTo: Int = Int.MAX_VALUE) {
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
        for (migration in all.filter { it.version !in applied && it.version <= upTo }) apply(db, migration)
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

/**
 * Postgres's migrations (`/migrations-pg/`). The first is a baseline: SQLite's schema after its
 * migration 047, translated table by table (server/tools/sqlite_to_pg.py), recorded as version 47.
 * Every later migration ships as a pair, SQLite's and Postgres's, under one version number.
 */
object PostgresMigrations {
    @Serializable
    data class Migration(val version: Int, val name: String, val file: String, val checksum: String) {
        val sql: String by lazy {
            PostgresMigrations::class.java.getResource("/migrations-pg/$file")!!.readBytes().toString(Charsets.UTF_8)
        }
    }

    val all: List<Migration> by lazy {
        val text = PostgresMigrations::class.java.getResource("/migrations-pg/migrations.json")!!.readText()
        Json.decodeFromString<List<Migration>>(text).sortedBy { it.version }
    }

    /** Apply every migration not applied yet, up to [upTo] (tests stop early to seed older data). */
    fun run(db: Database, upTo: Int = Int.MAX_VALUE) {
        db.withConnection { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE IF NOT EXISTS ${Database.MIGRATIONS_TABLE} (version bigint PRIMARY KEY, name text NOT NULL, " +
                        "checksum text NOT NULL, applied_at bigint NOT NULL)",
                )
            }
        }
        val applied = db.query("SELECT version, checksum FROM ${Database.MIGRATIONS_TABLE}") { it.getInt(1) to it.getString(2) }.toMap()
        val known = all.associateBy { it.version }
        val unknown = applied.keys.filter { it !in known }
        check(unknown.isEmpty()) {
            "Refusing to run against a newer database: it has migrations ${unknown.sorted()} that this build does not know."
        }
        for ((version, stored) in applied) {
            check(Migrations.checksum(known.getValue(version).sql) == stored) {
                "Migrations are append-only: migration $version has changed since it was applied."
            }
        }
        for (migration in all.filter { it.version !in applied && it.version <= upTo }) {
            db.transaction { c ->
                // Row security, once switched on, stays on in the database: a migration that moves
                // data sees every team's rows (TeamTables.kt).
                c.createStatement().use { it.execute("SELECT set_config('app.team', '*', true)") }
                c.createStatement().use { st -> SqlScript.split(migration.sql).forEach { st.execute(it) } }
                c.prepareStatement("INSERT INTO ${Database.MIGRATIONS_TABLE} (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)").use {
                    it.bind(arrayOf<Any?>(migration.version, migration.name, Migrations.checksum(migration.sql), System.currentTimeMillis()))
                    it.executeUpdate()
                }
            }
        }
    }
}
