package dev.wildware.feed.server

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.SqliteToPostgres
import java.io.File

/**
 * `feed-server migrate --from <data dir> [--verify-only]`: moves a self-hosted server's SQLite
 * database into the Postgres at DATABASE_URL, then checks the copy table by table. Not part of the
 * TS server's CLI, so it lives beside it.
 */
object Migrate {
    const val USAGE = """feed-server migrate --from <data dir> [--verify-only]

  Copies <data dir>/agent-dashboard.db into the Postgres database at DATABASE_URL (which must be new),
  then checks every table's row count and checksum. --verify-only skips the copy and only checks.
  Stop the old server first, so nothing changes while it copies."""

    fun run(args: List<String>, env: Map<String, String>, out: (String) -> Unit, err: (String) -> Unit): Int {
        var from: String? = null
        var verifyOnly = false
        val it = args.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "--from" -> from = if (it.hasNext()) it.next() else null
                "--verify-only" -> verifyOnly = true
                "-h", "--help" -> { out(USAGE); return 0 }
                else -> { err("unknown argument: $a"); err(USAGE); return 2 }
            }
        }
        val url = env["DATABASE_URL"]?.takeIf { it.isNotBlank() }
        if (from == null || url == null) {
            err(if (from == null) "--from <data dir> is needed" else "DATABASE_URL is not set")
            err(USAGE)
            return 2
        }
        val tool = SqliteToPostgres(out)
        return try {
            tool.openSource(File(from, Database.FILE_NAME)).use { source ->
                Database.openPostgres(url).use { target ->
                    if (!verifyOnly) {
                        out("Copying…")
                        val copied = tool.copy(source, target)
                        val nuls = copied.sumOf { it.nulsReplaced }
                        out("Copied ${copied.sumOf { it.rows }} rows in ${copied.size} tables" +
                            if (nuls > 0) "; $nuls NUL characters became ${SqliteToPostgres.NUL_STAND_IN}" else "; no NUL characters")
                    }
                    out("Checking…")
                    val checked = tool.verify(source, target)
                    out("Identical: ${checked.sumOf { it.rows }} rows in ${checked.size} tables")
                }
            }
            0
        } catch (e: Exception) {
            err(e.message ?: e.toString())
            1
        }
    }
}
