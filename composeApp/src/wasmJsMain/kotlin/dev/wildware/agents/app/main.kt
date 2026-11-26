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
    // The app draws with WebGL 2. Without it (graphics acceleration turned off, or a remote desktop
    // or virtual machine with no graphics card) it can't draw at all, and the page stayed blank
    // grey: say why instead.
    if (!canDraw()) return cannotDraw()
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

/** Whether this browser gives a page WebGL 2, which the app draws with. */
private fun canDraw(): Boolean = js(
    "(() => { try { return !!document.createElement('canvas').getContext('webgl2'); } catch (e) { return false; } })()",
)

/**
 * In place of "Loading": why the app can't draw here, and where to get the app instead. Plain
 * HTML in the page's own colours, so it reads in light and dark.
 */
private fun cannotDraw(): Unit = js(
    """{
    const box = document.getElementById('loading') || document.body.appendChild(document.createElement('div'));
    box.id = 'cannot-draw';
    box.setAttribute('role', 'alert');
    box.style.cssText = 'position:fixed;inset:0;display:flex;align-items:center;justify-content:center;padding:24px;opacity:1;text-align:center;line-height:1.5';
    box.textContent = '';
    const text = box.appendChild(document.createElement('p'));
    text.style.cssText = 'max-width:30em;margin:0';
    const link = (label, href) => {
        const a = document.createElement('a');
        a.textContent = label;
        a.href = href;
        a.rel = 'noopener';
        a.style.color = 'inherit';
        return a;
    };
    text.append(
        "This browser can't draw Wildware Feed. Turn on hardware acceleration (WebGL) in your browser settings, or get the app for ",
        link('iPhone', 'https://apps.apple.com/app/id6836368769'),
        ', ',
        link('Mac, Windows or Linux', 'https://github.com/wildware-uk/wildware-feed/releases/latest'),
        '.',
    );
}"""
)

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
