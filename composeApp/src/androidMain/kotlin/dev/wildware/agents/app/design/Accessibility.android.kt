package dev.wildware.agents.app.design

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Android's "Remove animations" switch (it sets the animator speed to zero) and, from Android 14,
 * the contrast slider turned up past halfway.
 */
@Composable
actual fun systemAccessibility(): AccessibilityPrefs {
    val context = LocalContext.current
    // Read again when the configuration changes, which is when these switches usually move.
    LocalConfiguration.current
    val animatorScale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val contrast = if (Build.VERSION.SDK_INT >= 34) {
        runCatching { (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).contrast }.getOrDefault(0f)
    } else {
        0f
    }
    return AccessibilityPrefs(reduceMotion = animatorScale == 0f, highContrast = contrast >= 0.5f)
}
