package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.media.MediaEdge
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media sent from the edge (MEDIA_EDGE_URL): the same checks, then a signed redirect. */
class MediaEdgeTest {
    private val secret = "edge-secret-for-tests-".padEnd(40, 'x')
    private var now = 1_800_000_000_000L

    private fun config(dataDir: String, edge: Boolean) = Config(
        dataDir = dataDir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
        mediaEdge = if (edge) Config.MediaEdgeConfig("https://media.example.test", secret) else null,
    )

    private fun png(): ByteArray {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, (Math.random() * 0xFFFFFF).toInt()) }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun withApp(edge: Boolean, block: suspend ApplicationTestBuilder.(App, HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("media-edge").toFile()
        val app = App(config(dir.path, edge), Database.memory()) { now }
        try {
            testApplication {
                application { feed(app) }
                block(app, createClient { followRedirects = false })
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.call(key: String?, path: String, method: HttpMethod = HttpMethod.Get, type: String? = null, body: ByteArray? = null): HttpResponse =
        request(path) {
            this.method = method
            if (key != null) header("Authorization", "Bearer $key")
            if (type != null) header("Content-Type", type)
            if (body != null) setBody(body)
        }

    private suspend fun HttpClient.upload(key: String, name: String = "chart.png", bytes: ByteArray = png()): String {
        val r = call(key, "/api/media?filename=$name", HttpMethod.Post, "image/png", bytes)
        assertEquals(201, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    /** The signed fields of an edge link, checked as the Worker checks them. */
    private class Link(val host: String, val key: String, val exp: Long, val ct: String, val cd: String, val sigOk: Boolean)

    private fun parse(location: String): Link {
        val uri = URI(location)
        val key = uri.rawPath.removePrefix("/").split('/').joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
        val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val exp = q.getValue("exp").toLong()
        val ok = MediaEdge.sign(secret, key, exp, q.getValue("ct"), q.getValue("cd")) == q.getValue("sig")
        return Link(uri.host, key, exp, q.getValue("ct"), q.getValue("cd"), ok)
    }

    @Test
    fun `links agree with the Worker's test vectors`() {
        val file = File("../media-edge/test/vectors.json")
        val vectors = Wire.json.parseToJsonElement(file.readText()).jsonObject
        val vectorSecret = vectors["secret"]!!.jsonPrimitive.content
        val edge = MediaEdge(vectors["edge"]!!.jsonPrimitive.content, vectorSecret)
        val cases = vectors["cases"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (c in cases.map { it.jsonObject }) {
            fun s(k: String) = c[k]!!.jsonPrimitive.content
            val exp = c["exp"]!!.jsonPrimitive.long
            assertEquals(s("sig"), MediaEdge.sign(vectorSecret, s("key"), exp, s("ct"), s("cd")), s("key"))
            assertEquals(s("url"), edge.link(s("key"), s("ct"), s("cd"), exp))
        }
    }

    @Test
    fun `links last until a fixed boundary, at least an hour away`() {
        val day = 1_764_028_800L // a midnight, UTC, in seconds
        assertEquals(day + 6 * 3600, MediaEdge.expiry(day * 1000))
        assertEquals(day + 6 * 3600, MediaEdge.expiry((day + 30 * 60) * 1000), "the same link all morning")
        assertEquals(day + 6 * 3600, MediaEdge.expiry((day + 5 * 3600) * 1000))
        assertEquals(day + 12 * 3600, MediaEdge.expiry((day + 5 * 3600 + 1) * 1000), "under an hour left: the next one")
        assertEquals(6 * 3600L - 600, MediaEdge.redirectMaxAge(day * 1000, day + 6 * 3600))
        assertEquals(0L, MediaEdge.redirectMaxAge((day + 6 * 3600) * 1000, day + 6 * 3600))
    }

    @Test
    fun `the edge needs both settings, a bucket, and a long secret`() {
        val base = mapOf(
            "ADMIN_PASSWORD_HASH" to Passwords.hash("owner password"), "SESSION_SECRET" to "s".repeat(32), "TOKEN_SECRET" to "t".repeat(32),
            "PUBLIC_BASE_URL" to "http://127.0.0.1", "DATA_DIR" to "/tmp/unused",
        )
        val s3 = mapOf("MEDIA_STORE" to "s3", "S3_ENDPOINT" to "https://s3.example.test", "S3_BUCKET" to "b", "S3_ACCESS_KEY_ID" to "k", "S3_SECRET_ACCESS_KEY" to "x")
        assertNull(Config.load(base + s3).mediaEdge, "unset: the server sends the bytes")
        val on = Config.load(base + s3 + mapOf("MEDIA_EDGE_URL" to "https://media.example.test/", "MEDIA_EDGE_SECRET" to secret))
        assertEquals("https://media.example.test", on.mediaEdge?.url)
        assertTrue(secret !in on.toString() && secret !in on.mediaEdge.toString(), "the secret is never printed")
        fun refused(env: Map<String, String>) = assertFailsWith<Config.Invalid> { Config.load(base + env) }
        refused(s3 + mapOf("MEDIA_EDGE_URL" to "https://media.example.test"))
        refused(s3 + mapOf("MEDIA_EDGE_SECRET" to secret))
        refused(s3 + mapOf("MEDIA_EDGE_URL" to "https://media.example.test", "MEDIA_EDGE_SECRET" to "short"))
        refused(s3 + mapOf("MEDIA_EDGE_URL" to "http://media.example.test", "MEDIA_EDGE_SECRET" to secret))
        refused(mapOf("MEDIA_EDGE_URL" to "https://media.example.test", "MEDIA_EDGE_SECRET" to secret))
    }

    @Test
    fun `a file someone may see is a signed redirect to the edge`() = withApp(edge = true) { app, client ->
        val (_, ownerKey) = app.devices.mint("Phone")
        val chart = client.upload(ownerKey)
        val shown = client.call(ownerKey, "/media/$chart/original")
        assertEquals(302, shown.status.value)
        assertEquals("", shown.bodyAsText())
        val link = parse(shown.headers["Location"]!!)
        assertEquals("media.example.test", link.host)
        assertTrue(link.sigOk, "the Worker's check passes")
        assertEquals("${chart.substring(0, 2)}/$chart/original.png", link.key)
        assertEquals("image/png", link.ct)
        assertEquals("inline", link.cd)
        assertEquals(MediaEdge.expiry(now), link.exp)
        val maxAge = Regex("^private, max-age=(\\d+)$").find(shown.headers["Cache-Control"]!!)!!.groupValues[1].toLong()
        assertTrue(maxAge in 1..(link.exp - now / 1000 - 1), "the redirect is kept for less than the link lasts: $maxAge")

        // The same link a while later, so whatever was fetched is reused.
        now += 10 * 60_000
        assertEquals(shown.headers["Location"], client.call(ownerKey, "/media/$chart/original").headers["Location"])

        // `?download=1` signs the download disposition in, so the edge sends it as a file.
        val saved = parse(client.call(ownerKey, "/media/$chart/original?download=1").headers["Location"]!!)
        assertTrue(saved.sigOk)
        assertEquals("attachment; filename=\"image.png\"; filename*=UTF-8''image.png", saved.cd)
        assertEquals("image/png", saved.ct)

        // A file kind is always a download, typed as bytes.
        val notes = client.call(ownerKey, "/api/media?filename=notes.txt", HttpMethod.Post, "text/plain", "the notes".toByteArray())
        val notesId = Wire.json.parseToJsonElement(notes.bodyAsText()).jsonObject["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val file = parse(client.call(ownerKey, "/media/$notesId/original").headers["Location"]!!)
        assertEquals("application/octet-stream", file.ct)
        assertTrue(file.cd.startsWith("attachment; filename=\"notes.txt\""), file.cd)
    }

    @Test
    fun `every refusal is still refused before any redirect`() = withApp(edge = true) { app, client ->
        val (_, ownerKey) = app.devices.mint("Phone")
        val (_, samToken) = app.people.invite(app.people.find(People.FOUNDER)!!, People.Role.MEMBER, "sam@example.test")
        val sam = app.people.accept(samToken, "Sam", "sam@example.test", "password for Sam")
        val samKey = app.devices.mint("Sam phone", sam.id).second
        app.feed.projects.create("Secret", null, null)
        app.feed.projects.create("Web", null, null)

        val chart = client.upload(ownerKey)
        assertEquals(401, client.call(null, "/media/$chart/original").status.value, "nobody signed in")
        assertEquals(401, client.call("not-a-key", "/media/$chart/original").status.value)
        assertEquals(404, client.call(ownerKey, "/media/$chart/thumb-640").status.value, "a variant it doesn't have")
        assertEquals(404, client.call(ownerKey, "/media/$chart/nonsense").status.value)
        assertEquals(404, client.call(ownerKey, "/media/01ARZ3NDEKTSV4RRFFQ69G5FAV/original").status.value, "no such file")

        // On a card in a private channel: only those who can read it.
        val secretPicture = client.upload(ownerKey)
        val inSecret = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "a picture", project = "secret"))
        app.db.update("UPDATE media SET message_id = ? WHERE team_id = :team AND id = ?", inSecret.id, secretPicture)
        assertEquals(200, client.call(ownerKey, "/api/projects/secret/access", HttpMethod.Put, "application/json", """{"private":true}""".toByteArray()).status.value)
        assertEquals(404, client.call(samKey, "/media/$secretPicture/original").status.value, "a channel Sam can't read")
        assertEquals(302, client.call(ownerKey, "/media/$secretPicture/original").status.value)

        // On a deleted card: gone for everyone.
        val deleted = client.upload(ownerKey)
        val inWeb = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "see this", project = "web"))
        app.db.update("UPDATE media SET message_id = ? WHERE team_id = :team AND id = ?", inWeb.id, deleted)
        assertEquals(302, client.call(samKey, "/media/$deleted/original").status.value)
        app.feed.messages.delete(inWeb.id, Messages.HUMAN)
        assertEquals(404, client.call(ownerKey, "/media/$deleted/original").status.value)

        // Removed by retention, or failed: a miss.
        val removed = client.upload(ownerKey)
        app.db.update("UPDATE media SET removed_at = ? WHERE team_id = :team AND id = ?", now, removed)
        assertEquals(404, client.call(ownerKey, "/media/$removed/original").status.value)
        val failed = client.upload(ownerKey)
        app.db.update("UPDATE media SET status = 'failed' WHERE team_id = :team AND id = ?", failed)
        assertEquals(404, client.call(ownerKey, "/media/$failed/original").status.value)
    }

    @Test
    fun `an agent's fetch still comes from the server`() = withApp(edge = true) { app, client ->
        val (_, ownerKey) = app.devices.mint("Phone")
        val (alpha, agentToken) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        Subscriptions(app.db, app.clock).followProject(Subscriptions.agent(alpha.id), app.feed.projects.resolve("web").id, chosen = true)
        val bytes = png()
        val chart = client.upload(ownerKey, bytes = bytes)
        val message = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "see this", project = "web"))
        app.db.update("UPDATE media SET message_id = ? WHERE team_id = :team AND id = ?", message.id, chart)
        val got = client.call(agentToken, "/api/agent/media/$chart")
        assertEquals(200, got.status.value)
        assertContentEquals(bytes, got.readRawBytes())
    }

    @Test
    fun `without the edge the server sends the bytes as before`() = withApp(edge = false) { app, client ->
        val (_, ownerKey) = app.devices.mint("Phone")
        val bytes = png()
        val chart = client.upload(ownerKey, bytes = bytes)
        val shown = client.call(ownerKey, "/media/$chart/original")
        assertEquals(200, shown.status.value)
        assertNull(shown.headers["Location"])
        assertEquals("private, max-age=31536000, immutable", shown.headers["Cache-Control"])
        assertContentEquals(bytes, shown.readRawBytes())
    }
}
