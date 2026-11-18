package dev.wildware.agents.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit

/**
 * Windows reports high contrast through AWT. Other desktops have no portable switch, so the
 * settings screen covers them. AGENTS_REDUCE_MOTION=1 forces reduced motion anywhere.
 */
@Composable
actual fun systemAccessibility(): AccessibilityPrefs = remember {
    val highContrast = runCatching {
        Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") == true
    }.getOrDefault(false)
    AccessibilityPrefs(
        reduceMotion = System.getenv("AGENTS_REDUCE_MOTION") == "1",
        highContrast = highContrast,
    )
}
