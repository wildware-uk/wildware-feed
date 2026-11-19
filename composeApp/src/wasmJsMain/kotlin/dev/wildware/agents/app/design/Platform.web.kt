package dev.wildware.agents.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.browser.window

/** The browser says light or dark (`prefers-color-scheme`). */
@Composable
actual fun systemDarkPreference(): Boolean? = isSystemInDarkTheme()

/** The browser's Reduce Motion and More Contrast settings, as media queries. */
@Composable
actual fun systemAccessibility(): AccessibilityPrefs = remember {
    AccessibilityPrefs(
        reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches,
        highContrast = window.matchMedia("(prefers-contrast: more)").matches,
    )
}

/** The page's own icon is the favicon; the app draws its plain tile. */
actual fun appIconBitmap(): ImageBitmap? = null
