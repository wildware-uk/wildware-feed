package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The website at the level of routes: who gets a page, who is redirected where, and what the
 * public share page promises crawlers. Markup is deliberately not compared — the Kotlin server
 * may render it differently. Spec: server/spec/09-web-frontend.md.
 */
class WebTest : ParityTest() {
    private suspend fun location(path: String, signedIn: Boolean = false) =
        (if (signedIn) owner.get(path) else call(HttpMethod.Get, path)).let { it.status.value to it.headers[HttpHeaders.Location] }

    @Test
    fun `1 to 4 signed out, every page sends you to login with the way back`() = parity {
        assertEquals(303 to "/login", location("/"))
        assertEquals(303 to "/login?redirectTo=%2Fprojects%2Facme", location("/projects/acme"))
        assertEquals(303 to "/login?redirectTo=%2F%3Ffocus%3Dx", location("/?focus=x"))
        assertEquals(303 to "/login?redirectTo=%2Fnope", location("/nope"))
        assertEquals(303 to "/login?redirectTo=%2Fapps%2F01ZZZZZZZZZZZZZZZZZZZZZZZZ", location("/apps/01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun `4 and 5 signed in - pages render, unknown ones 404, trailing slashes redirect`() = parity {
        val home = owner.get("/")
        assertEquals(200, home.status.value)
        assertTrue(home.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))

        val project = Fixtures.project()
        assertEquals(200, owner.get("/projects/${project.slug}").status.value)
        assertEquals(404, owner.get("/projects/nope-${Parity.suffix()}").status.value)
        assertEquals(404, owner.get("/nope").status.value)

        assertEquals(308 to "/projects/acme", location("/projects/acme/"))
        assertEquals(308 to "/projects/acme", location("/projects/acme/", signedIn = true))

        val unknownView = owner.get("/apps/01ZZZZZZZZZZZZZZZZZZZZZZZZ")
        assertEquals(404, unknownView.status.value)
        assertEquals("""{"error":"not_found"}""", unknownView.bodyAsText())
    }

    @Test
    fun `6 the login page sends a signed-in owner on, safely`() = parity {
        assertEquals(303 to "/", location("/login", signedIn = true))
        assertEquals(303 to "/projects/x", location("/login?redirectTo=/projects/x", signedIn = true))
        assertEquals(303 to "/", location("/login?redirectTo=//evil.com", signedIn = true))
        val page = call(HttpMethod.Get, "/login")
        assertEquals(200, page.status.value)
        assertContains(page.bodyAsText(), "password")
    }

    @Test
    fun `7 an empty password is refused without counting`() = parity {
        val empty = Owner.loginForm("")
        assertEquals(400, empty.status.value)
        assertContains(empty.bodyAsText(), "Enter your password.")
        assertEquals(null, Owner.sessionCookie(empty))
    }

    @Test
    fun `9 the logout page offers a form, signed out it sends you to login`() = parity {
        assertEquals(303 to "/login", location("/logout"))
        val page = owner.get("/logout")
        assertEquals(200, page.status.value)
        assertContains(page.bodyAsText().lowercase(), "method=\"post\"")
    }

    @Test
    fun `11 and 12 the share page describes the card for link previews`() = parity {
        val project = Fixtures.project()
        val image = Media.uploadPng(alpha, 1800, 1200)
        val update = Fixtures.update(project, extra = obj("title" to "Shared title", "media_ids" to arr(image)))["id"].s
        eventually(what = "the image to be ready") {
            owner.get("/api/snapshot/updates?project=${project.slug}").obj()["updates"]["items"].arr
                .single { it["id"].s == update }["media"][0].takeIf { it["status"].s == "ready" }
        }
        val url = owner.post("/api/updates/$update/share").obj()["url"].s
        val token = url.substringAfterLast('/')
        val page = call(HttpMethod.Get, "/s/$token")
        assertEquals(200, page.status.value)
        val html = page.bodyAsText()
        fun meta(property: String) = Regex("<meta property=\"$property\" content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
        assertEquals("Shared title", meta("og:title"))
        assertNotNull(meta("og:description"))
        assertEquals(url, meta("og:url"))
        assertEquals("$url/media/$image/thumb-1600", meta("og:image"))

        for (bad in listOf("/s/${"A".repeat(43)}", "/s/short")) {
            val miss = call(HttpMethod.Get, bad)
            assertEquals(404, miss.status.value, bad)
            assertEquals(null, miss.headers[HttpHeaders.Location])
            assertContains(miss.bodyAsText().lowercase(), "not found")
        }
    }

    @Test
    fun `13 built assets are cached for a year, misses are not`() = parity {
        val html = owner.get("/").bodyAsText()
        val asset = Regex("/_app/immutable/[A-Za-z0-9_./-]+\\.js").find(html)?.value ?: error("no immutable asset in the page")
        val got = owner.get(asset) { header(HttpHeaders.AcceptEncoding, "br") }
        assertEquals(200, got.status.value)
        assertEquals("public,max-age=31536000,immutable", got.headers[HttpHeaders.CacheControl])
        assertEquals("br", got.headers[HttpHeaders.ContentEncoding])
        assertEquals(404, call(HttpMethod.Get, "/_app/missing.js").status.value)
        assertEquals(200, call(HttpMethod.Get, "/service-worker.js").status.value)
        assertEquals(200, call(HttpMethod.Get, "/manifest.webmanifest").status.value)
    }

    @Test
    fun `compression - brotli preferred, gzip second, small bodies left alone`() = parity {
        val br = owner.get("/") { header(HttpHeaders.AcceptEncoding, "gzip, br") }
        assertEquals("br", br.headers[HttpHeaders.ContentEncoding])
        assertContains(br.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString(",").lowercase(), "accept-encoding")
        assertEquals("gzip", owner.get("/") { header(HttpHeaders.AcceptEncoding, "br;q=0, gzip") }.headers[HttpHeaders.ContentEncoding])
        assertEquals(null, owner.get("/") { header(HttpHeaders.AcceptEncoding, "*") }.headers[HttpHeaders.ContentEncoding])
        val update = Fixtures.update(Fixtures.project())["id"].s
        val small = owner.send(HttpMethod.Post, "/api/cards/update/$update/pin", obj("pinned" to true)) { header(HttpHeaders.AcceptEncoding, "br") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding], "under 1400 bytes")
    }
}
