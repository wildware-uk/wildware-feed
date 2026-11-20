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
    keysReachTheApp()
}

/**
 * Keys reach the app only through its canvas, and a page that has just loaded has the focus on
 * nothing (the body), so Ctrl+K and the other shortcuts did nothing until the first click. This
 * gives the canvas the focus once it is there, and again whenever the page comes back with the
 * focus on nothing, never taking it from a box that has it.
 */
private fun keysReachTheApp(): Unit = js(
    """{
    const idle = () => !document.activeElement || document.activeElement === document.body;
    const canvas = () => {
        for (const el of document.body.querySelectorAll('*')) {
            const c = el.shadowRoot && el.shadowRoot.querySelector('canvas');
            if (c) return c;
        }
        return null;
    };
    const focus = (tries) => {
        if (!idle()) return;
        const c = canvas();
        if (c) c.focus({ preventScroll: true });
        else if (tries > 0) requestAnimationFrame(() => focus(tries - 1));
    };
    focus(120);
    window.addEventListener('focus', () => focus(0));
}"""
)
