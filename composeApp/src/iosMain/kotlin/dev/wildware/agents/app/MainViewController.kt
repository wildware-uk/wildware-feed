package dev.wildware.agents.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.wildware.agents.app.session.SessionStore
import dev.wildware.agents.app.session.iosPlatform
import platform.UIKit.UIViewController

/**
 * What the SwiftUI app embeds to show the Compose app. Swift passes a [SessionStore] backed by
 * the Keychain, so the device key never sits in a plain file.
 */
fun MainViewController(sessions: SessionStore): UIViewController {
    val platform = iosPlatform(sessions)
    return ComposeUIViewController { AgentsApp(platform) }
}
