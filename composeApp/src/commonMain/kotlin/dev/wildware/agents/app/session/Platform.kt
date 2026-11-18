package dev.wildware.agents.app.session

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.VoiceInput

/**
 * What the app needs from the device it runs on. Desktop and iPhone each build one; tests build
 * one with a mock server engine and in-memory stores.
 */
class AppPlatform(
    val sessions: SessionStore,
    /** Which comments have been seen, per card. */
    val readMarks: TextStore,
    /** What is waiting to send. Kept on disk so nothing is lost when the app closes offline. */
    val outbox: OutboxStorage,
    /** Builds a network engine. Each signed-in session gets its own. */
    val httpEngine: () -> HttpClientEngine,
    /** What to call this device on the dashboard, offered on the sign-in screen. */
    val deviceName: String,
    /** A folder for the image disk cache, or null for memory only. */
    val imageCacheDir: String?,
    /** Forgets this device's read marks and outbox, on sign out. */
    val clearLocalData: () -> Unit = {},
    /** The owner's own settings (light or dark), kept across sign-outs. */
    val settings: TextStore = MemoryTextStore(),
    /** Picks files to attach, or null where there is no picker yet. */
    val filePicker: FilePicker? = null,
    /** Records voice notes for the composer's mic, or null where there is no mic. */
    val voiceInput: VoiceInput? = null,
    /** Fires when another process (the iPhone's share sheet, Siri, alert buttons) changed the outbox. */
    val outboxChanged: Flow<Unit> = emptyFlow(),
    /** Fires when the app comes back to the front after being away, so the live connection is renewed. */
    val foreground: Flow<Unit> = emptyFlow(),
) {
    companion object {
        /** Everything in memory: for tests and previews. */
        fun inMemory(
            engine: () -> HttpClientEngine,
            sessions: SessionStore = MemorySessionStore(),
            deviceName: String = "Test device",
            filePicker: FilePicker? = null,
        ) = AppPlatform(sessions, MemoryTextStore(), MemoryOutboxStorage(), engine, deviceName, imageCacheDir = null, filePicker = filePicker)
    }
}

