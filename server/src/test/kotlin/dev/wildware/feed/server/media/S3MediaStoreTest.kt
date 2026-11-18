package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [S3MediaStore] and [MediaToStore] against a local throwaway MinIO (see S3ClientIntegrationTest for setup). */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
class S3MediaStoreTest {
    private val prefix = "media-test-${UUID.randomUUID()}"
    private val client = S3Client(
        System.getenv("S3_TEST_ENDPOINT"), System.getenv("S3_TEST_BUCKET"),
        System.getenv("S3_TEST_ACCESS_KEY_ID"), System.getenv("S3_TEST_SECRET_ACCESS_KEY"), "auto",
    )
    private val work = Files.createTempDirectory("s3-store")
    private val store = S3MediaStore(client, work.resolve("fetch"))

    private fun temp(text: String): Path = Files.createTempFile(work, "f", ".bin").also { Files.writeString(it, text) }

    @Test
    fun `put, link, ranged read, local file, text and delete behave as the disk store does`() {
        val a = "$prefix/AB/one/original.png"
        val file = temp("0123456789")
        store.put(a, file, "image/png")
        assertTrue(!Files.exists(file), "put takes the file")
        assertEquals(10L, store.size(a))
        assertEquals("34567", store.read(a, 3, 5)!!.use { String(it.readAllBytes()) })
        assertTrue(store.link(a, "$prefix/AB/two/original.png"))
        assertEquals(10L, store.size("$prefix/AB/two/original.png"))
        assertEquals("0123456789", store.local(a) { Files.readString(it) })
        assertNull(store.local("$prefix/missing") { it })
        assertNull(store.read("$prefix/missing", 0, 1))
        store.writeText("$prefix/AB/one/error.txt", "bad\n")
        assertEquals(listOf("$prefix/AB/one/error.txt", "$prefix/AB/one/original.png"), store.list("$prefix/AB/one/").map { it.first }.sorted())
        store.deletePrefix("$prefix/AB/one/")
        assertNull(store.size(a))
        assertEquals(10L, store.size("$prefix/AB/two/original.png"))
        store.deletePrefix("$prefix/")
    }

    @Test
    fun `a media folder moves into the bucket once, hard links become bucket copies, and the check catches a bad object`() {
        val data = Files.createTempDirectory("media-move")
        val root = data.resolve("media")
        val id1 = "01J0AAAAAAAAAAAAAAAAAAAAAA"
        val id2 = "01J0BBBBBBBBBBBBBBBBBBBBBB"
        val bytes = "png bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        Files.createDirectories(root.resolve("01/$id1"))
        Files.createDirectories(root.resolve("01/$id2"))
        Files.write(root.resolve("01/$id1/original.png"), bytes)
        Files.createLink(root.resolve("01/$id2/original.png"), root.resolve("01/$id1/original.png"))
        Files.writeString(root.resolve("01/$id1/thumb-640.webp"), "thumb")
        val bucket = PrefixedStore(store, "$prefix/")
        Database.sqliteMemory().use { db ->
            db.update("INSERT INTO media (id, author, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'owner', 'image', 'image/png', ?, ?, 'ready', 1)", id1, bytes.size, sha)
            db.update("INSERT INTO media (id, author, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'owner', 'image', 'image/png', ?, ?, 'ready', 2)", id2, bytes.size, sha)
            db.update("INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES ('D1', ?, 'thumb', ?, 5, 640, 1)", id1, "01/$id1/thumb-640.webp")
            val move = MediaToStore(root, bucket)
            val first = move.copy()
            assertEquals(MediaToStore.CopyReport(3, 2, 1, 0, bytes.size + 5L), first)
            assertEquals(3, move.copy().skipped)
            assertEquals(emptyList(), move.verify(db, hashes = true).problems)

            bucket.put("01/$id2/original.png", Files.write(Files.createTempFile(work, "x", ""), "PNG BYTES".toByteArray()), "image/png")
            assertEquals(listOf("01/$id2/original.png: SHA-256 differs from the database"), move.verify(db, hashes = true).problems)
        }
        store.deletePrefix("$prefix/")
    }
}

/** Keeps a test's objects under one prefix. */
private class PrefixedStore(private val inner: MediaStore, private val p: String) : MediaStore {
    override fun size(key: String) = inner.size(p + key)
    override fun put(key: String, file: Path, contentType: String) = inner.put(p + key, file, contentType)
    override fun copyIn(key: String, file: Path, contentType: String) = inner.copyIn(p + key, file, contentType)
    override fun link(from: String, to: String) = inner.link(p + from, p + to)
    override fun read(key: String, start: Long, length: Long) = inner.read(p + key, start, length)
    override fun <T> local(key: String, block: (Path) -> T) = inner.local(p + key, block)
    override fun writeText(key: String, text: String) = inner.writeText(p + key, text)
    override fun delete(key: String) = inner.delete(p + key)
    override fun deletePrefix(prefix: String) = inner.deletePrefix(p + prefix)
    override fun list(prefix: String) = inner.list(p + prefix).map { it.first.removePrefix(p) to it.second }
}
