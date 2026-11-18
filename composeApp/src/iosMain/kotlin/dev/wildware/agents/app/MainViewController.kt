package dev.wildware.agents.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** What the SwiftUI app embeds to show the Compose screen. */
fun MainViewController(): UIViewController = ComposeUIViewController { AgentsApp() }
