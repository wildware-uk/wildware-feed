package dev.wildware.agents.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.session.SessionStore
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Locks and unlocks a few bytes. The device key is sealed with one before it touches the disk. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM with a key that lives in the Android Keystore: the key never leaves the secure
 * hardware (where the phone has it), so the sealed file is useless copied off the phone.
 * Each seal is the 12-byte nonce followed by the ciphertext.
 */
class KeystoreSecretBox(private val alias: String = "agents-session") : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(Provider).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Provider)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray = sealWith(key(), plain)
    override fun open(sealed: ByteArray): ByteArray = openWith(key(), sealed)

    companion object {
        private const val Provider = "AndroidKeyStore"
        private const val Transformation = "AES/GCM/NoPadding"
        private const val NonceBytes = 12

        /** Sealing itself, apart from where the key comes from, so tests can run it with a plain key. */
        fun sealWith(key: SecretKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            check(nonce.size == NonceBytes)
            return nonce + cipher.doFinal(plain)
        }

        fun openWith(key: SecretKey, sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, NonceBytes))
            return cipher.doFinal(sealed, NonceBytes, sealed.size - NonceBytes)
        }
    }
}

private val SessionJson = Json { ignoreUnknownKeys = true }

/**
 * The device key, sealed by [box], in [file] in the app's private storage. A file that can't be
 * opened (the Keystore key was wiped, say) counts as signed out and is removed.
 */
class SealedSessionStore(private val file: File, private val box: SecretBox) : SessionStore {
    override fun load(): SavedSession? {
        if (!file.exists()) return null
        return runCatching {
            SessionJson.decodeFromString(SavedSession.serializer(), box.open(file.readBytes()).decodeToString())
        }.getOrElse {
            file.delete()
            null
        }
    }

    override fun save(session: SavedSession) {
        writeAtomically(file, box.seal(SessionJson.encodeToString(SavedSession.serializer(), session).encodeToByteArray()))
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Text sealed by [box] in [file]: the teams this phone is signed in to, keys included. A file that
 * can't be opened reads as nothing.
 */
class SealedTextStore(private val file: File, private val box: SecretBox) : TextStore {
    override fun read(): String? = if (!file.exists()) null else runCatching { box.open(file.readBytes()).decodeToString() }.getOrNull()
    override fun write(text: String) = writeAtomically(file, box.seal(text.encodeToByteArray()))
}

/** One text file in the app's private storage, written whole. */
class FileTextStore(private val file: File) : TextStore {
    override fun read(): String? = runCatching { file.readText() }.getOrNull()
    override fun write(text: String) = writeAtomically(file, text.encodeToByteArray())
}

/** The outbox list in one file and each waiting attachment in a folder beside it, as on desktop. */
class FileOutboxStorage(private val dir: File) : OutboxStorage {
    private val list = File(dir, "outbox.json")
    private val files = File(dir, "files")

    init {
        files.mkdirs()
    }

    override fun load(): String = runCatching { list.readText() }.getOrDefault("")
    override fun save(text: String) = writeAtomically(list, text.encodeToByteArray())
    override fun writeFile(name: String, bytes: ByteArray) = writeAtomically(File(files, name), bytes)
    override fun readFile(name: String): ByteArray? = runCatching { File(files, name).readBytes() }.getOrNull()
    override fun deleteFile(name: String) {
        File(files, name).delete()
    }

    /** Signing out: nothing queued for that account should send. */
    fun clear() {
        list.delete()
        files.listFiles()?.forEach { it.delete() }
    }
}

/** Writes through a temporary file and a rename, so a crash never leaves half a file. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
