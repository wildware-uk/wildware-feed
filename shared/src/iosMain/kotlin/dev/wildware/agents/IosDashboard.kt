package dev.wildware.agents

import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/** Builds the client on Apple's own networking stack. This is the entry point Swift uses. */
fun dashboardClient(server: String, deviceToken: String?): DashboardClient =
    DashboardClient(server, deviceToken, Darwin.create())

@OptIn(ExperimentalForeignApi::class)
fun attachment(filename: String, mimeType: String, data: NSData): Attachment {
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    }
    return Attachment(filename, mimeType, bytes)
}

