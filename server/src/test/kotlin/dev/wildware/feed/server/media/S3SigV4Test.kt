package dev.wildware.feed.server.media

import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Published AWS Signature Version 4 examples, reproduced offline: the S3 header-auth examples from the
 * S3 API reference ("Signature Calculations for the Authorization Header") and cases from the AWS SigV4 test suite.
 */
class S3SigV4Test {
    private val s3Time = Instant.parse("2013-05-24T00:00:00Z")
    private val s3Key = "AKIAIOSFODNN7EXAMPLE"
    private val s3Secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val s3Host = "examplebucket.s3.amazonaws.com"

    private fun s3(method: String, uri: String, query: List<Pair<String, String>>, headers: Map<String, String>, payloadHash: String) =
        SigV4.sign(method, uri, query, headers + ("host" to s3Host), payloadHash, s3Time, "us-east-1", "s3", s3Key, s3Secret)

    @Test
    fun `S3 example - GET object with a range`() {
        val signed = s3("GET", "/test.txt", emptyList(), mapOf("Range" to "bytes=0-9"), S3Client.EMPTY_SHA256)
        assertEquals(
            """
            GET
            /test.txt

            host:examplebucket.s3.amazonaws.com
            range:bytes=0-9
            x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            x-amz-date:20130524T000000Z

            host;range;x-amz-content-sha256;x-amz-date
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            """.trimIndent(),
            signed.canonicalRequest,
        )
        assertEquals(
            """
            AWS4-HMAC-SHA256
            20130524T000000Z
            20130524/us-east-1/s3/aws4_request
            7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972
            """.trimIndent(),
            signed.stringToSign,
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signed.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.authorization,
        )
    }

    @Test
    fun `S3 example - PUT object with a signed payload and an encoded key`() {
        val body = "Welcome to Amazon S3.".toByteArray()
        val hash = S3Client.sha256Hex(body)
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val signed = s3(
            "PUT", "/" + S3Client.encodeKey("test\$file.text"), emptyList(),
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"), hash,
        )
        assertEquals(
            """
            PUT
            /test%24file.text

            date:Fri, 24 May 2013 00:00:00 GMT
            host:examplebucket.s3.amazonaws.com
            x-amz-content-sha256:44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072
            x-amz-date:20130524T000000Z
            x-amz-storage-class:REDUCED_REDUNDANCY

            date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class
            44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072
            """.trimIndent(),
            signed.canonicalRequest,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n20130524T000000Z\n20130524/us-east-1/s3/aws4_request\n" +
                "9e0e90d9c76de8fa5b200d8c849cd5b8dc7a3be3951ddb7f6a76b4158342019d",
            signed.stringToSign,
        )
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signed.signature)
    }

    @Test
    fun `S3 example - GET bucket lifecycle, a query key with no value`() {
        val signed = s3("GET", "/", listOf("lifecycle" to ""), emptyMap(), S3Client.EMPTY_SHA256)
        assertEquals("GET\n/\nlifecycle=\n", signed.canonicalRequest.substringBefore("host:"))
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signed.signature)
    }

    @Test
    fun `S3 example - list objects, query sorted`() {
        val signed = s3("GET", "/", listOf("prefix" to "J", "max-keys" to "2"), emptyMap(), S3Client.EMPTY_SHA256)
        assertEquals("GET\n/\nmax-keys=2&prefix=J\n", signed.canonicalRequest.substringBefore("host:"))
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signed.signature)
    }

    private val suiteTime = Instant.parse("2015-08-30T12:36:00Z")

    private fun suite(method: String, uri: String, query: List<Pair<String, String>> = emptyList()) = SigV4.sign(
        method, uri, query, mapOf("Host" to "example.amazonaws.com"), S3Client.EMPTY_SHA256, suiteTime,
        "us-east-1", "service", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", addContentHash = false,
    )

    @Test
    fun `test suite - get-vanilla`() {
        val signed = suite("GET", "/")
        assertEquals(
            "GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            signed.canonicalRequest,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            signed.stringToSign,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signed.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed.authorization,
        )
    }

    @Test
    fun `test suite - get-vanilla-query-order-key-case`() {
        val signed = suite("GET", "/", listOf("Param2" to "value2", "Param1" to "value1"))
        assertEquals("GET\n/\nParam1=value1&Param2=value2\n", signed.canonicalRequest.substringBefore("host:"))
        assertEquals("b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500", signed.signature)
    }

    @Test
    fun `test suite - post-vanilla`() {
        assertEquals("5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b", suite("POST", "/").signature)
    }

    @Test
    fun `test suite - get-utf8`() {
        val signed = suite("GET", "/" + S3Client.encodeKey("ሴ"))
        assertEquals("GET\n/%E1%88%B4\n\n", signed.canonicalRequest.substringBefore("host:"))
        assertEquals("8318018e0b0f223aa2bbf98705b62bb787dc9c0e678f255a891fd03141be5d85", signed.signature)
    }

    @Test
    fun `keys encode each segment and keep the slashes`() {
        assertEquals("01/01J0ABCDEF/original.png", S3Client.encodeKey("01/01J0ABCDEF/original.png"))
        assertEquals("a%20b/%C3%BC%2B%3D%26%25~_-./%F0%9F%98%80", S3Client.encodeKey("a b/ü+=&%~_-./😀"))
        assertEquals("a//b/", S3Client.encodeKey("a//b/"))
        assertFailsWith<IllegalArgumentException> { S3Client.encodeKey("") }
        assertFailsWith<IllegalArgumentException> { S3Client.encodeKey("a/../b") }
        assertFailsWith<IllegalArgumentException> { S3Client.encodeKey("./a") }
        assertFailsWith<IllegalArgumentException> { S3Client.encodeKey("x".repeat(1025)) }
    }

    @Test
    fun `query values encode slashes and spaces`() {
        assertEquals("continuation-token=a%2Fb%2Bc%3D&list-type=2&prefix=01%2Fa%20b", S3Client.canonicalQuery(
            listOf("list-type" to "2", "prefix" to "01/a b", "continuation-token" to "a/b+c="),
        ))
    }

    @Test
    fun `the host header carries only a non-default port`() {
        assertEquals("acct.r2.example.com", S3Client.hostHeader(URI.create("https://acct.r2.example.com")))
        assertEquals("acct.r2.example.com", S3Client.hostHeader(URI.create("https://acct.r2.example.com:443")))
        assertEquals("127.0.0.1:19000", S3Client.hostHeader(URI.create("http://127.0.0.1:19000")))
    }

    @Test
    fun `a big body gets a longer timeout`() {
        assertEquals(Duration.ofSeconds(60), S3Client.transferTimeout(10))
        assertEquals(Duration.ofSeconds(260), S3Client.transferTimeout(200_000_000))
    }

    @Test
    fun `the client never prints its credentials`() {
        val client = S3Client("https://acct.r2.example.com/", "media", "KEYID-EXAMPLE", "SECRET-EXAMPLE")
        assertEquals("S3Client(https://acct.r2.example.com/media)", client.toString())
    }
}
