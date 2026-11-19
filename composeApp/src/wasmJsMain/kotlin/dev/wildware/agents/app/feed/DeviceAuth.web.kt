package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** A browser has no prompt the app can reach: being signed in is the check. */
@Composable
actual fun rememberPlatformDeviceAuth(): DeviceAuth = remember { DeviceAuth { true } }
