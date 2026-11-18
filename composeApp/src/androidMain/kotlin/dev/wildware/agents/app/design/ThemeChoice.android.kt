package dev.wildware.agents.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** Android always says light or dark. */
@Composable
actual fun systemDarkPreference(): Boolean? = isSystemInDarkTheme()
