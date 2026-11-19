package dev.wildware.feed.server.http

import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.OAuth
import java.net.URI

/**
 * The authorize page, server-rendered: sign in with the owner password, then allow or deny an app as
 * one agent. Plain HTML forms and no scripts, in the app's look (dark first, one lime accent).
 * Everything that came from the client is escaped.
 */
object OAuthPage {
    fun problem(message: String) = layout(
        "Can't connect",
        """
        <h1>Can't connect this app</h1>
        <p>${esc(message)}</p>
        """,
    )

    fun signIn(request: AuthRequest, error: String?) = layout(
        "Sign in",
        """
        <p class="eyebrow">Wildware Feed</p>
        <h1><span class="client">${esc(request.client.name)}</span> wants to connect as an agent</h1>
        ${whereTo(request)}
        <form method="post" action="/oauth/authorize">
          ${hidden(request)}
          <label for="password">Your dashboard password</label>
          <input id="password" name="password" type="password" autocomplete="current-password" required autofocus${if (error != null) " aria-invalid=\"true\" aria-describedby=\"error\"" else ""}>
          ${error?.let { "<p class=\"error\" id=\"error\" role=\"alert\">${esc(it)}</p>" }.orEmpty()}
          <div class="actions"><button type="submit" name="action" value="sign_in" class="primary">Sign in</button></div>
        </form>
        <p class="faint">Sign in to choose whether to allow it. You stay signed in on this browser for 12 hours.</p>
        """,
    )

    fun consent(request: AuthRequest, agents: List<Agents.Agent>, csrf: String, suggested: String, existingId: String?, error: String?): String {
        val useExisting = existingId != null && agents.any { it.id == existingId }
        val options = agents.joinToString("") { a ->
            "<option value=\"${esc(a.id)}\"${if (a.id == existingId) " selected" else ""}>${esc(a.name)}</option>"
        }
        val existing = if (agents.isEmpty()) "" else """
          <div class="choice">
            <input type="radio" id="choice-existing" name="choice" value="existing"${if (useExisting) " checked" else ""}>
            <label for="choice-existing">An agent you already have</label>
            <label for="agent_id" class="sr">Agent</label>
            <select id="agent_id" name="agent_id">$options</select>
          </div>
        """
        return layout(
            "Allow ${request.client.name}?",
            """
            <p class="eyebrow">Wildware Feed</p>
            <h1><span class="client">${esc(request.client.name)}</span> wants to connect as an agent</h1>
            <p>It will post to your feed, ask you questions and read its messages, like any agent you connect. You can revoke it from the app at any time.</p>
            ${whereTo(request)}
            <form method="post" action="/oauth/authorize">
              ${hidden(request)}
              <input type="hidden" name="csrf" value="${esc(csrf)}">
              <fieldset>
                <legend>Connect it as</legend>
                <div class="choice">
                  <input type="radio" id="choice-new" name="choice" value="new"${if (useExisting) "" else " checked"}>
                  <label for="choice-new">A new agent named</label>
                  <label for="name" class="sr">Agent name</label>
                  <input id="name" name="name" type="text" maxlength="${OAuth.NAME_MAX}" value="${esc(suggested)}" autocomplete="off" spellcheck="false">
                </div>
                $existing
              </fieldset>
              ${error?.let { "<p class=\"error\" role=\"alert\">${esc(it)}</p>" }.orEmpty()}
              <div class="actions">
                <button type="submit" name="action" value="deny">Deny</button>
                <button type="submit" name="action" value="allow" class="primary">Allow</button>
              </div>
            </form>
            """,
        )
    }

    /** Where the answer goes, which the owner should check: the app's name is the app's own claim. */
    private fun whereTo(request: AuthRequest): String {
        val uri = runCatching { URI(request.redirectUri) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val (where, note) = when {
            uri != null && scheme == "http" && OAuth.isLoopbackHost(uri.host) ->
                "an app on this computer (${uri.host}${if (uri.port == -1) "" else ":${uri.port}"})" to
                    "Allow it only if you just started connecting it yourself."
            uri != null && scheme == "https" -> (uri.host ?: request.redirectUri) to "Allow it only if you know this site."
            else -> "the app that opens $scheme:// links" to "Allow it only if you just started connecting it yourself."
        }
        return """<p class="where">Afterwards you go back to <strong>${esc(where)}</strong>. The name above is what the app calls itself. ${esc(note)}</p>"""
    }

    private fun hidden(request: AuthRequest) =
        request.hidden.joinToString("") { (k, v) -> "<input type=\"hidden\" name=\"${esc(k)}\" value=\"${esc(v)}\">" }

    fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun layout(title: String, body: String) = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<meta name="color-scheme" content="dark light">
<title>${esc(title)} · Wildware Feed</title>
<style>
:root{--bg:#0A0B0D;--surface:#111215;--border:#1F2126;--border-strong:#2C2F36;--text:#ECEDEF;--muted:#9A9EA7;--faint:#656973;--accent:#C6F169;--on-accent:#0B0D06;--error:#FF6B6B;--focus:#C6F169;--sunken:#0D0E10}
@media (prefers-color-scheme: light){:root{--bg:#F6F6F7;--surface:#FFFFFF;--border:#E3E4E8;--border-strong:#CBCDD3;--text:#0E0F12;--muted:#5B5F68;--faint:#8A8E97;--accent:#0E0F12;--on-accent:#FFFFFF;--error:#D12C2C;--focus:#2F62D9;--sunken:#F2F2F4}}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px 16px;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{width:100%;max-width:440px;background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:28px}
.eyebrow{margin:0 0 6px;font:500 12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
h1{margin:0 0 12px;font-size:20px;line-height:1.3;font-weight:600}
.client{overflow-wrap:anywhere}
p{margin:0 0 14px;color:var(--muted)}
.where{padding:10px 12px;border:1px solid var(--border);border-radius:7px;background:var(--sunken);font-size:14px}
.where strong{color:var(--text);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-weight:500;overflow-wrap:anywhere}
.faint{color:var(--faint);font-size:13px;margin-top:16px;margin-bottom:0}
form{margin:18px 0 0}
fieldset{border:0;margin:0;padding:0}
legend{padding:0;margin-bottom:8px;font-weight:600}
.choice{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:6px 10px;margin-bottom:12px}
.choice input[type=text],.choice select{grid-column:2}
label{color:var(--text)}
input[type=password],input[type=text],select{width:100%;margin-top:6px;padding:9px 11px;font:inherit;color:var(--text);background:var(--sunken);border:1px solid var(--border-strong);border-radius:7px}
.choice input[type=text],.choice select{margin-top:0}
input[type=radio]{accent-color:var(--accent);width:16px;height:16px;margin:0}
.actions{display:flex;gap:10px;justify-content:flex-end;margin-top:18px}
button{font:inherit;font-weight:600;padding:9px 18px;border-radius:7px;border:1px solid var(--border-strong);background:transparent;color:var(--text);cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}
:focus-visible{outline:2px solid var(--focus);outline-offset:2px}
.error{color:var(--error);margin:10px 0 0}
.sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
</style>
</head>
<body>
<main>
$body
</main>
</body>
</html>
"""
}
