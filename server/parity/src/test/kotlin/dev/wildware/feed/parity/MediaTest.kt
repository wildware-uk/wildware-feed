package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Uploads, derivatives, serving, agent fetch and share links. Spec: server/spec/07-media-uploads-shares.md. */
class MediaTest : ParityTest() {
    private val project by lazy { kotlinx.coroutines.runBlocking { Fixtures.project() } }

    /** Upload, attach to a fresh update, and wait until the pipeline has finished with it. */
    private suspend fun readyOnUpdate(bytes: ByteArray, filename: String, mime: String): Pair<String, JsonObject> {
        val media = Media.upload(alpha, filename, mime, bytes)
        val update = Fixtures.update(project, extra = obj("media_ids" to arr(media)))["id"].s
        val card = eventually(timeout = 60.seconds, what = "media $media to be processed") {
            val item = owner.get("/api/snapshot/updates?project=${project.slug}").obj()["updates"]["items"].arr.single { it["id"].s == update }
            item["media"].arr.single().o.takeIf { it["status"].s != "pending" }
        }
        return update to card
    }

    private suspend fun HttpResponse.expectError(status: Int, error: String, message: String) {
        val text = bodyAsText()
        assertEquals(status, this.status.value, text)
        assertEquals("""{"error":"$error","message":${obj("m" to message)["m"]}}""", text)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `1 a PNG goes up, is processed, and comes back with thumbnails`() = parity {
        val bytes = Media.png(1800, 1200)
        val reserved = Media.reserve(alpha, "a.png", "image/png", bytes.size)
        val put = Media.put(reserved["upload_url"].s, bytes, "image/png")
        assertEquals(201, put.status.value)
        assertEquals("no-store", put.headers[HttpHeaders.CacheControl])
        val body = put.obj()
        assertEquals(listOf("media_id", "kind", "mime", "bytes", "sha256", "status", "deduped"), body.keys.toList())
        assertEquals(reserved["media_id"], body["media_id"])
        assertEquals("image", body["kind"].s)
        assertEquals(bytes.size, body["bytes"].int)
        assertEquals(sha256(bytes), body["sha256"].s)
        assertEquals("pending", body["status"].s)
        assertEquals(false, body["deduped"].bool)

        // 2: the token is single use.
        Media.put(reserved["upload_url"].s, bytes, "image/png").expectError(403, "token_rejected",
            "this upload token is not valid: it may be expired, already used, or not one of ours. Call create_upload again for a fresh one.")

        val id = body["media_id"].s
        val update = Fixtures.update(project)["id"].s
        alpha.ok("attach_media", obj("update_id" to update, "media_ids" to arr(id)))
        val card = eventually(timeout = 60.seconds, what = "the PNG to be ready") {
            owner.get("/api/snapshot/updates?project=${project.slug}").obj()["updates"]["items"].arr
                .single { it["id"].s == update }["media"].arr.single().o.takeIf { it["status"].s == "ready" }
        }
        assertEquals(arr("original", "thumb-640", "thumb-1600"), card["variants"])
        assertEquals(1800, card["width"].int)
        assertEquals(1200, card["height"].int)
        assertEquals(
            listOf("id", "updateId", "messageId", "requestId", "taskId", "kind", "mime", "status", "bytes", "width", "height", "durationMs", "filename", "variants"),
            card.keys.toList(),
        )

        val thumb = owner.get("/media/$id/thumb-640")
        assertEquals(200, thumb.status.value)
        assertEquals("image/webp", thumb.headers[HttpHeaders.ContentType])
        val original = owner.get("/media/$id/original")
        assertContentEquals(bytes, original.bodyAsBytes())
    }

    @Test
    fun `3 to 6 refused uploads, and which ones spend the token`() = parity {
        val png = Media.png(40, 30)

        // No body: not spent.
        val reserved = Media.reserve(alpha, "b.png", "image/png", png.size)
        val noBody = Media.put(reserved["upload_url"].s, null, null)
        assertEquals(400, noBody.status.value)
        assertEquals("invalid_argument", noBody.obj()["error"].s)
        assertTrue(noBody.obj()["message"].s.startsWith("the request had no body."))
        // A text/plain PUT from nowhere is stopped by the website's CSRF rule, not spent either.
        if (Parity.web) {
            val crossSite = Media.put(reserved["upload_url"].s, png, "text/plain")
            assertEquals(403, crossSite.status.value)
            assertEquals("Cross-site PUT form submissions are forbidden", crossSite.bodyAsText())
        }
        assertEquals(201, Media.put(reserved["upload_url"].s, png, "image/png").status.value)

        // Too big: refused, and (decided fix 7) a refused upload does not spend the link.
        val small = Media.reserve(alpha, "c.png", "image/png", 10)
        Media.put(small["upload_url"].s, ByteArray(11), "image/png").let {
            assertEquals(413, it.status.value)
            assertEquals("too_large", it.obj()["error"].s)
        }
        val tiny = Media.reserve(alpha, "c2.png", "image/png", png.size)
        assertEquals(413, Media.put(tiny["upload_url"].s, png + ByteArray(1), "image/png").status.value)
        assertEquals(201, Media.put(tiny["upload_url"].s, png, "image/png").status.value, "the link survives a 413")
        val typed = Media.reserve(alpha, "c3.png", "image/png", png.size)
        assertEquals(415, Media.put(typed["upload_url"].s, "plain text".toByteArray(), "image/png").status.value)
        assertEquals(201, Media.put(typed["upload_url"].s, png, "image/png").status.value, "the link survives a 415")

        // Wrong bytes for the reservation.
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(200)
        val wrong = Media.reserve(alpha, "d.png", "image/png", zip.size)
        Media.put(wrong["upload_url"].s, zip, "image/png").let {
            assertEquals(415, it.status.value)
            assertTrue(it.obj()["message"].s.startsWith("these bytes are \"application/zip\", and this upload was reserved for image/png."))
        }
        val text = "plain text".toByteArray()
        val unknown = Media.reserve(alpha, "e.png", "image/png", text.size)
        Media.put(unknown["upload_url"].s, text, "image/png").let {
            assertEquals(415, it.status.value)
            assertTrue(it.obj()["message"].s.startsWith("these bytes are of no recognised type, and this upload was reserved for image/png."))
        }
    }

    @Test
    fun `7 a file is stored as is and downloaded as an attachment`() = parity {
        val bytes = "hello, file".toByteArray()
        val reserved = Media.reserve(alpha, "ü \"x\".txt", "text/plain", bytes.size)
        val put = Media.put(reserved["upload_url"].s, bytes, "application/octet-stream")
        assertEquals(201, put.status.value)
        assertEquals("file", put.obj()["kind"].s)
        val id = reserved["media_id"].s
        alpha.ok("attach_media", obj("update_id" to Fixtures.update(project)["id"].s, "media_ids" to arr(id)))
        val got = owner.get("/media/$id/original")
        assertEquals(200, got.status.value)
        assertEquals("application/octet-stream", got.headers[HttpHeaders.ContentType])
        assertEquals("attachment; filename=\"_ _x_.txt\"; filename*=UTF-8''%C3%BC%20%22x%22.txt", got.headers[HttpHeaders.ContentDisposition])
        assertEquals(404, owner.get("/media/$id/thumb-640").status.value)
    }

    @Test
    fun `8 the same bytes twice are deduplicated`() = parity {
        val bytes = Media.png(64, 64)
        val first = Media.reserve(alpha, "f.png", "image/png", bytes.size)
        assertEquals(false, Media.put(first["upload_url"].s, bytes, "image/png").obj()["deduped"].bool)
        val second = Media.reserve(alpha, "g.png", "image/png", bytes.size)
        assertEquals(true, Media.put(second["upload_url"].s, bytes, "image/png").obj()["deduped"].bool)
    }

    @Test
    fun `9 to 12 serving - headers, conditional requests, ranges, misses`() = parity {
        val bytes = Media.png(320, 200)
        val (_, card) = readyOnUpdate(bytes, "h.png", "image/png")
        val id = card["id"].s
        val etag = "\"${sha256(bytes)}-original\""

        val got = owner.get("/media/$id/original")
        assertEquals(200, got.status.value)
        val h = got.headers
        assertEquals("image/png", h[HttpHeaders.ContentType])
        assertEquals(bytes.size.toString(), h[HttpHeaders.ContentLength])
        // The TS server says public; the Kotlin server says private, so no shared cache in front keeps it.
        assertTrue(h[HttpHeaders.CacheControl] in setOf("public, max-age=31536000, immutable", "private, max-age=31536000, immutable"), h[HttpHeaders.CacheControl])
        assertEquals("nosniff", h["x-content-type-options"])
        assertEquals("inline", h[HttpHeaders.ContentDisposition])
        assertEquals("default-src 'none'; sandbox", h["content-security-policy"])
        assertEquals("bytes", h[HttpHeaders.AcceptRanges])
        assertEquals(etag, h[HttpHeaders.ETag])

        for (candidate in listOf(etag, "W/$etag", "*", "\"nope\", $etag")) {
            val cached = owner.get("/media/$id/original") { header(HttpHeaders.IfNoneMatch, candidate) }
            assertEquals(304, cached.status.value, candidate)
            assertNull(cached.headers[HttpHeaders.ContentLength])
        }

        suspend fun range(value: String) = owner.get("/media/$id/original") { header(HttpHeaders.Range, value) }
        val size = bytes.size
        range("bytes=0-9").let {
            assertEquals(206, it.status.value)
            assertEquals("bytes 0-9/$size", it.headers[HttpHeaders.ContentRange])
            assertContentEquals(bytes.copyOfRange(0, 10), it.bodyAsBytes())
        }
        range("bytes=5-").let { assertEquals(206, it.status.value); assertContentEquals(bytes.copyOfRange(5, size), it.bodyAsBytes()) }
        range("bytes=-4").let { assertEquals(206, it.status.value); assertContentEquals(bytes.copyOfRange(size - 4, size), it.bodyAsBytes()) }
        for (bad in listOf("bytes=$size-", "bytes=5-2")) {
            val r = range(bad)
            assertEquals(416, r.status.value, bad)
            assertEquals("bytes */$size", r.headers[HttpHeaders.ContentRange])
        }
        for (whole in listOf("bytes=0-1,4-5", "Bytes=0-1")) assertEquals(200, range(whole).status.value, whole)

        for (path in listOf("/media/not-a-ulid/original", "/media/01ZZZZZZZZZZZZZZZZZZZZZZZZ/original", "/media/$id/huge")) {
            val miss = owner.get(path)
            assertEquals(404, miss.status.value, path)
            assertEquals("""{"error":"not_found"}""", miss.bodyAsText())
            assertEquals("no-store", miss.headers[HttpHeaders.CacheControl])
        }

        val anonymous = call(HttpMethod.Get, "/media/$id/original")
        if (Parity.web) {
            assertEquals(303, anonymous.status.value)
            assertEquals("/login?redirectTo=%2Fmedia%2F$id%2Foriginal", anonymous.headers[HttpHeaders.Location])
        } else {
            assertEquals(401, anonymous.status.value)
        }
        assertEquals(200, Device.signIn().get("/media/$id/original").status.value, "a device key may fetch media")
    }

    @Test
    fun `13 an agent fetches attached media, and every refusal is a 404`() = parity {
        val (_, card) = readyOnUpdate(Media.png(50, 50), "i.png", "image/png")
        val id = card["id"].s
        val got = call(HttpMethod.Get, "/api/agent/media/$id") { bearer(beta.token) }
        assertEquals(200, got.status.value)
        assertEquals("inline", got.headers[HttpHeaders.ContentDisposition])

        val loose = Media.uploadPng(alpha, 20, 20)
        assertEquals(404, call(HttpMethod.Get, "/api/agent/media/$loose") { bearer(alpha.token) }.status.value, "unattached")
        val anonymous = call(HttpMethod.Get, "/api/agent/media/$id")
        assertEquals(404, anonymous.status.value)
        assertEquals("""{"error":"not_found"}""", anonymous.bodyAsText())
    }

    @Test
    fun `14 a video gets a poster, a mov also gets an mp4`() = parity {
        assumeTrue(Media.ffmpegAvailable, "needs ffmpeg to make test clips")
        val (_, mp4) = readyOnUpdate(Media.clip("mp4", 1.0), "clip.mp4", "video/mp4")
        assertEquals("ready", mp4["status"].s)
        assertEquals("video", mp4["kind"].s)
        assertEquals(arr("original", "poster"), mp4["variants"])
        assertTrue(mp4["durationMs"].int in 900..1100, "duration ${mp4["durationMs"]}")
        val poster = owner.get("/media/${mp4["id"].s}/poster")
        assertEquals("image/jpeg", poster.headers[HttpHeaders.ContentType])

        val (_, mov) = readyOnUpdate(Media.clip("mov", 0.5), "clip.mov", "video/quicktime")
        assertEquals("ready", mov["status"].s)
        assertTrue(mov["variants"].arr.map { it.s }.containsAll(listOf("poster", "video")), mov["variants"].toString())
        assertEquals("video/mp4", owner.get("/media/${mov["id"].s}/video").headers[HttpHeaders.ContentType])
    }

    @Test
    fun `15 an image that cannot be decoded fails, and nothing of it is served`() = parity {
        val good = Media.png(30, 30)
        val corrupt = good.copyOfRange(0, 33) + ByteArray(400) { 7 }
        val (_, card) = readyOnUpdate(corrupt, "bad.png", "image/png")
        assertEquals("failed", card["status"].s)
        assertEquals(arr(), card["variants"])
        assertEquals(404, owner.get("/media/${card["id"].s}/original").status.value)
    }

    @Test
    @Tag("tsOnly")
    fun `16 share links show one update and its media to anyone holding the link`() = parity {
        val (update, card) = readyOnUpdate(Media.png(200, 100), "share.png", "image/png")
        val minted = owner.post("/api/updates/$update/share")
        assertEquals(200, minted.status.value)
        val share = minted.obj()
        assertEquals(listOf("url", "share"), share.keys.toList())
        assertEquals(listOf("id", "update_id", "created_at"), share["share"].o.keys.toList())
        val url = share["url"].s
        assertTrue(Regex("^${Regex.escape(Parity.baseUrl)}/s/[A-Za-z0-9_-]{43}$").matches(url), url)
        val path = url.removePrefix(Parity.baseUrl)

        val page = call(HttpMethod.Get, path)
        assertEquals(200, page.status.value)
        assertEquals("noindex, nofollow", page.headers["x-robots-tag"])
        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
        assertTrue(page.bodyAsText().contains("<meta name=\"robots\" content=\"noindex, nofollow\"/>"))
        call(HttpMethod.Get, path)
        val views = owner.get("/api/snapshot/updates?project=${project.slug}").obj()["updates"]["items"].arr.single { it["id"].s == update }["share"]
        assertEquals(2, views["views"].int)

        assertEquals(200, call(HttpMethod.Get, "$path/media/${card["id"].s}/original").status.value)
        val other = Media.uploadPng(alpha, 20, 20)
        alpha.ok("attach_media", obj("update_id" to Fixtures.update(project)["id"].s, "media_ids" to arr(other)))
        assertEquals(404, call(HttpMethod.Get, "$path/media/$other/original").status.value, "another update's media")

        val second = owner.post("/api/updates/$update/share").obj()["url"].s.removePrefix(Parity.baseUrl)
        assertEquals(404, call(HttpMethod.Get, path).status.value, "re-sharing revokes the old link")
        assertEquals(200, call(HttpMethod.Get, second).status.value)

        assertEquals("""{"revoked":true}""", owner.delete("/api/updates/$update/share").bodyAsText())
        assertEquals(404, call(HttpMethod.Get, second).status.value)
        assertEquals(404, call(HttpMethod.Get, "$second/media/${card["id"].s}/original").status.value)
        assertEquals("""{"revoked":false}""", owner.delete("/api/updates/$update/share").bodyAsText())
        assertEquals("""{"revoked":false}""", owner.delete("/api/updates/01ZZZZZZZZZZZZZZZZZZZZZZZZ/share").bodyAsText())

        owner.delete("/api/updates/$update")
        assertEquals(404, owner.post("/api/updates/$update/share").status.value)
    }

    @Test
    fun `an agent's get_messages carries the owner's attachments inline`() = parity {
        val here = Fixtures.project()
        Fixtures.update(here) // alpha works here, so the owner's message reaches it
        suspend fun ownerUpload(name: String, type: ContentType, bytes: ByteArray): String =
            owner.send(HttpMethod.Post, "/api/media?filename=$name") { setBody(ByteArrayContent(bytes, type)) }.obj()["media"]["id"].s
        val png = Media.png(300, 150)
        val json = "{\"a\": 1}\n".toByteArray()
        val pdf = "%PDF-1.4\n%\u00e2\u00e3\n1 0 obj\n<<>>\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val image = ownerUpload("chart.png", ContentType.Image.PNG, png)
        val notes = ownerUpload("notes.json", ContentType.Application.Json, json)
        val report = ownerUpload("report.pdf", ContentType.Application.Pdf, pdf)
        val message = owner.post("/api/messages", obj("project" to here.slug, "body" to "see these", "mediaIds" to arr(image, notes, report)))
            .obj()["message"]["id"].s

        val read = eventually(timeout = 60.seconds, what = "the picture to be ready") {
            alpha.tool("get_messages", obj("project" to here.slug, "mark_read" to false))
                .takeIf { r -> r.structured["messages"].arr.single { it["id"].s == message }["attachments"].arr.all { it["status"].s == "ready" } }
        }
        val base = Parity.baseUrl.trimEnd('/')
        fun view(id: String, kind: String, mime: String, bytes: Int, filename: String?) = obj(
            "media_id" to id, "kind" to kind, "mime" to mime, "bytes" to bytes, "filename" to filename, "status" to "ready",
            "included" to true, "download_url" to "$base/api/agent/media/$id",
        )
        assertEquals(
            arr(view(image, "image", "image/png", png.size, null), view(notes, "file", "application/json", json.size, "notes.json"), view(report, "file", "application/pdf", pdf.size, "report.pdf")),
            read.structured["messages"].arr.single { it["id"].s == message }["attachments"],
        )
        val content = read.raw["content"].arr
        assertEquals(4, content.size, "the text, then one block per attachment")
        assertTrue(
            content[0]["text"].s.contains("\nmessage $message: 3 attachments are included below ($image, notes.json (application/json), report.pdf (application/pdf)).\n"),
            content[0]["text"].s,
        )
        assertEquals("image", content[1]["type"].s)
        assertEquals("image/webp", content[1]["mimeType"].s, "the largest thumbnail that fits")
        val webp = java.util.Base64.getDecoder().decode(content[1]["data"].s)
        assertEquals("WEBP", String(webp, 8, 4, Charsets.ISO_8859_1))
        assertEquals(
            obj("type" to "text", "text" to "Attached file \"notes.json\" (application/json, ${json.size} bytes):\n<<<FILE\n{\"a\": 1}\n\nFILE>>>"),
            content[2],
        )
        assertEquals(
            obj("type" to "resource", "resource" to obj("uri" to "attachment:$report/report.pdf", "mimeType" to "application/pdf", "blob" to java.util.Base64.getEncoder().encodeToString(pdf))),
            content[3],
        )
    }

    @Test
    fun `18 the owner uploads straight from the browser`() = parity {
        val bytes = Media.png(60, 40)
        val response = owner.send(HttpMethod.Post, "/api/media?filename=a.png") {
            setBody(ByteArrayContent(bytes, ContentType.Image.PNG))
        }
        assertEquals(201, response.status.value)
        val media = response.obj()["media"].o
        assertEquals(
            listOf("seq", "id", "agentId", "author", "updateId", "messageId", "requestId", "taskId", "kind", "mime", "bytes", "sha256", "width", "height", "durationMs", "filename", "status", "createdAt"),
            media.keys.toList(),
        )
        assertEquals("human", media["author"].s)
        assertTrue(media["agentId"].isNull)
        assertTrue(media["filename"].isNull, "a filename is kept only for kind file")

        // The owner can then post it on a message.
        val posted = owner.post("/api/messages", obj("project" to project.slug, "body" to "look", "mediaIds" to arr(media["id"].s)))
        assertEquals(201, posted.status.value)

        val empty = owner.send(HttpMethod.Post, "/api/media?filename=a.png")
        empty.expectError(400, "invalid_argument", "the request carried no body")
        assertTrue(ImageIO.read(ByteArrayInputStream(bytes)) != null)
    }
}
