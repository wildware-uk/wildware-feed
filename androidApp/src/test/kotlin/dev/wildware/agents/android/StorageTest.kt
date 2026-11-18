package dev.wildware.agents.android

import dev.wildware.agents.app.session.SavedSession
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Keystore's sealing, with a plain key: the computer has no Android Keystore. */
private class PlainBox(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretBox {
    override fun seal(plain: ByteArray) = KeystoreSecretBox.sealWith(key, plain)
    override fun open(sealed: ByteArray) = KeystoreSecretBox.openWith(key, sealed)
}

class StorageTest {
    private val dir: File = Files.createTempDirectory("agents-android").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val session = SavedSession("https://dashboard.example.com", "secret-device-key", "Pixel", "device-1")

    @Test
    fun theSessionComesBackAsSaved() {
        val store = SealedSessionStore(File(dir, "session.bin"), PlainBox())
        assertNull(store.load())
        store.save(session)
        assertEquals(session, store.load())
    }

    @Test
    fun theKeyIsNeverOnDiskInPlainText() {
        val file = File(dir, "session.bin")
        SealedSessionStore(file, PlainBox()).save(session)
        val onDisk = file.readBytes().decodeToString()
        assertFalse("secret-device-key" in onDisk)
        assertFalse("dashboard.example.com" in onDisk)
    }

    @Test
    fun aFileSealedWithAnotherKeyCountsAsSignedOut() {
        val file = File(dir, "session.bin")
        SealedSessionStore(file, PlainBox()).save(session)
        // The Keystore key was wiped (a restore onto a new phone, say): the old file can't open.
        assertNull(SealedSessionStore(file, PlainBox()).load())
        assertFalse(file.exists())
    }

    @Test
    fun signingOutForgetsTheKey() {
        val store = SealedSessionStore(File(dir, "session.bin"), PlainBox())
        store.save(session)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun eachSealIsDifferent() {
        val box = PlainBox()
        val a = box.seal("same".encodeToByteArray())
        val b = box.seal("same".encodeToByteArray())
        assertFalse(a.contentEquals(b))
        assertEquals("same", box.open(a).decodeToString())
    }

    @Test
    fun textStoresWriteWholeFiles() {
        val store = FileTextStore(File(dir, "nested/read-marks.json"))
        assertNull(store.read())
        store.write("{\"a\":1}")
        store.write("{\"b\":2}")
        assertEquals("{\"b\":2}", store.read())
        assertFalse(File(dir, "nested/read-marks.json.tmp").exists())
    }

    @Test
    fun theOutboxKeepsItsListAndFilesAndClearsOnSignOut() {
        val outbox = FileOutboxStorage(File(dir, "outbox"))
        assertEquals("", outbox.load())
        outbox.save("[1]")
        outbox.writeFile("photo.png", byteArrayOf(1, 2, 3))
        assertEquals("[1]", FileOutboxStorage(File(dir, "outbox")).load())
        assertContentEquals(byteArrayOf(1, 2, 3), outbox.readFile("photo.png"))
        outbox.deleteFile("photo.png")
        assertNull(outbox.readFile("photo.png"))

        outbox.writeFile("other.png", byteArrayOf(4))
        outbox.clear()
        assertEquals("", outbox.load())
        assertNull(outbox.readFile("other.png"))
    }

    @Test
    fun sharedFilesStopJustPastTheComposersLimit() {
        val big = ByteArray(1000) { 7 }
        assertEquals(101, readCapped(ByteArrayInputStream(big), 101).size)
        assertEquals(1000, readCapped(ByteArrayInputStream(big), 5000).size)
        assertTrue(readCapped(ByteArrayInputStream(ByteArray(0)), 10).isEmpty())
    }
}
