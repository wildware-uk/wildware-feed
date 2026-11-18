package dev.wildware.agents.app.session

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/**
 * The iPhone's platform. [sessions] comes from Swift, which keeps the key in the Keychain.
 * Read marks and the outbox stay in memory until the Swift app hands over its own stores.
 */
fun iosPlatform(sessions: SessionStore): AppPlatform {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    return AppPlatform(
        sessions = sessions,
        readMarks = MemoryTextStore(),
        outbox = MemoryOutboxStorage(),
        httpEngine = { Darwin.create() },
        deviceName = UIDevice.currentDevice.name,
        imageCacheDir = caches?.let { "$it/agents-images" },
    )
}
