package dev.wildware.feed.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID

/**
 * Where media bytes live, by key: `<id[0:2]>/<id>/<name>`, the same relative path on disk and in a
 * bucket. Work that needs a real file (ffmpeg, image readers) asks for one with [local].
 */
interface MediaStore {
    /** The object's size, or null when there is none. */
    fun size(key: String): Long?

    /** Takes [file] into the store under [key]; the file is gone afterwards. */
    fun put(key: String, file: Path, contentType: String)

    /** Stores a copy of [file] under [key]; the file stays where it is. */
    fun copyIn(key: String, file: Path, contentType: String)

    /** Makes [to] hold the same bytes as [from] without sending them again; false when it can't. */
    fun link(from: String, to: String): Boolean

    /** [length] bytes from [start], or null when there is no such object. Close it. */
    fun read(key: String, start: Long, length: Long): InputStream?

    /** Runs [block] on a local file with the object's bytes, or answers null when there is none. */
    fun <T> local(key: String, block: (Path) -> T): T?

    fun writeText(key: String, text: String)

    fun delete(key: String)

    /** Removes every object under [prefix] (a media's folder, `ab/<id>/`). */
    fun deletePrefix(prefix: String)

    /** Every object under [prefix] with its size. */
    fun list(prefix: String): List<Pair<String, Long>>
}

/** Today's layout: files under `<DATA_DIR>/media`, dedupe by hard link. */
class LocalMediaStore(val root: Path) : MediaStore {
    fun file(key: String): Path? = root.resolve(key).normalize().takeIf { it.startsWith(root) && it != root }

    override fun size(key: String): Long? = file(key)?.takeIf { Files.isRegularFile(it) }?.let(Files::size)

    override fun put(key: String, file: Path, contentType: String) {
        val target = file(key) ?: throw IllegalArgumentException("bad media key")
        Files.createDirectories(target.parent)
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun copyIn(key: String, file: Path, contentType: String) {
        val target = file(key) ?: throw IllegalArgumentException("bad media key")
        Files.createDirectories(target.parent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun link(from: String, to: String): Boolean {
        val source = file(from) ?: return false
        val target = file(to) ?: return false
        return runCatching {
            Files.createDirectories(target.parent)
            Files.createLink(target, source)
        }.isSuccess
    }

    override fun read(key: String, start: Long, length: Long): InputStream? {
        val path = file(key)?.takeIf { Files.isRegularFile(it) } ?: return null
        val channel = Files.newByteChannel(path).position(start)
        return Bounded(java.nio.channels.Channels.newInputStream(channel), length)
    }

    override fun <T> local(key: String, block: (Path) -> T): T? = file(key)?.takeIf { Files.isRegularFile(it) }?.let(block)

    override fun writeText(key: String, text: String) {
        val target = file(key) ?: return
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    override fun delete(key: String) {
        file(key)?.let { Files.deleteIfExists(it) }
    }

    override fun deletePrefix(prefix: String) {
        file(prefix.trimEnd('/'))?.toFile()?.deleteRecursively()
    }

    override fun list(prefix: String): List<Pair<String, Long>> {
        val start = file(prefix.trimEnd('/')) ?: root
        if (!Files.isDirectory(start)) return emptyList()
        return Files.walk(start).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') to Files.size(it) }
                .toList()
        }
    }
}

/**
 * An S3-compatible bucket (Cloudflare R2, AWS S3, Bucketeer, MinIO). Private: everything is served
 * through the server. Objects never change once written, so sizes are remembered.
 */
class S3MediaStore(private val client: S3Client, private val workDir: Path) : MediaStore {
    private val sizes: MutableMap<String, Long> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 20_000
        },
    )

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    override fun size(key: String): Long? = sizes[key] ?: io { client.head(key) }?.also { sizes[key] = it }

    override fun put(key: String, file: Path, contentType: String) {
        val bytes = Files.size(file)
        io { client.put(key, file, contentType) }
        sizes[key] = bytes
        Files.deleteIfExists(file)
    }

    override fun copyIn(key: String, file: Path, contentType: String) {
        val bytes = Files.size(file)
        io { client.put(key, file, contentType) }
        sizes[key] = bytes
    }

    override fun link(from: String, to: String): Boolean = runCatching { io { client.copy(from, to) } }.isSuccess

    override fun read(key: String, start: Long, length: Long): InputStream? {
        if (length <= 0) return InputStream.nullInputStream()
        val download = io { client.get(key, start..(start + length - 1)) } ?: return null
        return object : FilterInputStream(download.body) {
            override fun close() = download.close()
        }
    }

    override fun <T> local(key: String, block: (Path) -> T): T? {
        val download = io { client.get(key) } ?: return null
        Files.createDirectories(workDir)
        val temp = workDir.resolve("${UUID.randomUUID()}-${key.substringAfterLast('/')}")
        try {
            download.use { Files.copy(it.body, temp, StandardCopyOption.REPLACE_EXISTING) }
            return block(temp)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun writeText(key: String, text: String) = io { client.put(key, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8") }

    override fun delete(key: String) {
        sizes.remove(key)
        io { client.delete(key) }
    }

    override fun deletePrefix(prefix: String) {
        synchronized(sizes) { sizes.keys.removeIf { it.startsWith(prefix) } }
        io { client.deletePrefix(prefix) }
    }

    override fun list(prefix: String): List<Pair<String, Long>> = io { client.list(prefix) }.map { it.key to it.size }
}

/** At most [left] bytes of [inner]. */
private class Bounded(inner: InputStream, private var left: Long) : FilterInputStream(inner) {
    override fun read(): Int {
        if (left <= 0) return -1
        return super.read().also { if (it >= 0) left-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
}
