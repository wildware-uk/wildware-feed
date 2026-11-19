package dev.wildware.agents.app.feed

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The fingerprint, face or screen lock, through the system's own prompt (Android 9 and later). A
 * phone with no screen lock has nothing to check against; neither, here, does Android 8.
 */
@Composable
actual fun rememberPlatformDeviceAuth(): DeviceAuth {
    val context = LocalContext.current
    return remember(context) { DeviceAuth { reason -> confirm(context, reason) } }
}

private suspend fun confirm(context: Context, reason: String): Boolean {
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isDeviceSecure) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    val builder = BiometricPrompt.Builder(context).setTitle(reason)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val prompt = builder.build()
    return suspendCancellableCoroutine { done ->
        val cancel = CancellationSignal()
        done.invokeOnCancellation { cancel.cancel() }
        prompt.authenticate(cancel, context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (done.isActive) done.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (done.isActive) done.resume(false)
            }
        })
    }
}
