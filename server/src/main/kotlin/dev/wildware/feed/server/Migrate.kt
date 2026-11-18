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
    const val USAGE = """feed-server migrate --from <data dir> [--verify-only] [--sizes-only] [--media-only]

  Copies <data dir>/agent-dashboard.db into the Postgres database at DATABASE_URL (which must be new),
  then checks every table's row count and checksum. With MEDIA_STORE=s3 (and the S3_* settings) it
  also copies <data dir>/media into the bucket, skipping objects already there, then checks every
  original's SHA-256 (or only its size, with --sizes-only) and every derivative's size.
  --verify-only skips the copies and only checks. Stop the old server first, so nothing changes.
  --media-only copies and checks only the media (against the SQLite file; no DATABASE_URL needed),
  which can run ahead of the cut-over while the old server is still running."""

    fun run(args: List<String>, env: Map<String, String>, out: (String) -> Unit, err: (String) -> Unit): Int {
        var from: String? = null
        var verifyOnly = false
        var sizesOnly = false
        var mediaOnly = false
        val it = args.iterator()
        while (it.hasNext()) {
            when (val a = it.next()) {
                "--from" -> from = if (it.hasNext()) it.next() else null
                "--verify-only" -> verifyOnly = true
                "--sizes-only" -> sizesOnly = true
                "--media-only" -> mediaOnly = true
                "-h", "--help" -> { out(USAGE); return 0 }
                else -> { err("unknown argument: $a"); err(USAGE); return 2 }
            }
        }
        if (mediaOnly && from != null) return mediaOnly(from, env, sizesOnly, verifyOnly, out, err)
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
                    val store = bucket(env) ?: return@use 0
                    val media = dev.wildware.feed.server.media.MediaToStore(File(from, "media").toPath().toRealPath(), store, out)
                    if (!verifyOnly) {
                        out("Copying media…")
                        val c = media.copy()
                        out("Media: ${c.files} files; ${c.uploaded} sent (${c.bytesSent / 1_000_000} MB), ${c.linked} copied in the bucket, ${c.skipped} already there")
                    }
                    out(if (sizesOnly) "Checking media sizes…" else "Checking media bytes…")
                    val v = media.verify(target, hashes = !sizesOnly)
                    if (v.problems.isNotEmpty()) {
                        v.problems.take(20).forEach(err)
                        err("${v.problems.size} media problems")
                        return@use 1
                    }
                    out("Media identical: ${v.originals} originals${if (sizesOnly) " by size" else " by SHA-256"}, ${v.derivatives} derivatives by size")
                    0
                }
            }
        } catch (e: Exception) {
            err(e.message ?: e.toString())
            1
        }
    }

    private fun mediaOnly(from: String, env: Map<String, String>, sizesOnly: Boolean, verifyOnly: Boolean, out: (String) -> Unit, err: (String) -> Unit): Int = try {
        val store = bucket(env) ?: throw IllegalArgumentException("--media-only needs MEDIA_STORE=s3 and the S3_* settings")
        val media = dev.wildware.feed.server.media.MediaToStore(File(from, "media").toPath().toRealPath(), store, out)
        if (!verifyOnly) {
            out("Copying media…")
            val c = media.copy()
            out("Media: ${c.files} files; ${c.uploaded} sent (${c.bytesSent / 1_000_000} MB), ${c.linked} copied in the bucket, ${c.skipped} already there")
        }
        // Against the old database, read-only: the server may still be running on it.
        Database.openReadOnly(from).use { db ->
            val v = media.verify(db, hashes = !sizesOnly)
            if (v.problems.isEmpty()) {
                out("Media identical: ${v.originals} originals${if (sizesOnly) " by size" else " by SHA-256"}, ${v.derivatives} derivatives by size")
                0
            } else {
                v.problems.take(20).forEach(err)
                err("${v.problems.size} media problems")
                1
            }
        }
    } catch (e: Exception) {
        err(e.message ?: e.toString())
        1
    }

    /** The bucket MEDIA_STORE=s3 names, or null when media stays on disk. */
    private fun bucket(env: Map<String, String>): dev.wildware.feed.server.media.MediaStore? {
        if (env["MEDIA_STORE"] != "s3") return null
        fun need(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name is required when MEDIA_STORE is s3")
        val client = dev.wildware.feed.server.media.S3Client(
            need("S3_ENDPOINT"), need("S3_BUCKET"), need("S3_ACCESS_KEY_ID"), need("S3_SECRET_ACCESS_KEY"), env["S3_REGION"]?.takeIf { it.isNotBlank() } ?: "auto",
        )
        return dev.wildware.feed.server.media.S3MediaStore(client, java.nio.file.Files.createTempDirectory("feed-migrate"))
    }
}
