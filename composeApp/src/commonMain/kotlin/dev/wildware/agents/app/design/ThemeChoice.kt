package dev.wildware.agents.app.design

import androidx.compose.runtime.Composable

/** Light or dark, as the owner picked it. [System] follows the device. */
enum class ThemeChoice { System, Dark, Light }

/**
 * Whether the device asks for dark, light, or says nothing (a bare Linux session, say). Null
 * means no preference is known.
 */
@Composable
expect fun systemDarkPreference(): Boolean?

/** The owner's pick wins; then the device's; with neither, dark, because the look is dark-first. */
fun resolveDark(choice: ThemeChoice, system: Boolean?): Boolean = when (choice) {
    ThemeChoice.Dark -> true
    ThemeChoice.Light -> false
    ThemeChoice.System -> system ?: true
}
