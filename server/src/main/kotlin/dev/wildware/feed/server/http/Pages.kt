package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route

/**
 * The few pages the server renders itself until phase 5 serves the real website. They keep the
 * routes, statuses and redirects right; their markup is deliberately plain.
 */
object Pages {
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(title: String, body: String) =
        """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(title)}</title></head><body>$body</body></html>"""

    fun login(error: String?) = page(
        "Sign in",
        """<form method="POST"><h1>Sign in</h1>${error?.let { "<p role=\"alert\">${escape(it)}</p>" } ?: ""}""" +
            """<label>Password <input type="password" name="password" autocomplete="current-password" required></label>""" +
            """<button>Sign in</button></form>""",
    )

    fun logout() = page("Sign out", """<form method="POST"><p>Sign out of this browser?</p><button>Sign out</button></form>""")

    fun notFound() = page("Not Found", "<h1>404</h1><p>Not Found</p>")

    fun shell() = page("Wildware Feed", "<main id=\"app\"></main>")
}

fun Route.pageRoutes(app: App) {
    endpoint("/", HttpMethod.Get to { call.respondRaw(200, Pages.shell(), "text/html") })
    endpoint(
        "/projects/{slug}",
        HttpMethod.Get to {
            val slug = call.parameters["slug"]!!
            if (runCatching { app.reads.findProject(slug) }.getOrNull() == null) call.respondRaw(404, Pages.notFound(), "text/html")
            else call.respondRaw(200, Pages.shell(), "text/html")
        },
    )
}
