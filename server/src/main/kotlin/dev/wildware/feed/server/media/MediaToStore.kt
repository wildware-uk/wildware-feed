package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves a media folder (`<DATA_DIR>/media`) into a [MediaStore] under the same keys, then checks it
 * against the database. Safe to run again: objects already there with the right size are skipped.
 * Files that are hard links of each other (the old server's dedupe) are sent once and copied in the
 * bucket after that.
 */
class MediaToStore(private val root: Path, private val store: MediaStore, private val log: (String) -> Unit = {}) {
    data class CopyReport(val files: Long, val uploaded: Long, val linked: Long, val skipped: Long, val bytesSent: Long)
    data class VerifyReport(val originals: Long, val derivatives: Long, val problems: List<String>)

    fun copy(threads: Int = 4): CopyReport {
        val files = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        val uploaded = AtomicLong()
        val linked = AtomicLong()
        val skipped = AtomicLong()
        val sent = AtomicLong()
        val done = AtomicLong()
        // One key per inode, so hard links are copied in the bucket instead of sent again.
        val firstKey = ConcurrentHashMap<Any, String>()
        val (shared, single) = files.partition { inode(it)?.let { ino -> firstKey.putIfAbsent(ino, key(it)) != null } == true }
        val failures = ConcurrentHashMap.newKeySet<String>()
        fun run(batch: List<Path>, action: (Path, String) -> Unit) {
            val pool = Executors.newFixedThreadPool(threads)
            for (file in batch) pool.execute {
                val key = key(file)
                try {
                    if (store.size(key) == Files.size(file)) skipped.incrementAndGet() else action(file, key)
                } catch (e: Exception) {
                    failures += "$key: ${e.message}"
                }
                val n = done.incrementAndGet()
                if (n % 500 == 0L) log("  $n of ${files.size} files")
            }
            pool.shutdown()
            pool.awaitTermination(7, TimeUnit.DAYS)
        }
        run(single) { file, key ->
            store.copyIn(key, file, contentType(key))
            uploaded.incrementAndGet()
            sent.addAndGet(Files.size(file))
        }
        run(shared) { file, key ->
            val twin = firstKey.getValue(inode(file)!!)
            if (store.link(twin, key)) {
                linked.incrementAndGet()
            } else {
                store.copyIn(key, file, contentType(key))
                uploaded.incrementAndGet()
                sent.addAndGet(Files.size(file))
            }
        }
        check(failures.isEmpty()) { "${failures.size} files didn't copy, e.g. " + failures.take(5).joinToString("; ") }
        return CopyReport(files.size.toLong(), uploaded.get(), linked.get(), skipped.get(), sent.get())
    }

    /**
     * Every original the database knows has its exact bytes in the store (SHA-256 against
     * `media.sha256`, read back from the store when [hashes] is true, else its size against
     * `media.bytes`), and every derivative has its recorded size.
     */
    fun verify(db: Database, hashes: Boolean, threads: Int = 4): VerifyReport {
        val paths = MediaPaths(root.parent.toString())
        class Original(val id: String, val mime: String, val bytes: Long, val sha256: String)
        val originals = db.query("SELECT id, mime, bytes, sha256 FROM media WHERE sha256 <> '' ORDER BY seq") {
            Original(it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
        }
        val derivatives = db.query("SELECT path, bytes FROM derivatives ORDER BY seq") { it.getString(1) to it.getLong(2) }
        val problems = ConcurrentHashMap.newKeySet<String>()
        val checked = AtomicLong()
        val pool = Executors.newFixedThreadPool(threads)
        for (o in originals) pool.execute {
            val key = paths.originalKey(o.id, o.mime)
            try {
                val size = store.size(key)
                when {
                    size == null -> problems += "$key: missing"
                    size != o.bytes -> problems += "$key: $size bytes, the database says ${o.bytes}"
                    hashes -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        store.read(key, 0, size)!!.use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        val hex = digest.digest().joinToString("") { "%02x".format(it) }
                        if (hex != o.sha256) problems += "$key: SHA-256 differs from the database"
                    }
                }
            } catch (e: Exception) {
                problems += "$key: ${e.message}"
            }
            val n = checked.incrementAndGet()
            if (n % 500 == 0L) log("  checked $n of ${originals.size} originals")
        }
        for ((key, bytes) in derivatives) pool.execute {
            try {
                val size = store.size(key)
                if (size != bytes) problems += "$key: ${size ?: "missing"}, the database says $bytes bytes"
            } catch (e: Exception) {
                problems += "$key: ${e.message}"
            }
        }
        pool.shutdown()
        pool.awaitTermination(7, TimeUnit.DAYS)
        return VerifyReport(originals.size.toLong(), derivatives.size.toLong(), problems.sorted())
    }

    private fun key(file: Path) = root.relativize(file).toString().replace('\\', '/')

    private fun inode(file: Path): Any? = runCatching { Files.getAttribute(file, "unix:ino") }.getOrNull()

    private fun contentType(key: String): String = when (key.substringAfterLast('.').lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
