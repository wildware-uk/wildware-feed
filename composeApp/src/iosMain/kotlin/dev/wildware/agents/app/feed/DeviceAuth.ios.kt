package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.coroutines.resume

/** Face ID or Touch ID, falling back to the passcode. A phone with no passcode has nothing to check. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPlatformDeviceAuth(): DeviceAuth = remember {
    DeviceAuth { reason ->
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) return@DeviceAuth true
        suspendCancellableCoroutine { done ->
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { ok, _ -> done.resume(ok) }
        }
    }
}
