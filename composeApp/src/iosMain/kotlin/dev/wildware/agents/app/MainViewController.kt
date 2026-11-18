package dev.wildware.agents.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.rememberSystemSafeArea
import dev.wildware.agents.app.session.SessionStore
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.VoiceInput
import dev.wildware.agents.app.session.iosPlatform
import platform.UIKit.UIViewController

/**
 * What the SwiftUI app embeds to show the Compose app. Swift passes a [SessionStore] backed by
 * the Keychain, so the device key never sits in a plain file, and [dataDir], a folder in the app
 * group for read marks. Swift keeps [navigator] to open cards from notifications,
 * widgets, search and Siri. [outboxDir] is the outbox the extensions share. [photoPicker] attaches
 * pictures (none means no paperclip); [voiceInput] records the mic's voice notes. [sample]
 * shows made-up data for screenshots, and never signs in.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(
    sessions: SessionStore,
    dataDir: String,
    navigator: AppNavigator,
    sample: Boolean,
    outboxDir: String,
    photoPicker: FilePicker? = null,
    voiceInput: VoiceInput? = null,
): UIViewController {
    val platform = iosPlatform(sessions, dataDir, outboxDir, photoPicker, voiceInput)
    return ComposeUIViewController(
        configure = {
            // The screens move their own text boxes above the keyboard, through the safe area below.
            onFocusBehavior = OnFocusBehavior.DoNothing
            // Encodes each frame's Metal commands off the main thread, so the main thread has the
            // whole 8 ms of a 120 Hz frame for touches, layout and drawing.
            parallelRendering = true
        },
    ) {
        // One safe area for the app's life. The keyboard moves its bottom every frame while it
        // slides; only the padding that reads it moves, nothing redraws.
        val safeArea = rememberSystemSafeArea()
        if (sample) AgentsPreview(safeArea = safeArea) else AgentsApp(platform, safeArea = safeArea, navigator = navigator)
    }
}
