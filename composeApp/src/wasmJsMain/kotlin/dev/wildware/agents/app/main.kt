package dev.wildware.agents.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.wildware.agents.app.session.webPlatform
import kotlinx.browser.document

/** The web app: the same app as on the phone and the desktop, in the page the dashboard serves. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val platform = webPlatform()
    ComposeViewport(document.body!!) {
        AgentsApp(platform)
    }
    // The page shows a plain "Loading" until the app draws; take it away now.
    document.getElementById("loading")?.remove()
}
