package dev.wildware.feed.parity

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Reads, checked differentially. In record mode (against the TS server) it builds a rich database
 * and saves every read response; in compare mode (against another server opened on the same data
 * directory) it asks the same questions and wants the same answers, byte for byte, apart from the
 * stream position and the clock.
 *
 * PARITY_READS=record|compare, PARITY_READS_FILE=<path>. Skipped when PARITY_READS is unset.
 */
class ReadParityTest : ParityTest() {
    @Serializable
    data class Recorded(val path: String, val status: Int, val contentType: String?, val cacheControl: String?, val body: String)

    private val mode = System.getenv("PARITY_READS")
    private val file get() = File(System.getenv("PARITY_READS_FILE") ?: error("set PARITY_READS_FILE"))

    @Test
    fun `reads answer the same on the same database`() = parity(timeout = kotlin.time.Duration.parse("10m")) {
        assumeTrue(mode == "record" || mode == "compare", "set PARITY_READS=record or compare")
        if (mode == "record") record() else compare()
    }

    private suspend fun record() {
        val paths = seed()
        val recorded = paths.map { path ->
            val r = owner.get(path)
            Recorded(path, r.status.value, r.headers[HttpHeaders.ContentType], r.headers[HttpHeaders.CacheControl], r.bodyAsText())
        }
        file.parentFile?.mkdirs()
        file.writeText(Parity.json.encodeToString(ListSerializer(Recorded.serializer()), recorded))
        println("recorded ${recorded.size} reads to $file")
    }

    private suspend fun compare() {
        val recorded = Parity.json.decodeFromString(ListSerializer(Recorded.serializer()), file.readText())
        val failures = mutableListOf<String>()
        for (want in recorded) {
            val r = owner.get(want.path)
            val got = Recorded(want.path, r.status.value, r.headers[HttpHeaders.ContentType], r.headers[HttpHeaders.CacheControl], r.bodyAsText())
            val problems = buildList {
                if (got.status != want.status) add("status ${got.status}, want ${want.status}")
                if (got.contentType != want.contentType) add("content-type ${got.contentType}, want ${want.contentType}")
                if (got.cacheControl != want.cacheControl) add("cache-control ${got.cacheControl}, want ${want.cacheControl}")
                val a = normalise(got.body)
                val b = normalise(want.body)
                if (a != b) add("body differs at ${a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.length, b.length) else it }}:\n  got  ${excerpt(a, b)}\n  want ${excerpt(b, a)}")
            }
            if (problems.isNotEmpty()) failures += "${want.path}: ${problems.joinToString("; ")}"
        }
        println("compared ${recorded.size} reads, ${failures.size} differ")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    /** The stream position and the clock differ between any two servers; everything else must not. */
    private fun normalise(body: String) = body
        .replaceFirst(Regex("^\\{\"seq\":\\d+,\"at\":\"[^\"]*\""), "{\"seq\":0,\"at\":\"\"")
        .replaceFirst(Regex("^\\{\"at\":\"[^\"]*\""), "{\"at\":\"\"")

    private fun excerpt(s: String, other: String): String {
        val i = s.zip(other).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(s.length, other.length) else it }
        return s.substring(maxOf(0, i - 80), minOf(s.length, i + 120))
    }

    /** Build a database with something of everything, through the TS server's own API. Returns the reads to record. */
    private suspend fun seed(): List<String> {
        val a = alpha
        val b = beta
        val main = Fixtures.project(a, "Main ${Parity.suffix()}")
        val other = Fixtures.project(b, "Other ${Parity.suffix()}")
        val archived = Fixtures.project(a, "Archived ${Parity.suffix()}")
        owner.patch("/api/projects/${archived.slug}", obj("status" to "archived"))
        owner.patch("/api/projects/${main.slug}", obj("pinned" to true, "theme" to obj("accent" to "#abc", "background" to "#101820")))
        owner.patch("/api/projects/${other.slug}", obj("board" to obj("columns" to arr(obj("title" to "Doing", "states" to arr("todo"))))))

        // Updates of every level, with media, embeds and apps.
        val image = Media.uploadPng(a, 1800, 1200)
        // A text file goes up as octet-stream: a text/plain PUT from no origin is refused by the CSRF rule.
        val notes = "plain notes".toByteArray()
        val reserved = Media.reserve(a, "notes.txt", "text/plain", notes.size)
        Media.put(reserved["upload_url"].s, notes, "application/octet-stream")
        val file = reserved["media_id"].s
        val u1 = Fixtures.update(main, a, "first **update**", obj("title" to "One", "level" to "success", "priority" to "high", "media_ids" to arr(image, file)))["id"].s
        val u2 = Fixtures.update(main, a, "second", obj("level" to "warn", "embeds" to arr(obj("type" to "github-comment", "data" to obj("username" to "octo", "body" to "hi", "url" to "https://github.com/x/y/issues/1")))))["id"].s
        val u3 = Fixtures.update(other, b, "on the other project", obj("level" to "error"))["id"].s
        val gone = Fixtures.update(main, a, "deleted soon")["id"].s
        owner.delete("/api/updates/$gone")
        a.ok("edit_update", obj("update_id" to u2, "body" to "second, edited"))
        owner.patch("/api/updates/$u1", obj("pinned" to true))
        val uri = "ui://parity-${Parity.suffix()}/board"
        a.ok("register_app", obj("uri" to uri, "name" to "Board", "html" to "<p>hi</p>"))
        a.ok("post_update", obj("project" to main.slug, "body" to "with an app", "apps" to arr(obj("app" to uri, "data" to obj("n" to 1), "title" to "B"))))
        owner.post("/api/updates/$u1/share")

        // Cards: status, tags, fields, reactions, pins.
        owner.post("/api/cards/update/$u1/status", obj("status" to "in_progress"))
        owner.post("/api/cards/update/$u3/status", obj("status" to "todo"))
        owner.post("/api/cards/update/$u1/tags", obj("tag" to "Urgent", "create" to true))
        owner.post("/api/projects/${main.slug}/tags", obj("name" to "Later", "colour" to "rose"))
        owner.post("/api/cards/update/$u1/fields", obj("name" to "Branch", "value" to "main"))
        owner.post("/api/cards/update/$u2/reactions", obj("emoji" to "👍"))
        b.ok("react", obj("update_id" to u2, "emoji" to "👀"))

        // Messages: card threads, feed posts with replies, answers, a deleted one, acks.
        val m1 = Fixtures.message(a, obj("update_id" to u1, "body" to "on the card"))["id"].s
        val m2 = owner.post("/api/messages", obj("update" to u1, "body" to "owner on the card")).obj()["message"]["id"].s
        a.ok("post_message", obj("message_id" to m2, "body" to "answering the owner"))
        val post = owner.post("/api/messages", obj("project" to main.slug, "body" to "a feed post @${b.name}")).obj()["message"]["id"].s
        owner.post("/api/messages", obj("replyTo" to post, "body" to "a reply"))
        b.ok("post_message", obj("message_id" to post, "body" to "beta replies"))
        val doomed = owner.post("/api/messages", obj("project" to main.slug, "body" to "to delete")).obj()["message"]["id"].s
        owner.delete("/api/messages/$doomed")
        owner.post("/api/messages", obj("body" to "no project at all"))
        a.ok("acknowledge", obj("state" to "thinking", "message_id" to m2))
        owner.post("/api/cards/message/$post/status", obj("status" to "todo"))
        owner.post("/api/messages/$m1/reactions", obj("emoji" to "✅"))

        // Requests: pending of every kind (held calls are let go, the requests stay), and settled ones.
        val picture = Media.uploadPng(a, 300, 200)
        val asks = listOf(
            obj("kind" to "confirm", "question" to "Ship it?", "default" to "true"),
            obj("kind" to "text", "question" to "Name?", "placeholder" to "a name", "min" to 2),
            obj("kind" to "buttons", "question" to "Which?", "options" to arr("A", "B")),
            obj("kind" to "choice", "question" to "Colour?", "options" to arr("Red", "Blue"), "option_media" to obj("Red" to picture)),
            obj("kind" to "multi_choice", "question" to "Some?", "options" to arr("x", "y", "z"), "max" to 2),
            obj("kind" to "form", "question" to "Note?", "options" to arr("Send", "Skip"), "label" to "Note", "update" to u1),
        )
        val scope = CoroutineScope(Dispatchers.IO)
        for (args in asks) {
            scope.launch { withTimeoutOrNull(3.seconds) { a.tool("request_input", kotlinx.serialization.json.JsonObject(args + obj("project" to main.slug, "timeout_s" to 86400))) } }
        }
        val pending = eventually(what = "six pending requests") {
            owner.get("/api/snapshot/requests").obj()["requests"].arr.takeIf { it.size >= 6 }
        }
        val answered = pending.first { it["question"].s == "Which?" }["id"].s
        owner.post("/api/requests/$answered/answer", obj("value" to "B"))
        val dismissed = pending.first { it["question"].s == "Some?" }["id"].s
        owner.delete("/api/requests/$dismissed")
        owner.post("/api/messages", obj("request" to pending.first { it["question"].s == "Ship it?" }["id"].s, "body" to "why?"))

        // Devices and the bell.
        val phone = Device.signIn("Phone")
        phone.send(io.ktor.http.HttpMethod.Put, "/api/devices/current/apns", obj("token" to "abcdef", "environment" to "sandbox"))
        phone.send(io.ktor.http.HttpMethod.Put, "/api/devices/current/alert-projects", obj("projects" to arr(main.slug)))
        val first = owner.get("/api/notifications").obj()["notifications"].arr.last()["id"].s
        owner.post("/api/notifications/seen", obj("ids" to arr(first)))

        // Presence last, so it is still fresh when the other server reads it.
        val session = a.ok("register_session", obj("meta" to obj("host" to "box", "cwd" to "/w", "model" to "m")))["session_id"].s
        a.ok("heartbeat", obj("session_id" to session))

        eventually(timeout = 60.seconds, what = "media to finish processing") {
            owner.get("/api/snapshot?limit=200").obj()["updates"]["items"].arr
                .flatMap { it["media"].arr }.takeIf { all -> all.none { it["status"].s == "pending" } }
        }

        return listOf(
            "/api/summary",
            "/api/snapshot",
            "/api/snapshot?limit=2",
            "/api/snapshot?project=${main.slug}",
            "/api/snapshot?project=${other.id}",
            "/api/snapshot?status=archived",
            "/api/snapshot?cursor=abc",
            "/api/snapshot?project=nope-${Parity.suffix()}",
            "/api/snapshot/updates",
            "/api/snapshot/updates?limit=1",
            "/api/snapshot/requests",
            "/api/snapshot/agents",
            "/api/messages",
            "/api/messages?project=${main.slug}",
            "/api/messages?update=$u1",
            "/api/messages?replyTo=$post",
            "/api/messages?limit=2",
            "/api/messages?limit=0",
            "/api/projects/${main.slug}/tags",
            "/api/projects/nope-x/tags",
            "/api/devices",
            "/api/notifications",
            "/api/notifications?unseen=true",
            "/api/notifications?limit=2",
            "/api/push",
        )
    }
}
