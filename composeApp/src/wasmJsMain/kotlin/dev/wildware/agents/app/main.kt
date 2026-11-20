package dev.wildware.agents.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.InviteLink
import dev.wildware.agents.app.session.webPlatform
import kotlinx.browser.document
import kotlinx.browser.window

/** The web app: the same app as on the phone and the desktop, in the page the dashboard serves. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val platform = webPlatform()
    val navigator = AppNavigator()
    // An invite's page links here as `/#invite=<code>`: open the join screen with it filled in.
    InviteLink.inFragment(window.location.hash)?.let { code ->
        navigator.openInvite(code)
        // Out of the address bar, so a reload or a copied link doesn't carry the code on.
        window.history.replaceState(null, "", window.location.pathname + window.location.search)
    }
    ComposeViewport(document.body!!) {
        AgentsApp(platform, navigator = navigator)
    }
    // The page shows a plain "Loading" until the app draws; take it away now.
    document.getElementById("loading")?.remove()
}
