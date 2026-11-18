package dev.wildware.agents.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.SessionStore
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.session.iosPlatform
import platform.UIKit.UIViewController

/**
 * What the SwiftUI app embeds to show the Compose app. Swift passes a [SessionStore] backed by
 * the Keychain, so the device key never sits in a plain file, and [dataDir], a folder in the app
 * group for read marks and the outbox. Swift keeps [navigator] to open cards from notifications,
 * widgets, search and Siri. [photoPicker] attaches pictures (none means no paperclip). [sample]
 * shows made-up data for screenshots, and never signs in.
 */
fun MainViewController(
    sessions: SessionStore,
    dataDir: String,
    navigator: AppNavigator,
    sample: Boolean,
    photoPicker: FilePicker? = null,
): UIViewController {
    val platform = iosPlatform(sessions, dataDir, photoPicker)
    return ComposeUIViewController(
        configure = {
            // The screens move their own text boxes above the keyboard, through the safe area below.
            onFocusBehavior = OnFocusBehavior.DoNothing
        },
    ) {
        val safeArea = safeArea()
        if (sample) AgentsPreview(safeArea = safeArea) else AgentsApp(platform, safeArea = safeArea, navigator = navigator)
    }
}

/** The status bar and notch above, and the home indicator or the keyboard (whichever is taller) below. */
@Composable
private fun safeArea(): SafeArea {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    return with(density) { SafeArea(top = insets.getTop(density).toDp(), bottom = insets.getBottom(density).toDp()) }
}
