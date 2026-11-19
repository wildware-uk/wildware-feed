package dev.wildware.feed.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.io.File

/**
 * The web app: the Compose app built for the browser (composeApp's `wasmJsBrowserDistribution`),
 * served at the site's root from [dir]. Every path that isn't the API, `/mcp` or `/media` is the
 * app's: a file of the bundle, or else `index.html`, so a link like `/projects/web` opens the app.
 *
 * The bundle is public, as any website's code is: it holds nothing of the owner's. The app signs
 * in with the owner password and from then on rides on an HttpOnly cookie (see [OwnerCookie]).
 */
class WebApp(val dir: File) {
    private val root = dir.canonicalFile
    private val index = File(root, "index.html")

    /** Whether there is a bundle to serve. */
    val present: Boolean get() = index.isFile

    /** Answers a GET or HEAD for [ApplicationCall.request]'s path. */
    suspend fun serve(call: ApplicationCall) {
        val method = call.request.httpMethod
        if (method != HttpMethod.Get && method != HttpMethod.Head) {
            call.respondRaw(405, "${method.value} method not allowed", "text/plain;charset=utf-8", "Allow" to "GET, HEAD")
            return
        }
        val path = call.request.path()
        val file = resolve(path)
        when {
            file != null -> send(call, file)
            // A file the bundle doesn't have: a plain 404, not the page, so a missing script fails loudly.
            looksLikeAFile(path) -> call.respondRaw(404, "Not Found", "text/plain;charset=utf-8")
            else -> send(call, index)
        }
    }

    /** The bundle's file for [path], never one outside it. */
    private fun resolve(path: String): File? {
        val relative = path.trimStart('/').ifEmpty { "index.html" }
        val file = File(root, relative).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        // The precompressed copies are served by [send], never asked for by name.
        if (file.name.endsWith(".gz")) return null
        return file
    }

    private fun looksLikeAFile(path: String) = path.substringAfterLast('/').contains('.')

    private suspend fun send(call: ApplicationCall, file: File) {
        val type = contentTypeOf(file.name)
        val gzip = File(file.path + ".gz")
        val acceptsGzip = call.request.headers[HttpHeaders.AcceptEncoding]?.split(',')
            ?.any { it.substringBefore(';').trim().equals("gzip", ignoreCase = true) } == true
        val zipped = acceptsGzip && gzip.isFile
        // Each encoding is its own representation, so each has its own tag.
        val tag = "\"${file.length().toString(36)}-${file.lastModified().toString(36)}${if (zipped) "-gz" else ""}\""
        call.response.header(HttpHeaders.CacheControl, cacheControlOf(file.name))
        call.response.header(HttpHeaders.ETag, tag)
        call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
        call.response.header("X-Content-Type-Options", "nosniff")
        if (file == index) {
            call.response.header("Referrer-Policy", "same-origin")
            // The app holds the owner's session: no other site may frame it.
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Content-Security-Policy", "frame-ancestors 'none'")
        }
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == tag } == true) {
            call.respond(HttpStatusCode.NotModified)
            return
        }
        if (zipped) {
            call.response.header(HttpHeaders.ContentEncoding, "gzip")
            call.respond(LocalFileContent(gzip, type))
        } else {
            call.respond(LocalFileContent(file, type))
        }
    }

    companion object {
        /**
         * The `web` folder beside the installed server's `lib` folder, where `:server:installDist`
         * puts the bundle, when there is one there.
         */
        fun besideInstall(): String? {
            val jar = runCatching { File(WebApp::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
            val web = jar.parentFile?.parentFile?.let { File(it, "web") } ?: return null
            return web.path.takeIf { File(web, "index.html").isFile }
        }

        fun contentTypeOf(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
            "js", "mjs" -> ContentType("text", "javascript").withCharset(Charsets.UTF_8)
            "wasm" -> ContentType("application", "wasm")
            "json", "map" -> ContentType.Application.Json
            "css" -> ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            "png" -> ContentType.Image.PNG
            "svg" -> ContentType.Image.SVG
            "ico" -> ContentType("image", "x-icon")
            "ttf" -> ContentType("font", "ttf")
            "otf" -> ContentType("font", "otf")
            "woff2" -> ContentType("font", "woff2")
            "txt" -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
            else -> ContentType.Application.OctetStream
        }

        /**
         * The compiled code's names are hashes of what is in them, so they are kept for a year. The
         * page, its loader script and the fonts keep their names from build to build, so a browser
         * checks those each time (a 304 when nothing changed), and a new release shows on the next load.
         */
        fun cacheControlOf(name: String): String =
            if (Regex("^[0-9a-f]{16,}\\.wasm$").matches(name)) "public, max-age=31536000, immutable" else "no-cache"
    }
}
