package dev.wildware.agents.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A signed-in device: which dashboard, its key, and what the device is called there. */
@Serializable
data class SavedSession(
    val server: String,
    val token: String,
    val deviceName: String,
    /** The dashboard's id for this device. The iPhone hands it to the watch with the key. */
    val deviceId: String? = null,
    /** Signed in to the server this app runs itself, which must be started before the session opens. */
    val local: Boolean = false,
)

/**
 * Where the device key lives between launches. It is a password in all but name, so each
 * platform keeps it somewhere private: a file only the user can read on desktop, the Keychain on
 * iPhone (implemented in Swift, which can adopt this interface).
 */
interface SessionStore {
    fun load(): SavedSession?
    fun save(session: SavedSession)

    /** Forgets the key. Signing out calls this. */
    fun clear()
}

/** Keeps the session in memory only. For tests, previews, and until the iPhone wires the Keychain. */
class MemorySessionStore(private var session: SavedSession? = null) : SessionStore {
    override fun load(): SavedSession? = session
    override fun save(session: SavedSession) {
        this.session = session
    }
    override fun clear() {
        session = null
    }
}

internal val SessionJson = Json { ignoreUnknownKeys = true }
