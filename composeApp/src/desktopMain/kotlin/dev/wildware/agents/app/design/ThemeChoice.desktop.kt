package dev.wildware.agents.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.concurrent.TimeUnit

/**
 * Mac and Windows report light or dark. Linux has no single switch: GNOME's colour-scheme
 * setting and a dark GTK theme are read when present; otherwise there is no preference.
 */
@Composable
actual fun systemDarkPreference(): Boolean? {
    val os = remember { System.getProperty("os.name").lowercase() }
    if ("mac" in os || "win" in os) return isSystemInDarkTheme()
    return remember { linuxPreference() }
}

internal fun linuxPreference(): Boolean? {
    System.getenv("GTK_THEME")?.let { theme -> return theme.contains("dark", ignoreCase = true) }
    val scheme = runCatching {
        val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroy()
            null
        } else {
            process.inputStream.bufferedReader().readText().trim().trim('\'')
        }
    }.getOrNull()
    return when (scheme) {
        "prefer-dark" -> true
        "prefer-light" -> false
        else -> null
    }
}
