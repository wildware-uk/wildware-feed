package dev.wildware.agents.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** iOS always says light or dark. */
@Composable
actual fun systemDarkPreference(): Boolean? = isSystemInDarkTheme()
