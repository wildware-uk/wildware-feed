package dev.wildware.feed.server.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretBoxTest {
    private val keyA = "a".repeat(32) + "-first-key"
    private val keyB = "b".repeat(32) + "-second-key"
    private val value = "sk-test-not-a-real-key-0123456789".toByteArray()

    @Test
    fun `a value seals and opens again, and the sealed text holds none of it`() {
        val box = SecretBox(keyA)
        val sealed = box.seal("S1", value)
        assertContentEquals(value, box.open("S1", sealed))
        assertEquals(box.currentKeyId, sealed.keyId)
        assertTrue(!sealed.sealed.contains("sk-test") && !sealed.wrappedKey.contains("sk-test"))
        assertTrue(!sealed.toString().contains(sealed.sealed))
        // A fresh data key and nonce each time.
        assertNotEquals(sealed.sealed, box.seal("S1", value).sealed)
    }

    @Test
    fun `a sealed value moved to another row, or changed, does not open`() {
        val box = SecretBox(keyA)
        val sealed = box.seal("S1", value)
        assertFailsWith<SecretBox.CannotOpen> { box.open("S2", sealed) }
        val flipped = java.util.Base64.getDecoder().decode(sealed.sealed).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SecretBox.CannotOpen> { box.open("S1", sealed.copy(sealed = java.util.Base64.getEncoder().encodeToString(flipped))) }
        assertFailsWith<SecretBox.CannotOpen> { box.open("S1", sealed.copy(wrappedKey = box.seal("S1", value).wrappedKey)) }
    }

    @Test
    fun `a new key opens old rows while the old one is listed, and re-sealing moves them over`() {
        val old = SecretBox(keyA).seal("S1", value)
        assertFailsWith<SecretBox.CannotOpen> { SecretBox(keyB).open("S1", old) }

        val rotating = SecretBox(keyB, old = listOf(keyA))
        assertContentEquals(value, rotating.open("S1", old))
        val fresh = rotating.reseal("S1", old)!!
        assertEquals(rotating.currentKeyId, fresh.keyId)
        assertEquals(old.sealed, fresh.sealed, "only the data key is re-sealed")
        assertNull(rotating.reseal("S1", fresh))

        // Once moved, the old key is no longer needed.
        assertContentEquals(value, SecretBox(keyB).open("S1", fresh))
        assertNotEquals(SecretBox(keyA).currentKeyId, SecretBox(keyB).currentKeyId)
    }

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", Tokens.hex(SecretBox.hkdf(ikm, salt, info)))
    }
}
