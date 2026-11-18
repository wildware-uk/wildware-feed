package dev.wildware.agents.app

import dev.wildware.agents.app.session.FileSessionStore
import dev.wildware.agents.app.session.SavedSession
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopStorageTest {
    private val dir = createTempDirectory("agents-test").toFile()

    @Test
    fun theDeviceKeyIsReadableOnlyByItsOwner() {
        val file = dir.resolve("session.json")
        val store = FileSessionStore(file)
        store.save(SavedSession("https://dash.test", "key-1", "Desk"))
        assertEquals("key-1", FileSessionStore(file).load()?.token)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun signingOutDeletesTheKeyFile() {
        val file = dir.resolve("session.json")
        val store = FileSessionStore(file)
        store.save(SavedSession("https://dash.test", "key-1", "Desk"))
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }
}
