package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * "Is it really you?": Face ID, Touch ID, a fingerprint or the device's passcode, before a secret
 * is shown or copied. A device with no lock set, or a platform with no such prompt (desktop),
 * answers yes: there is nothing to check against beyond being signed in.
 */
fun interface DeviceAuth {
    suspend fun confirm(reason: String): Boolean
}

/** Tests and previews supply their own; otherwise the platform's prompt is used. */
val LocalDeviceAuth = staticCompositionLocalOf<DeviceAuth?> { null }

/** The platform's own prompt. */
@Composable
expect fun rememberPlatformDeviceAuth(): DeviceAuth
