package dev.wildware.feed.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/** A non-2xx answer from the store, with the S3 error code and message from its XML body. Never carries credentials. */
class S3Exception(val status: Int, val code: String, message: String, val requestId: String? = null) : IOException(message)

/**
 * A small S3 client for Cloudflare R2, AWS S3, Bucketeer and MinIO: path-style URLs (`<endpoint>/<bucket>/<key>`),
 * Signature Version 4, every payload hashed and signed (no UNSIGNED-PAYLOAD). Files stream from disk; nothing
 * here holds a whole body in memory except [put] with a byte array.
 */
class S3Client(
    endpoint: String,
    private val bucket: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String = "auto",
    private val clock: () -> Instant = Instant::now,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    /** A stored object, from [list]. */
    data class Entry(val key: String, val size: Long)

    /** An open object body from [get]; close it (or read it to the end) to free the connection. */
    class Download(
        val status: Int,
        val contentLength: Long?,
        val contentRange: String?,
        val contentType: String?,
        val etag: String?,
        val body: InputStream,
    ) : Closeable {
        override fun close() = body.close()
    }

    private val base: String = endpoint.trimEnd('/').also {
        val uri = URI.create(it)
        require(uri.scheme == "http" || uri.scheme == "https") { "S3 endpoint must be http or https" }
        require(uri.host != null) { "S3 endpoint needs a host" }
    }
    private val endpointUri = URI.create(base)

    init {
        require(bucket.isNotEmpty() && '/' !in bucket) { "S3 bucket name is invalid" }
    }

    /** Uploads [file] in one PUT. The file is read twice (once to hash, once to send), never held in memory. */
    suspend fun put(key: String, file: Path, contentType: String) {
        val size = withContext(Dispatchers.IO) { Files.size(file) }
        val hash = withContext(Dispatchers.IO) { Files.newInputStream(file).use { sha256Hex(it) } }
        send(
            "PUT", objectPath(key), payloadHash = hash, body = HttpRequest.BodyPublishers.ofFile(file),
            headers = mapOf("content-type" to contentType), timeout = transferTimeout(size),
        ).use { it.expectOk("PUT", key) }
    }

    /** Uploads [bytes] in one PUT. */
    suspend fun put(key: String, bytes: ByteArray, contentType: String) {
        send(
            "PUT", objectPath(key), payloadHash = sha256Hex(bytes), body = HttpRequest.BodyPublishers.ofByteArray(bytes),
            headers = mapOf("content-type" to contentType), timeout = transferTimeout(bytes.size.toLong()),
        ).use { it.expectOk("PUT", key) }
    }

    /**
     * Opens [key] for reading, the whole object or the inclusive byte [range] (`5L..9L` is `bytes=5-9`;
     * `5L..Long.MAX_VALUE` is `bytes=5-`). Null when the key does not exist. The request timeout covers the
     * response headers only; the caller reads the body at its own pace.
     */
    suspend fun get(key: String, range: LongRange? = null): Download? {
        val headers = buildMap {
            if (range != null) {
                require(range.first >= 0 && range.last >= range.first) { "S3 range is empty or negative" }
                put("range", if (range.last == Long.MAX_VALUE) "bytes=${range.first}-" else "bytes=${range.first}-${range.last}")
            }
        }
        val response = send("GET", objectPath(key), headers = headers, timeout = REQUEST_TIMEOUT)
        if (response.status == 404) {
            response.close()
            return null
        }
        response.expectOk("GET", key)
        return Download(
            status = response.status,
            contentLength = response.header("content-length")?.toLongOrNull(),
            contentRange = response.header("content-range"),
            contentType = response.header("content-type"),
            etag = response.header("etag"),
            body = response.body,
        )
    }

    /** The object's size in bytes, or null when the key does not exist. */
    suspend fun head(key: String): Long? = send("HEAD", objectPath(key)).use {
        if (it.status == 404) return null
        it.expectOk("HEAD", key)
        it.header("content-length")?.toLongOrNull() ?: throw S3Exception(it.status, "MissingContentLength", "S3 HEAD $key: no content-length")
    }

    /** Copies [fromKey] to [toKey] inside the bucket, on the server. */
    suspend fun copy(fromKey: String, toKey: String) {
        val source = "/$bucket/${encodeKey(fromKey)}"
        send("PUT", objectPath(toKey), headers = mapOf("x-amz-copy-source" to source), timeout = COPY_TIMEOUT).use {
            it.expectOk("COPY", toKey)
            // CopyObject can answer 200 and still fail: the error then arrives as the body.
            val doc = parseXml(it.readBody())
            if (doc?.tagName == "Error") throw it.error("COPY", toKey, doc)
        }
    }

    /** Deletes [key]. Deleting a missing key succeeds. */
    suspend fun delete(key: String) {
        send("DELETE", objectPath(key)).use { it.expectOk("DELETE", key) }
    }

    /** Every object whose key starts with [prefix], following ListObjectsV2 pages to the end. */
    suspend fun list(prefix: String): List<Entry> = list(prefix, pageSize = null)

    /** [list] with a page size, so tests can walk several pages without a thousand objects. */
    internal suspend fun list(prefix: String, pageSize: Int?): List<Entry> {
        val entries = mutableListOf<Entry>()
        var token: String? = null
        do {
            val query = buildList {
                add("list-type" to "2")
                add("prefix" to prefix)
                if (token != null) add("continuation-token" to token)
                if (pageSize != null) add("max-keys" to pageSize.toString())
            }
            val doc = send("GET", "/$bucket", query = query).use {
                it.expectOk("LIST", prefix)
                parseXml(it.readBody()) ?: throw S3Exception(it.status, "MalformedResponse", "S3 LIST $prefix: unreadable answer")
            }
            val contents = doc.getElementsByTagName("Contents")
            for (i in 0 until contents.length) {
                val item = contents.item(i) as Element
                entries += Entry(item.child("Key") ?: continue, item.child("Size")?.toLongOrNull() ?: 0L)
            }
            val truncated = doc.child("IsTruncated") == "true"
            token = if (truncated) doc.child("NextContinuationToken") else null
            if (truncated && token == null) throw S3Exception(200, "MalformedResponse", "S3 LIST $prefix: truncated with no continuation token")
        } while (token != null)
        return entries
    }

    /** Deletes every object under [prefix] (which must not be empty) and answers how many there were. */
    suspend fun deletePrefix(prefix: String): Int {
        require(prefix.isNotEmpty()) { "S3 deletePrefix needs a prefix; an empty one would empty the bucket" }
        val entries = list(prefix)
        for (entry in entries) delete(entry.key)
        return entries.size
    }

    override fun toString() = "S3Client($base/$bucket)"

    internal class Response(val status: Int, val headers: java.net.http.HttpHeaders, val body: InputStream) : Closeable {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
        fun readBody(): ByteArray = body.use { it.readNBytes(MAX_SMALL_BODY) }
        override fun close() = body.close()
    }

    private fun objectPath(key: String) = "/$bucket/${encodeKey(key)}"

    /** Builds, signs and sends one request. [path] is already encoded. */
    internal suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        payloadHash: String = EMPTY_SHA256,
        body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(),
        timeout: Duration = REQUEST_TIMEOUT,
    ): Response {
        val canonicalPath = endpointUri.rawPath.orEmpty() + path
        val queryString = canonicalQuery(query)
        val signed = SigV4.sign(
            method = method,
            canonicalUri = canonicalPath,
            query = query,
            headers = headers + ("host" to hostHeader(endpointUri)),
            payloadHash = payloadHash,
            time = clock(),
            region = region,
            service = "s3",
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        )
        val uri = URI.create(
            "${endpointUri.scheme}://${endpointUri.rawAuthority}$canonicalPath" + if (queryString.isEmpty()) "" else "?$queryString",
        )
        val request = HttpRequest.newBuilder(uri).timeout(timeout).method(method, body).apply {
            for ((name, value) in signed.headers) if (name != "host") header(name, value)
            header("authorization", signed.authorization)
        }.build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        return Response(response.statusCode(), response.headers(), response.body())
    }

    private fun Response.expectOk(op: String, key: String) {
        if (status in 200..299) return
        val bytes = try { readBody() } catch (_: IOException) { ByteArray(0) }
        throw error(op, key, parseXml(bytes))
    }

    private fun Response.error(op: String, key: String, doc: Element?): S3Exception {
        val code = doc?.child("Code") ?: when (status) {
            404 -> "NotFound"
            403 -> "Forbidden"
            else -> "Http$status"
        }
        val detail = doc?.child("Message")?.let(::redact)?.take(500) ?: "no detail"
        val requestId = doc?.child("RequestId") ?: header("x-amz-request-id")
        return S3Exception(status, code, "S3 $op $key: $status $code: $detail", requestId)
    }

    private fun redact(text: String): String =
        listOf(secretAccessKey, accessKeyId).filter { it.isNotEmpty() }.fold(text) { acc, secret -> acc.replace(secret, "[redacted]") }

    companion object {
        val EMPTY_SHA256 = sha256Hex(ByteArray(0))
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        private val COPY_TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val MAX_SMALL_BODY = 16 * 1024 * 1024
        private const val MAX_KEY_BYTES = 1024

        /** 60 s, plus one second per MB: a 200 MB body gets a little over four minutes. */
        internal fun transferTimeout(bytes: Long): Duration = REQUEST_TIMEOUT.plusSeconds(bytes / 1_000_000)

        /** A key as a URL path: each `/`-separated segment encoded per SigV4, the slashes kept. */
        fun encodeKey(key: String): String {
            require(key.isNotEmpty()) { "S3 key is empty" }
            require(key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES) { "S3 key is longer than $MAX_KEY_BYTES bytes" }
            val segments = key.split('/')
            require(segments.none { it == "." || it == ".." }) { "S3 key has a . or .. segment" }
            return segments.joinToString("/") { SigV4.uriEncode(it) }
        }

        internal fun canonicalQuery(query: List<Pair<String, String>>): String =
            query.map { (k, v) -> SigV4.uriEncode(k) to SigV4.uriEncode(v) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { (k, v) -> "$k=$v" }

        /** The Host header the JDK client sends: the port only when it isn't the scheme's default. */
        internal fun hostHeader(uri: URI): String {
            val port = uri.port
            val defaultPort = port == -1 || (uri.scheme == "https" && port == 443) || (uri.scheme == "http" && port == 80)
            return if (defaultPort) uri.host else "${uri.host}:$port"
        }

        private fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().toHex()
        }

        internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** The root element of an XML body, or null when it is empty or not XML. Doctypes are refused. */
        private fun parseXml(bytes: ByteArray): Element? {
            if (bytes.isEmpty()) return null
            return try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isExpandEntityReferences = false
                    isXIncludeAware = false
                }
                factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }.parse(ByteArrayInputStream(bytes)).documentElement
            } catch (_: Exception) {
                null
            }
        }

        private fun Element.child(name: String): String? {
            var node = firstChild
            while (node != null) {
                if (node is Element && node.tagName == name) return node.textContent
                node = node.nextSibling
            }
            return null
        }
    }
}

/** AWS Signature Version 4, header form. Pure: the same inputs always give the same signature. */
internal object SigV4 {
    class Signed(
        val canonicalRequest: String,
        val stringToSign: String,
        val signature: String,
        val authorization: String,
        /** Every header that was signed, lower-case names, including the added `x-amz-date` and `x-amz-content-sha256`. */
        val headers: Map<String, String>,
    )

    private val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * Signs a request. [canonicalUri] is the already-encoded path; [headers] must include `host`.
     * `x-amz-date` is added from [time] and, when [addContentHash] is true, `x-amz-content-sha256` from [payloadHash].
     */
    fun sign(
        method: String,
        canonicalUri: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
        time: Instant,
        region: String,
        service: String,
        accessKeyId: String,
        secretAccessKey: String,
        addContentHash: Boolean = true,
    ): Signed {
        val dateTime = amzDate.format(time)
        val date = dateTime.substring(0, 8)
        val all = buildMap {
            for ((k, v) in headers) put(k.lowercase(), v)
            put("x-amz-date", dateTime)
            if (addContentHash) put("x-amz-content-sha256", payloadHash)
        }.toSortedMap()
        val canonicalHeaders = all.entries.joinToString("") { (k, v) -> "$k:${v.trim().replace(Regex(" +"), " ")}\n" }
        val signedHeaders = all.keys.joinToString(";")
        val canonicalRequest = listOf(
            method,
            canonicalUri.ifEmpty { "/" },
            S3Client.canonicalQuery(query),
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", dateTime, scope, S3Client.sha256Hex(canonicalRequest.toByteArray())).joinToString("\n")
        var key = hmac("AWS4$secretAccessKey".toByteArray(), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return Signed(canonicalRequest, stringToSign, signature, authorization, all)
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /** SigV4 URI encoding: unreserved characters as they are, every other UTF-8 byte as `%XX`. */
    fun uriEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (b >= 0 && c in UNRESERVED) append(c) else append("%%%02X".format(b.toInt() and 0xff))
        }
    }
}
