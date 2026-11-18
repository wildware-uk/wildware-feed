package dev.wildware.feed.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Token formats and hashes, byte-compatible with the TS server (spec/01-config-and-auth.md §6-8). */
object Tokens {
    private val random = SecureRandom()
    private val shape = Regex("^[A-Za-z0-9_-]{43}$")
    private val ulid = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")
    private const val UPLOAD_PREFIX = "agent-dashboard:upload-token:v1:"

    fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun hmac(key: String, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Equal, in time that does not depend on where they differ. */
    fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** A new agent token, device key or share token: 32 random bytes, base64url, 43 characters. */
    fun random(): String = base64url(ByteArray(32).also(random::nextBytes))

    fun wellFormed(token: String) = shape.matches(token)

    /** How agent tokens and device keys are stored. */
    fun hash(token: String, secret: String): String {
        require(secret.isNotEmpty()) { "TOKEN_SECRET is empty" }
        return hex(hmac(secret, token))
    }

    fun hashShare(token: String, secret: String): String = hex(hmac(secret, "share:$token"))

    fun signUpload(id: String, secret: String): String = "$id.${base64url(hmac(secret, UPLOAD_PREFIX + id))}"

    /** The upload token's row id, or null for anything this server did not sign. */
    fun verifyUpload(token: String, secret: String): String? {
        val parts = token.split('.')
        if (parts.size != 2) return null
        val (id, sig) = parts
        if (!ulid.matches(id) || sig.isEmpty()) return null
        return id.takeIf { same(signUpload(id, secret), token) }
    }
}
