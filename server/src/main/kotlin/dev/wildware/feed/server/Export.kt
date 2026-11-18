package dev.wildware.feed.server

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.PostgresToSqlite
import dev.wildware.feed.server.media.LocalMediaStore
import dev.wildware.feed.server.media.MediaToStore
import dev.wildware.feed.server.media.StoreToFolder
import java.io.File

/**
 * `feed-server export --to <new data dir> [--sizes-only]`: the way back from Heroku. Copies the
 * Postgres database at DATABASE_URL into a new SQLite file, and (with MEDIA_STORE=s3) the bucket into
 * a media folder, then checks both, so the TS server or a self-hosted one can run on the folder.
 */
object Export {
    const val USAGE = """feed-server export --to <new data dir> [--sizes-only]

  Copies the Postgres database at DATABASE_URL into <new data dir>/agent-dashboard.db and checks every
  table's row count and checksum. With MEDIA_STORE=s3 (and the S3_* settings) it also copies the
  bucket into <new data dir>/media and checks every original's SHA-256 (or size, with --sizes-only)
  and every derivative's size. Stop the server first, so nothing changes while it copies."""

    fun run(args: List<String>, env: Map<String, String>, out: (String) -> Unit, err: (String) -> Unit): Int {
        var to: String? = null
        var sizesOnly = false
        val it = args.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "--to" -> to = if (it.hasNext()) it.next() else null
                "--sizes-only" -> sizesOnly = true
                "-h", "--help" -> { out(USAGE); return 0 }
                else -> { err("unknown argument: $a"); err(USAGE); return 2 }
            }
        }
        val url = env["DATABASE_URL"]?.takeIf { it.isNotBlank() }
        if (to == null || url == null) {
            err(if (to == null) "--to <new data dir> is needed" else "DATABASE_URL is not set")
            err(USAGE)
            return 2
        }
        return try {
            Database.openPostgres(url, migrate = false).use { source ->
                out("Copying…")
                val checked = PostgresToSqlite(out).export(source, to)
                out("Identical: ${checked.sumOf { it.rows }} rows in ${checked.size} tables")
            }
            val store = Migrate.bucket(env) ?: return 0
            val root = File(to, "media").toPath().toAbsolutePath()
            out("Copying media…")
            val c = StoreToFolder(store, root, out).copy()
            out("Media: ${c.objects} objects; ${c.written} written (${c.bytes / 1_000_000} MB), ${c.skipped} already there")
            Database.openReadOnly(to).use { db ->
                val v = MediaToStore(root, LocalMediaStore(root)).verify(db, hashes = !sizesOnly)
                if (v.problems.isNotEmpty()) {
                    v.problems.take(20).forEach(err)
                    err("${v.problems.size} media problems")
                    return 1
                }
                out("Media identical: ${v.originals} originals${if (sizesOnly) " by size" else " by SHA-256"}, ${v.derivatives} derivatives by size")
            }
            0
        } catch (e: Exception) {
            err(e.message ?: e.toString())
            1
        }
    }
}
