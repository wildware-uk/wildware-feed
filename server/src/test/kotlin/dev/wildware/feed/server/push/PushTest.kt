package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTest {
    private val apnsConfig = Config.Apns("KEY123", "TEAM456", "/unused.p8", "dev.example.feed")
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
    )
    private val app = App(config, Database.memory(), clock = { 1_700_000_000_000 })

    @AfterTest
    fun close() = app.close()

    @Test
    fun `markdown becomes notification text the way the TS server writes it`() {
        assertEquals("Title\nsome bold and code\n• one\n• two\n1. first\n2. second", PlainText.of("# Title\n\nsome **bold** and `code`\n\n- one\n- two\n\n1. first\n2. second"))
        assertEquals("a\nb", PlainText.of("a  \nb"))
        assertEquals("look: chart", PlainText.of("look: ![chart](x.png)"))
        assertEquals("image", PlainText.of("![](x.png)"))
        assertEquals("val x = 1\nval y = 2", PlainText.of("```\nval x = 1\nval y = 2\n```"))
        assertEquals("<b>hi</b> there", PlainText.of("<b>hi</b> there"))
        assertEquals("quoted", PlainText.of("> quoted"))
        assertEquals("link text", PlainText.of("[link text](https://example.com)"))
        assertEquals("x".repeat(140) + "…", PlainText.notification("x".repeat(141)))
        assertEquals("a b", PlainText.notification("a \t  b"))
    }

    @Test
    fun `byte cuts never split a character`() {
        assertEquals("ab", Apns.truncateBytes("ab", 2))
        assertEquals("a", Apns.truncateBytes("aé", 2))
        assertEquals("", Apns.truncateBytes("😀", 3))
    }

    @Test
    fun `alert headers and payload keep the TS order`() {
        val headers = Apns.alertHeaders("dev.example.feed", "request-01J", 1_700_000_000_999)
        assertEquals(
            listOf("apns-push-type" to "alert", "apns-topic" to "dev.example.feed", "apns-priority" to "10", "apns-expiration" to "1700000900", "apns-collapse-id" to "request-01J"),
            headers,
        )
        val request = Apns.Alert(
            PushMessage("Ada is waiting on you", "Ship it?", "https://dashboard.example.com/", "request-R1"), "REQUEST", "dashboard", null, "Ship it?",
            request = Triple("R1", "confirm", null),
        )
        assertEquals(
            """{"aps":{"alert":{"title":"Ada is waiting on you","body":"Ship it?"},"sound":"question.wav","thread-id":"dashboard","category":"REQUEST","mutable-content":1,"interruption-level":"time-sensitive"},"url":"https://dashboard.example.com/","tag":"request-R1","markdown":"Ship it?","requestId":"R1","requestKind":"confirm","options":null}""",
            Wire.encode(Apns.alertPayload(request)),
        )
        val update = Apns.Alert(
            PushMessage("t", "b", "u", "update-U1"), "UPDATE", "web", "P1", "b", reply = "update" to "U1",
            extra = listOf("agentName" to JsonPrimitive("Ada")),
        )
        assertEquals(
            """{"aps":{"alert":{"title":"t","body":"b"},"sound":"medium-info.wav","thread-id":"web","category":"UPDATE","interruption-level":"active"},"url":"u","tag":"update-U1","markdown":"b","reply":{"update":"U1"},"agentName":"Ada"}""",
            Wire.encode(Apns.alertPayload(update)),
        )
    }

    @Test
    fun `the provider token is an ES256 JWT that verifies and is reused for 50 minutes`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val signer = ProviderToken(apnsConfig) { pem }
        val token = signer.token(1_700_000_000_500)
        val (header, claims, signature) = token.split('.')
        fun text(part: String) = String(Base64.getUrlDecoder().decode(part))
        assertEquals("""{"alg":"ES256","kid":"KEY123"}""", text(header))
        assertEquals("""{"iss":"TEAM456","iat":1700000000}""", text(claims))
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update("$header.$claims".toByteArray()) }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
        assertEquals(token, signer.token(1_700_000_000_500 + ProviderToken.TTL_MS - 1))
        assertTrue(token != signer.token(1_700_000_000_500 + ProviderToken.TTL_MS))
    }

    @Test
    fun `APNS_KEY carries the key itself where there is no file, with escaped newlines allowed`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val env = mapOf(
            "ADMIN_PASSWORD_HASH" to "\$argon2id\$v=19\$m=65536,t=3,p=4\$c2FsdA\$aGFzaA", "SESSION_SECRET" to "s".repeat(32), "TOKEN_SECRET" to "t".repeat(32),
            "APNS_KEY_ID" to "KEY123", "APNS_TEAM_ID" to "TEAM456", "APNS_BUNDLE_ID" to "dev.example.feed",
            "APNS_KEY" to pem.replace("\n", "\\n"),
        )
        val apns = Config.load(env).apns!!
        assertTrue(!apns.toString().contains("PRIVATE"), "the key never shows in logs")
        val token = ProviderToken(apns) { error("no file to read") }.token(1_700_000_000_500)
        val (header, claims, signature) = token.split('.')
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update("$header.$claims".toByteArray()) }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
    }

    @Test
    fun `delivery clears a dead token, counts failures and resets them on success`() {
        app.db.update("INSERT INTO owner_devices (id, name, token_hash, created_at, apns_token, failures) VALUES ('D1', 'Phone', 'h', 1, 'abc', 3)")
        var answer = ApnsResponse(200, null)
        val sent = mutableListOf<ApnsRequest>()
        val apns = Apns(app, apnsConfig) { sent += it; answer }
        fun device() = apns.devices().single()
        fun failures() = app.db.one("SELECT failures FROM owner_devices WHERE id = 'D1'") { it.getInt(1) }
        assertEquals("sent", apns.deliver(device(), "apns_token", emptyList(), "{}"))
        assertEquals(0, failures())
        assertEquals("production", sent.single().environment)
        answer = ApnsResponse(500, null)
        assertEquals("failed", apns.deliver(device(), "apns_token", emptyList(), "{}"))
        assertEquals(1, failures())
        answer = ApnsResponse(400, "BadDeviceToken")
        assertEquals("removed", apns.deliver(device(), "apns_token", emptyList(), "{}"))
        assertNull(device().apnsToken)
        assertEquals("failed", apns.deliver(device(), "apns_token", emptyList(), "{}"))
    }

    @Test
    fun `an update alert names the agent and links to the card`() {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Ada', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        app.db.update("INSERT INTO updates (id, project_id, agent_id, body, level, created_at) VALUES ('U1', 'P1', 'A1', '**Done** with it', 'info', 1)")
        val alert = Apns(app, apnsConfig) { ApnsResponse(200, null) }.alertFor(
            dev.wildware.feed.server.events.AppEvent("update.created", 1, "", kotlinx.serialization.json.buildJsonObject { put("updateId", JsonPrimitive("U1")) }),
        )!!
        assertEquals(PushMessage("Ada posted an update", "Done with it", "https://dashboard.example.com/projects/web?focus=U1", "update-U1"), alert.message)
        assertEquals("web", alert.threadId)
    }

    @Test
    fun `a message that names the owner plays the mention sound`() {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Ada', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        app.db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M1', 'P1', 'agent:A1', '@you have a look', 1)")
        app.db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES ('M2', 'P1', 'agent:A1', 'have a look', 2)")
        val apns = Apns(app, apnsConfig) { ApnsResponse(200, null) }
        fun alert(id: String) = apns.alertFor(
            dev.wildware.feed.server.events.AppEvent("message.created", 1, "", kotlinx.serialization.json.buildJsonObject { put("messageId", JsonPrimitive(id)) }),
        )!!
        assertEquals("mention.wav", Apns.soundFor(alert("M1")))
        assertEquals("message.wav", Apns.soundFor(alert("M2")))
    }

    @Test
    fun `an agent asking to join alerts every phone with its name and code`() {
        val started = app.pairings.start("laptop-personal", "laptop", null, null, null)
        val alert = Apns(app, apnsConfig) { ApnsResponse(200, null) }.alertFor(
            dev.wildware.feed.server.events.AppEvent("pairing.created", 1, "", kotlinx.serialization.json.buildJsonObject { put("pairingId", JsonPrimitive(started.pairing.id)) }),
        )!!
        assertEquals(
            PushMessage(
                "laptop-personal wants to join",
                "From laptop. Code ${started.pairing.code}. Open the app to allow or deny it.",
                "https://dashboard.example.com/",
                "pairing-${started.pairing.id}",
            ),
            alert.message,
        )
        assertEquals("PAIRING", alert.category)
        assertTrue(Wire.encode(Apns.alertPayload(alert)).contains(""""interruption-level":"time-sensitive""""))
        assertTrue(!Wire.encode(Apns.alertPayload(alert)).contains(started.secret))
        // A phone that hears only questions still hears this.
        val onlyQuestions = kotlinx.serialization.json.buildJsonObject { put("types", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("request")))) }
        assertTrue(dev.wildware.feed.server.domain.AlertPrefs.notifies(onlyQuestions, alert.about))
    }

    @Test
    fun `cut counts the ellipsis and never splits a pair`() {
        val cut = dev.wildware.feed.server.domain.Reads.Companion::cut
        assertEquals("short", cut("short", 40))
        assertEquals("abcd\u2026", cut("abcdefgh", 5))
        assertEquals("ab\u2026", cut("ab  cdef", 5), "trailing spaces go before the ellipsis")
        assertEquals("abc\u2026", cut("abc\uD83D\uDE00defg", 5), "a pair at the cut is dropped whole")
        assertEquals("Fix the thing", dev.wildware.feed.server.domain.Reads.firstLine("**Fix   the thing**\nmore detail"))
    }

    @Test
    fun `the Live Activity state carries the top request and what is in progress`() {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Rover', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'garden', 'Garden Game', 1, 1)")
        app.db.update(
            "INSERT INTO approvals (id, agent_id, project_id, question, options, state, expires_at, kind, config) VALUES " +
                "('R1', 'A1', 'P1', 'Which?', '[\"red\",\"green\"]', 'pending', 1700000999999, 'choice', '{\"default\":\"red\"}')",
        )
        app.db.update(
            "INSERT INTO updates (id, project_id, agent_id, title, body, level, status, status_at, created_at) VALUES " +
                "('U1', 'P1', 'A1', NULL, '**Fixing fee rounding** at checkout\nmore', 'info', 'in_progress', 1700000500000, 1)",
        )
        val state = LiveActivity(app, Apns(app, apnsConfig) { ApnsResponse(200, null) }, "dev.example.feed").state()
        assertEquals(
            listOf("online", "waiting", "inProgress", "topAgent", "topQuestion", "topRequestId", "topKind", "topAgentId", "topProject", "topExpiresAt", "topOptions", "topDefault", "working"),
            state.keys.toList(),
        )
        assertEquals(
            """{"online":0,"waiting":1,"inProgress":1,"topAgent":"Rover","topQuestion":"Which?","topRequestId":"R1","topKind":"choice","topAgentId":"A1","topProject":"Garden Game","topExpiresAt":1700000999,"topOptions":["red","green"],"topDefault":"red","working":[{"agentId":"A1","agent":"Rover","task":"Fixing fee rounding at checkout","since":1700000500}]}""",
            Wire.encode(state),
        )
    }

    @Test
    fun `a list default never reaches the Live Activity, which only reads a string`() {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Rover', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'garden', 'Garden Game', 1, 1)")
        app.db.update(
            "INSERT INTO approvals (id, agent_id, project_id, question, options, state, expires_at, kind, config) VALUES " +
                "('R1', 'A1', 'P1', 'Which ones?', '[\"red\",\"green\"]', 'pending', 1700000999999, 'multi_choice', '{\"default\":[\"red\"]}')",
        )
        val state = LiveActivity(app, Apns(app, apnsConfig) { ApnsResponse(200, null) }, "dev.example.feed").state()
        assertEquals(JsonNull, state["topDefault"])
    }

    @Test
    fun `a phone gets only the kinds, levels and priorities it chose`() {
        val about = dev.wildware.feed.server.domain.AlertPrefs
        val updateHigh = dev.wildware.feed.server.domain.AlertPrefs.About("update", "info", "high")
        val updateLow = dev.wildware.feed.server.domain.AlertPrefs.About("update", "warn", "low")
        assertTrue(about.notifies(null, updateLow))
        val prefs = about.check(kotlinx.serialization.json.buildJsonObject {
            put("types", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("update"), JsonPrimitive("request"))))
            put("priorities", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("high"))))
        })!!
        assertTrue(about.notifies(prefs, updateHigh))
        assertTrue(!about.notifies(prefs, updateLow))
        assertTrue(about.notifies(prefs, dev.wildware.feed.server.domain.AlertPrefs.About("request")))
        assertTrue(!about.notifies(prefs, dev.wildware.feed.server.domain.AlertPrefs.About("comment")))
        assertTrue(about.notifies(prefs, dev.wildware.feed.server.domain.AlertPrefs.About("update", "info", null)), "an update with no priority passes the priority filter")
    }

    @Test
    fun `critical pages a phone that turned it on, for the agents and projects it named`() {
        val rules = dev.wildware.feed.server.domain.AlertPrefs
        fun prefs(json: String) = rules.check(kotlinx.serialization.json.Json.parseToJsonElement(json))
        val critical = dev.wildware.feed.server.domain.AlertPrefs.About("update", "error", "critical", agentId = "a1", projectSlug = "fees")
        assertTrue(!rules.pages(null, critical))
        assertTrue(!rules.pages(prefs("""{"critical":false}"""), critical))
        assertTrue(rules.pages(prefs("""{"critical":true}"""), critical))
        assertTrue(!rules.pages(prefs("""{"critical":true}"""), critical.copy(priority = "high")))
        assertTrue(rules.pages(prefs("""{"critical":true,"criticalAgents":["a1"],"criticalProjects":["fees"]}"""), critical))
        assertTrue(!rules.pages(prefs("""{"critical":true,"criticalAgents":["a2"]}"""), critical))
        assertTrue(!rules.pages(prefs("""{"critical":true,"criticalProjects":["docs"]}"""), critical))
        // A page gets past every other filter; critical counts as high for a phone that isn't paged.
        assertTrue(rules.notifies(prefs("""{"types":["request"],"critical":true}"""), critical))
        assertTrue(!rules.notifies(prefs("""{"types":["request"]}"""), critical))
        assertTrue(rules.notifies(prefs("""{"priorities":["high"]}"""), critical))
    }

    @Test
    fun `a critical alert says so, and a paged phone gets it time-sensitive or as Apple's critical alert`() {
        val alert = Apns.Alert(
            PushMessage("Production is down", "Every request fails", "u", "update-U1"), "UPDATE", "web", "P1", "b",
            about = dev.wildware.feed.server.domain.AlertPrefs.About("update", "error", "critical"),
        )
        assertEquals("time-sensitive", Apns.CRITICAL_STYLE)
        assertEquals(
            """{"aps":{"alert":{"title":"Production is down","body":"Every request fails"},"sound":"critical-error.wav","thread-id":"web","category":"UPDATE","interruption-level":"active"},"url":"u","tag":"update-U1","markdown":"b","priority":"critical"}""",
            Wire.encode(Apns.alertPayload(alert)),
        )
        assertEquals(
            """{"aps":{"alert":{"title":"Production is down","body":"Every request fails"},"sound":"critical-error.wav","thread-id":"web","category":"UPDATE","interruption-level":"time-sensitive"},"url":"u","tag":"update-U1","markdown":"b","priority":"critical"}""",
            Wire.encode(Apns.alertPayload(alert, page = Apns.CRITICAL_STYLE)),
        )
        assertEquals(
            """{"critical":1,"name":"critical.wav","volume":1}""",
            Wire.encode((Apns.alertPayload(alert, page = "critical")["aps"] as kotlinx.serialization.json.JsonObject)["sound"]!!),
        )
    }

    @Test
    fun `each alert names the app's own sound for its kind`() {
        fun sound(category: String, about: dev.wildware.feed.server.domain.AlertPrefs.About): String {
            val alert = Apns.Alert(PushMessage("t", "b", "u", "tag"), category, "web", "P1", "b", about = about)
            return Wire.encode((Apns.alertPayload(alert)["aps"] as kotlinx.serialization.json.JsonObject)["sound"]!!)
        }
        val about = dev.wildware.feed.server.domain.AlertPrefs::About
        assertEquals("\"question.wav\"", sound("REQUEST", about("request", null, "medium", null, null)))
        assertEquals("\"critical.wav\"", sound("REQUEST", about("request", null, "critical", null, null)))
        assertEquals("\"permission.wav\"", sound("PAIRING", about("pairing", null, null, null, null)))
        assertEquals("\"message.wav\"", sound("MESSAGE", about("message", null, null, null, null)))
        assertEquals("\"message.wav\"", sound("MESSAGE", about("comment", null, null, null, null)))
        val mention = Apns.Alert(PushMessage("t", "b", "u", "tag"), "MESSAGE", "web", "P1", "b", about = about("message", null, null, null, null), mention = true)
        assertEquals("mention.wav", Apns.soundFor(mention))
        // An update: the grid sound for its priority and level, medium and info when it has none.
        assertEquals("\"high-error.wav\"", sound("UPDATE", about("update", "error", "high", null, null)))
        assertEquals("\"medium-success.wav\"", sound("UPDATE", about("update", "success", null, null, null)))
        assertEquals("\"medium-info.wav\"", sound("UPDATE", about("update", null, null, null, null)))
        assertEquals("\"low-warning.wav\"", sound("UPDATE", about("update", "warn", "low", null, null)))
        assertEquals("\"critical-success.wav\"", sound("UPDATE", about("update", "success", "critical", null, null)))
    }

    @Test
    fun `every update's priority and level has its own grid sound`() {
        val names = buildSet {
            for (priority in listOf("low", "medium", "high", "critical")) {
                for (level in listOf("info", "success", "warn", "error")) add(Apns.gridSound(priority, level))
            }
        }
        assertEquals(16, names.size)
        assertEquals("critical-warning.wav", Apns.gridSound("critical", "warn"))
        assertEquals("medium-info.wav", Apns.gridSound("unheard-of", "unheard-of"))
    }

    @Test
    fun `every sound a push names is in the iPhone app`() {
        val bundled = java.io.File("../iosApp/Sounds").list()!!.toSet()
        val named = buildSet {
            for (priority in listOf(null, "low", "medium", "high", "critical")) {
                for (level in listOf(null, "info", "success", "warn", "error")) add(Apns.gridSound(priority, level))
            }
            for (category in listOf("REQUEST", "PAIRING", "MESSAGE")) {
                for (priority in listOf(null, "critical")) {
                    for (mention in listOf(false, true)) {
                        val about = dev.wildware.feed.server.domain.AlertPrefs.About(category.lowercase(), priority = priority)
                        add(Apns.soundFor(Apns.Alert(PushMessage("t", "b", "u", "tag"), category, "web", "P1", "b", about = about, mention = mention)))
                    }
                }
            }
            add(Apns.CRITICAL_SOUND)
        }
        assertEquals(emptySet(), named - bundled)
        // An older app has only these: a question, a reply and a critical page still play its own.
        assertTrue(bundled.containsAll(listOf("question.wav", "message.wav", "done.wav", "error.wav", "critical.wav")))
    }

    @Test
    fun `clears go to every phone as one quiet background push naming the alerts`() {
        app.db.update("INSERT INTO owner_devices (id, name, token_hash, created_at, apns_token) VALUES ('D1', 'Phone', 'h1', 1, 'abc')")
        app.db.update("INSERT INTO owner_devices (id, name, token_hash, created_at, apns_token) VALUES ('D2', 'Desk', 'h2', 1, NULL)")
        val sent = java.util.concurrent.LinkedBlockingQueue<ApnsRequest>()
        val apns = Apns(app, apnsConfig, clearDelayMs = 50) { sent += it; ApnsResponse(200, null) }
        val stop = apns.start()
        try {
            fun clear(vararg tags: String) = app.bus.publish(
                "notifications.cleared",
                kotlinx.serialization.json.buildJsonObject { put("tags", kotlinx.serialization.json.JsonArray(tags.map(::JsonPrimitive))) },
            )
            clear("update-U1", "message-M1")
            clear("request-R1", "update-U1")
            val push = sent.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("abc", push.deviceToken)
            assertEquals(
                listOf("apns-push-type" to "background", "apns-topic" to "dev.example.feed", "apns-priority" to "5", "apns-expiration" to "1700021600"),
                push.headers,
            )
            assertEquals("""{"aps":{"content-available":1},"clear":["update-U1","message-M1","request-R1"]}""", push.payload)
            Thread.sleep(200)
            assertNull(sent.poll(), "two clears close together are one push, and the phone without a token gets none")
        } finally {
            stop()
        }
    }

    @Test
    fun `a clear never outgrows the payload and never carries an alert`() {
        val tags = (1..100).map { "message-01JABCDEFGHJKMNPQRSTVWXY%02d".format(it) }
        val payload = Wire.encode(Apns.clearPayload(tags))
        assertTrue(payload.toByteArray().size < 4096, "${payload.length} bytes")
        assertTrue(payload.contains(tags.last()) && !payload.contains("\"${tags.first()}\""), "the newest are kept")
        assertTrue(!payload.contains("alert") && !payload.contains("sound"))
    }
}
