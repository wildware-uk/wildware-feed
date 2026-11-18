package dev.wildware.agents.app.design

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** Reads the iPhone's Reduce Motion and Increase Contrast switches. */
@Composable
actual fun systemAccessibility(): AccessibilityPrefs = AccessibilityPrefs(
    reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
    highContrast = UIAccessibilityDarkerSystemColorsEnabled(),
)
