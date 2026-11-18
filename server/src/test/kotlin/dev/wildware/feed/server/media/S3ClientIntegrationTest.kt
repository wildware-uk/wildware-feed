package dev.wildware.feed.server.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [S3Client] against a real S3-compatible server. Only ever point this at a local throwaway MinIO:
 *
 * ```
 * docker run -d --name feed-s3 -p 127.0.0.1:19000:8333 -v <s3.json>:/etc/s3.json:ro \
 *   chrislusf/seaweedfs:3.97 server -s3 -s3.config=/etc/s3.json -dir=/data
 * (s3.json names one identity with the access key and secret below; MinIO's images are no longer
 * published, and any S3-compatible server will do)
 * S3_TEST_ENDPOINT=http://127.0.0.1:19000 S3_TEST_BUCKET=feed-test \
 *   S3_TEST_ACCESS_KEY_ID=feedtestkey S3_TEST_SECRET_ACCESS_KEY=feedtestsecret123 \
 *   ./gradlew :server:test --tests '*S3*'
 * docker rm -f feed-minio
 * ```
 *
 * The bucket is created when missing. Region is `auto` with path-style URLs, the way R2 is set up.
 * Skipped when `S3_TEST_ENDPOINT` is not set.
 */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ClientIntegrationTest {
    private val endpoint = System.getenv("S3_TEST_ENDPOINT").orEmpty()
    private val bucket = System.getenv("S3_TEST_BUCKET") ?: "feed-test"
    private val keyId = System.getenv("S3_TEST_ACCESS_KEY_ID").orEmpty()
    private val secret = System.getenv("S3_TEST_SECRET_ACCESS_KEY").orEmpty()
    private val client by lazy { S3Client(endpoint, bucket, keyId, secret, region = "auto") }
    private val run = "test-${UUID.randomUUID()}"

    @BeforeAll
    fun createBucket(): Unit = runBlocking {
        val bucketClient = S3Client(endpoint, bucket, keyId, secret, region = "auto")
        bucketClient.send("PUT", "/$bucket").use {
            val body = String(it.body.readAllBytes())
            // A rerun finds it there: AWS and MinIO say "owned by you", SeaweedFS says "already exists".
            val there = "BucketAlreadyOwnedByYou" in body || "BucketAlreadyExists" in body
            assertTrue(it.status == 200 || there, "create bucket: ${it.status}")
        }
    }

    @AfterAll
    fun cleanUp(): Unit = runBlocking {
        client.deletePrefix("$run/")
    }

    @Test
    fun `put, get, head, copy, delete`(): Unit = runBlocking {
        val key = "$run/01/01J0ABCDEFGHJKMNPQRSTVWXYZ/original.png"
        val bytes = Random(1).nextBytes(3000)
        client.put(key, bytes, "image/png")
        assertEquals(3000L, client.head(key))
        client.get(key)!!.use {
            assertEquals(200, it.status)
            assertEquals(3000L, it.contentLength)
            assertEquals("image/png", it.contentType)
            assertContentEquals(bytes, it.body.readAllBytes())
        }
        val copy = "$run/copies/original.png"
        client.copy(key, copy)
        client.get(copy)!!.use { assertContentEquals(bytes, it.body.readAllBytes()) }
        client.delete(key)
        assertNull(client.head(key))
        client.delete(key)
    }

    @Test
    fun `a file uploads from disk`(): Unit = runBlocking {
        val file = Files.createTempFile("s3-test", ".txt")
        try {
            Files.writeString(file, "hello from a file")
            client.put("$run/file.txt", file, "text/plain")
            client.get("$run/file.txt")!!.use { assertEquals("hello from a file", String(it.body.readAllBytes())) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `range reads`(): Unit = runBlocking {
        val key = "$run/range.txt"
        client.put(key, "0123456789abcdef".toByteArray(), "text/plain")
        client.get(key, 5L..9L)!!.use {
            assertEquals(206, it.status)
            assertEquals(5L, it.contentLength)
            assertEquals("bytes 5-9/16", it.contentRange)
            assertEquals("56789", String(it.body.readAllBytes()))
        }
        client.get(key, 10L..Long.MAX_VALUE)!!.use { assertEquals("abcdef", String(it.body.readAllBytes())) }
        val error = assertFailsWith<S3Exception> { client.get(key, 100L..200L) }
        assertEquals(416, error.status)
        assertEquals("InvalidRange", error.code)
    }

    @Test
    fun `keys with spaces, unicode and reserved characters`(): Unit = runBlocking {
        val key = "$run/odd names/ünïcödé 文字 😀/a+b=c&d%e?f#g~h.txt"
        client.put(key, "odd".toByteArray(), "text/plain")
        assertEquals(3L, client.head(key))
        val copy = "$run/odd names/copy of ü.txt"
        client.copy(key, copy)
        client.get(copy)!!.use { assertEquals("odd", String(it.body.readAllBytes())) }
        val listed = client.list("$run/odd names/").map { it.key }.toSet()
        assertEquals(setOf(key, copy), listed)
    }

    @Test
    fun `list walks every page and deletePrefix empties the prefix`(): Unit = runBlocking {
        val prefix = "$run/many/"
        val keys = (1..5).map { "${prefix}item-$it" }
        keys.forEachIndexed { i, key -> client.put(key, ByteArray(i + 1), "application/octet-stream") }
        client.put("$run/other", ByteArray(1), "application/octet-stream")
        val listed = client.list(prefix, pageSize = 2)
        assertEquals(keys, listed.map { it.key })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), listed.map { it.size })
        assertEquals(5, client.deletePrefix(prefix))
        assertEquals(emptyList(), client.list(prefix))
        assertNotNull(client.head("$run/other"))
    }

    @Test
    fun `a missing key is not found`(): Unit = runBlocking {
        assertNull(client.get("$run/missing"))
        assertNull(client.head("$run/missing"))
        // AWS, R2 and MinIO answer 404 NoSuchKey; SeaweedFS answers 400. Either way it fails, which
        // is all a dedupe copy needs (it falls back to sending the bytes).
        val error = assertFailsWith<S3Exception> { client.copy("$run/missing", "$run/copied") }
        assertTrue(error.status == 404 || error.status == 400, "copy from a missing key: ${error.status} ${error.code}")
    }

    @Test
    fun `a wrong secret is a signature error that names neither credential`(): Unit = runBlocking {
        val wrongSecret = "not-the-real-secret-value"
        val bad = S3Client(endpoint, bucket, keyId, wrongSecret, region = "auto")
        val error = assertFailsWith<S3Exception> { bad.put("$run/bad", "x".toByteArray(), "text/plain") }
        assertEquals(403, error.status)
        assertEquals("SignatureDoesNotMatch", error.code)
        val text = error.toString() + error.stackTraceToString()
        assertFalse(wrongSecret in text)
        assertFalse(secret in text)
        assertFalse(keyId in text)
        assertNull(client.head("$run/bad"))
    }

    @Test
    fun `a 50 MB file streams both ways in bounded memory`(): Unit = runBlocking {
        val size = 50L * 1024 * 1024
        val file = Files.createTempFile("s3-big", ".bin")
        try {
            val sent = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(file).use { out ->
                val random = Random(7)
                val chunk = ByteArray(1024 * 1024)
                repeat((size / chunk.size).toInt()) {
                    random.nextBytes(chunk)
                    sent.update(chunk)
                    out.write(chunk)
                }
            }
            val key = "$run/big.bin"
            val upload = HeapWatch().measure { client.put(key, file, "application/octet-stream") }
            assertEquals(size, client.head(key))
            val received = MessageDigest.getInstance("SHA-256")
            val download = HeapWatch().measure {
                client.get(key)!!.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = it.body.read(buffer)
                        if (n < 0) break
                        received.update(buffer, 0, n)
                    }
                }
            }
            assertContentEquals(sent.digest(), received.digest())
            val limit = 20L * 1024 * 1024
            assertTrue(upload < limit, "upload grew the live heap by ${upload / 1024 / 1024} MB")
            assertTrue(download < limit, "download grew the live heap by ${download / 1024 / 1024} MB")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** Samples the live heap (after a GC) while a block runs; answers the largest rise over the start. */
    private class HeapWatch {
        private fun live(): Long {
            System.gc()
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }

        suspend fun measure(block: suspend () -> Unit): Long {
            val start = live()
            val peak = AtomicLong(start)
            val running = AtomicBoolean(true)
            val sampler = Thread {
                while (running.get()) {
                    peak.accumulateAndGet(live(), ::maxOf)
                    Thread.sleep(20)
                }
            }.apply { isDaemon = true; start() }
            try {
                block()
            } finally {
                running.set(false)
                sampler.join()
            }
            return peak.get() - start
        }
    }
}
