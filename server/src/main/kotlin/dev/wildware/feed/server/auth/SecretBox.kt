package dev.wildware.feed.server.auth

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for shared secrets (docs/SECRETS.md). Each value gets its own random data key;
 * the value is sealed with AES-256-GCM under the data key, and the data key with AES-256-GCM under a
 * key-encryption key (KEK) made from SECRETS_KEY by HKDF-SHA256. Both seals carry the secret's id as
 * associated data, so a sealed value only opens on the row it was made for.
 *
 * [current] seals; [old] keys only open, so rows can be re-sealed after SECRETS_KEY changes.
 */
class SecretBox(current: String, old: List<String> = emptyList()) {
    /** What a row stores: which KEK, the wrapped data key and the sealed value, both base64 (nonce first). */
    data class Sealed(val keyId: String, val wrappedKey: String, val sealed: String) {
        override fun toString() = "Sealed(keyId=$keyId)"
    }

    /** The row was sealed under a key this server no longer has, or it was tampered with. */
    class CannotOpen(message: String) : RuntimeException(message)

    private class Kek(val id: String, val key: SecretKeySpec)

    private val currentKek = kek(current)
    private val keks: Map<String, Kek> = (listOf(currentKek) + old.map(::kek)).associateBy { it.id }

    /** The fingerprint of the key that seals now. Not secret: it only says which key a row needs. */
    val currentKeyId: String get() = currentKek.id

    fun seal(secretId: String, value: ByteArray): Sealed {
        val dataKey = ByteArray(32).also(random::nextBytes)
        try {
            val sealed = gcm(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), valueAad(secretId), value)
            return Sealed(currentKek.id, wrap(currentKek, secretId, dataKey), sealed)
        } finally {
            dataKey.fill(0)
        }
    }

    fun open(secretId: String, row: Sealed): ByteArray {
        val dataKey = unwrap(secretId, row)
        try {
            return gcmOpen(SecretKeySpec(dataKey, "AES"), valueAad(secretId), row.sealed)
                ?: throw CannotOpen("the sealed value of secret $secretId does not open: it was changed or moved")
        } finally {
            dataKey.fill(0)
        }
    }

    /** The same value under the current KEK; null when it already is. Only the data key is re-sealed. */
    fun reseal(secretId: String, row: Sealed): Sealed? {
        if (row.keyId == currentKek.id) return null
        val dataKey = unwrap(secretId, row)
        try {
            return row.copy(keyId = currentKek.id, wrappedKey = wrap(currentKek, secretId, dataKey))
        } finally {
            dataKey.fill(0)
        }
    }

    private fun wrap(kek: Kek, secretId: String, dataKey: ByteArray) =
        gcm(Cipher.ENCRYPT_MODE, kek.key, keyAad(secretId, kek.id), dataKey)

    private fun unwrap(secretId: String, row: Sealed): ByteArray {
        val kek = keks[row.keyId]
            ?: throw CannotOpen("secret $secretId is sealed under key ${row.keyId}, which is neither SECRETS_KEY nor in SECRETS_KEY_OLD")
        return gcmOpen(kek.key, keyAad(secretId, kek.id), row.wrappedKey)
            ?: throw CannotOpen("the data key of secret $secretId does not open: it was changed or moved")
    }

    private fun gcm(mode: Int, key: SecretKeySpec, aad: ByteArray, input: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return b64.encodeToString(nonce + cipher.doFinal(input))
    }

    private fun gcmOpen(key: SecretKeySpec, aad: ByteArray, text: String): ByteArray? {
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        if (bytes.size < NONCE_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()
        private val b64 = Base64.getEncoder()
        private val SALT = "wildware-feed/secrets/salt/v1".toByteArray()
        private val INFO = "wildware-feed/secrets/kek/v1".toByteArray()

        private fun valueAad(secretId: String) = "wildware-feed/secret/v1|$secretId".toByteArray()
        private fun keyAad(secretId: String, keyId: String) = "wildware-feed/data-key/v1|$secretId|$keyId".toByteArray()

        private fun kek(secret: String): Kek {
            val bytes = hkdf(secret.toByteArray(Charsets.UTF_8), SALT, INFO)
            val id = Tokens.hex(hmac(bytes, "wildware-feed/secrets/key-id/v1".toByteArray())).take(16)
            return Kek(id, SecretKeySpec(bytes, "AES"))
        }

        /** HKDF-SHA256 (RFC 5869) for one 32-byte block. */
        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val prk = hmac(salt, ikm)
            return hmac(prk, info + byteArrayOf(1))
        }

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(message) }
    }
}
