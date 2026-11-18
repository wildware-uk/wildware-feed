package dev.wildware.feed.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * argon2id in the PHC string format node-argon2 writes: `$argon2id$v=19$m=65536,p=4,t=3$salt$hash`
 * (spec/01-config-and-auth.md §4). Verification reads the parameters from the string.
 */
object Passwords {
    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val out = derive(Argon2Parameters.ARGON2_id, 0x13, 65536, 3, 4, salt, password, 32)
        return "\$argon2id\$v=19\$m=65536,p=4,t=3\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(out)}"
    }

    fun verify(encoded: String, password: String): Boolean = runCatching {
        if (encoded.isEmpty() || password.isEmpty()) return false
        val parts = encoded.split('$')
        // ["", "argon2id", "v=19", "m=..,p=..,t=..", salt, hash]
        if (parts.size != 6 || parts[0].isNotEmpty()) return false
        val type = when (parts[1]) {
            "argon2id" -> Argon2Parameters.ARGON2_id
            "argon2i" -> Argon2Parameters.ARGON2_i
            "argon2d" -> Argon2Parameters.ARGON2_d
            else -> return false
        }
        val version = parts[2].removePrefix("v=").toInt()
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = decoder.decode(parts[4])
        val expected = decoder.decode(parts[5])
        val actual = derive(type, version, params.getValue("m"), params.getValue("t"), params.getValue("p"), salt, password, expected.size)
        MessageDigest.isEqual(expected, actual)
    }.getOrDefault(false)

    private fun derive(type: Int, version: Int, memoryKb: Int, iterations: Int, parallelism: Int, salt: ByteArray, password: String, length: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(type)
                .withVersion(version)
                .withMemoryAsKB(memoryKb)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build(),
        )
        return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }
}
