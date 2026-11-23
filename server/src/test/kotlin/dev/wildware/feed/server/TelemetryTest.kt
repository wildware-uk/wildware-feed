package dev.wildware.feed.server

import dev.wildware.feed.server.CrashReports.forCrashReports
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.http.endpoint
import dev.wildware.feed.server.http.feed
import dev.wildware.feed.server.http.respondJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import io.sentry.protocol.User
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.StringWriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    @AfterTest
    fun off() {
        Telemetry.stop()
        CrashReports.logger = null
    }

    private fun inMemory(): InMemoryMetricReader {
        val reader = InMemoryMetricReader.create()
        Telemetry.install(SdkMeterProvider.builder().registerMetricReader(reader).build(), null)
        return reader
    }

    private fun Collection<MetricData>.named(name: String) = single { it.name == name }

    @Test
    fun `no OTLP endpoint means nothing is recorded or sent`() {
        assertNull(Telemetry.settings(emptyMap()))
        assertNull(Telemetry.settings(mapOf("OTEL_EXPORTER_OTLP_ENDPOINT" to "  ")))
        assertNull(Telemetry.settings(mapOf("OTEL_EXPORTER_OTLP_ENDPOINT" to "https://otlp.example", "OTEL_SDK_DISABLED" to "true")))
        assertNull(Telemetry.settings(mapOf("OTEL_EXPORTER_OTLP_ENDPOINT" to "otlp.example")))
        assertFalse(Telemetry.start(emptyMap()))
        assertFalse(Telemetry.on)
        // With nothing started, every hook is a no-op rather than a failure.
        Telemetry.request("GET", "/api/server", 200, 1_000)
        Telemetry.statement("SELECT 1", 1_000)
        Telemetry.event(Telemetry.Level.ERROR, "job.failed", "job" to "x")
    }

    @Test
    fun `settings read the standard OTEL variables and never print the token`() {
        val s = Telemetry.settings(
            mapOf(
                "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://otlp.example/otlp/",
                "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Basic%20not-a-real-value,X-Other=1",
                "OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment=staging,host.name=ignored",
                "OTEL_METRIC_EXPORT_INTERVAL" to "500",
                "OTEL_LOGS_EXPORTER" to "none",
            ),
        )
        assertNotNull(s)
        assertEquals("https://otlp.example/otlp", s.endpoint)
        assertEquals("Basic not-a-real-value", s.headers["Authorization"])
        assertEquals("staging", s.environment)
        assertEquals(10_000, s.intervalMs)
        assertFalse(s.logs)
        assertFalse("not-a-real-value" in s.toString())
        // A base64 token keeps its + and = (a form decoder would turn + into a space), with or without %20.
        assertEquals("Basic ab+c/d==", Telemetry.pairs("Authorization=Basic%20ab+c/d==")["Authorization"])
        assertEquals("Basic ab+c/d==", Telemetry.pairs("Authorization=Basic ab+c/d==")["Authorization"])
        assertEquals("production", Telemetry.settings(mapOf("OTEL_EXPORTER_OTLP_ENDPOINT" to "https://otlp.example"))!!.environment)
    }

    @Test
    fun `a request is timed by its route pattern and status class, never its path`() {
        val reader = inMemory()
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application {
                feed(app)
                routing { endpoint("/api/agent/thing/{id}", HttpMethod.Get to { call.respondJson(200, JsonObject(emptyMap())) }) }
            }
            client.get("/api/agent/thing/C42") { header("Authorization", "Bearer $key") }
            client.get("/api/agent/thing/C43") { header("Authorization", "Bearer $key") }
            client.get("/no/such/place")
        }
        app.close()
        val points = reader.collectAllMetrics().named("http.server.request.duration").histogramData.points
        val thing = points.single { it.attributes.asMap().any { (k, v) -> k.key == "http.route" && v == "/api/agent/thing/{id}" } }
        assertEquals(2, thing.count)
        assertEquals("2xx", thing.attributes.asMap().entries.single { it.key.key == "http.response.status_class" }.value)
        assertEquals("GET", thing.attributes.asMap().entries.single { it.key.key == "http.request.method" }.value)
        assertTrue(points.any { p -> p.attributes.asMap().any { (k, v) -> k.key == "http.route" && v == "other" } })
        for (p in points) for ((_, v) in p.attributes.asMap()) assertFalse("C42" in v.toString() || "/no/such" in v.toString(), "a label holds a path: $v")
    }

    @Test
    fun `no metric label is a team id, a name or anything an agent typed`() {
        val reader = inMemory()
        val app = App(config, Database.memory())
        val (agent, _) = app.agents.mint("alpha")
        val typed = "tool-named-after-team-${Tenant.HOME}"
        kotlinx.coroutines.runBlocking {
            app.tools.call(app, agent, JsonObject(mapOf("name" to JsonPrimitive(typed))))
            app.tools.call(app, agent, JsonObject(mapOf("name" to JsonPrimitive("list_projects"), "arguments" to JsonObject(emptyMap()))))
        }
        app.db.query("SELECT id FROM teams") { it.getString(1) }
        Telemetry.push("alert", "sent")
        Telemetry.uploaded("image", 1234)
        Telemetry.stream("agent", 1)
        Telemetry.request("POST", "/api/devices/sign-in", 429, 1_000)
        Telemetry.watch(app)
        val teams = app.db.query("SELECT id FROM teams") { it.getString(1) } + Tenant.HOME
        val metrics = reader.collectAllMetrics()
        app.close()

        val calls = metrics.named("feed.mcp.tool.calls").longSumData.points
        assertTrue(calls.any { p -> p.attributes.asMap().any { (k, v) -> k.key == "mcp.tool" && v == "unknown" } })
        assertTrue(calls.any { p -> p.attributes.asMap().any { (k, v) -> k.key == "mcp.tool" && v == "list_projects" } })
        val signIn = metrics.named("feed.sign_in.attempts").longSumData.points.single()
        assertEquals("blocked", signIn.attributes.asMap().entries.single { it.key.key == "outcome" }.value)
        assertTrue(metrics.named("db.client.operation.duration").histogramData.points.isNotEmpty())
        assertTrue(metrics.named("feed.teams").longGaugeData.points.single().value >= 1)

        for (metric in metrics) {
            for (point in metric.data.points) {
                for ((k, v) in point.attributes.asMap()) {
                    assertTrue(k.key in Telemetry.LABELS, "${metric.name} has a label outside the list: ${k.key}")
                    val value = v.toString()
                    assertFalse(teams.any { it in value } || "alpha" in value || typed in value, "${metric.name} labels ${k.key}=$value")
                }
            }
        }
    }

    @Test
    fun `an event keeps only the attributes on its list`() {
        val sent = mutableListOf<Pair<String, Map<String, Any>>>()
        CrashReports.logger = { _, name, attributes -> sent += name to attributes }
        Telemetry.event(Telemetry.Level.ERROR, "job.failed", "job" to "media sweep", "team" to "T123", "message" to "card text", "error.type" to "IOException")
        val (name, attributes) = sent.single()
        assertEquals("job.failed", name)
        assertEquals(setOf("job", "error.type", "event.name"), attributes.keys)
    }

    @Test
    fun `the traces rate is 5 percent unless set, and nonsense is none`() {
        assertEquals(0.05, CrashReports.tracesRate(emptyMap()))
        assertEquals(0.0, CrashReports.tracesRate(mapOf("SENTRY_TRACES_SAMPLE_RATE" to "0")))
        assertEquals(0.25, CrashReports.tracesRate(mapOf("SENTRY_TRACES_SAMPLE_RATE" to "0.25")))
        assertEquals(0.0, CrashReports.tracesRate(mapOf("SENTRY_TRACES_SAMPLE_RATE" to "lots")))
        assertEquals(0.0, CrashReports.tracesRate(mapOf("SENTRY_TRACES_SAMPLE_RATE" to "2")))
        assertTrue(CrashReports.logsOn(emptyMap()))
        assertFalse(CrashReports.logsOn(mapOf("SENTRY_LOGS" to "off")))
        val options = SentryOptions().apply { forCrashReports("https://key@sentry.example/1", "production", 0.05, true) }
        assertEquals(0.05, options.tracesSampleRate)
        assertTrue(options.logs.isEnabled)
        assertNotNull(options.beforeSendTransaction)
        assertNull(Tracing.startRequest("GET"), "no DSN started, so nothing is traced")
    }

    @Test
    fun `a trace loses the request, cookies, auth, address and span data`() {
        val secret = "Bearer abcdef1234567890abcdef"
        val span = SentrySpan(
            1.0, 2.0, SentryId(), io.sentry.SpanId(), null, "db.sql.query", Tracing.describe("SELECT * FROM cards WHERE body = 'launch plans' AND n = 42"),
            io.sentry.SpanStatus.OK, null, emptyMap(), emptyMap(), mutableMapOf<String, Any>("db.values" to "launch plans"),
        )
        val tx = SentryTransaction("GET /api/cards/{id}", 1.0, 2.0, mutableListOf(span), mutableMapOf(), TransactionInfo("route")).apply {
            request = Request().apply {
                url = "https://feed.example/api/cards/C1"
                headers = mapOf("Authorization" to secret, "Cookie" to "owner=abcdef1234567890abcdef")
                cookies = "owner=abcdef1234567890abcdef"
                data = """{"body":"launch plans"}"""
            }
            user = User().apply { id = "owner"; ipAddress = "203.0.113.9" }
            serverName = "dyno-host"
            setExtra("body", "launch plans")
            setTag("http.method", "GET")
            setTag("team", "T123")
            breadcrumbs = mutableListOf(Breadcrumb("launch plans"))
        }
        val sent = CrashReports.scrubTransaction(tx)
        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()
        assertTrue("GET /api/cards/{id}" in json)
        assertTrue("SELECT * FROM cards WHERE body = ? AND n = ?" in json)
        for (leak in listOf("launch plans", "abcdef1234567890abcdef", "feed.example", "203.0.113.9", "dyno-host", "T123", "\"owner\"")) {
            assertFalse(leak in json, "the trace still carries $leak: $json")
        }
    }

    @Test
    fun `a Sentry log loses the machine and the person`() {
        val log = SentryLogEvent(SentryId(), 1.0, "job.failed", SentryLogLevel.ERROR).apply {
            setAttribute("job", SentryLogEventAttributeValue("string", "media sweep"))
            setAttribute("sentry.environment", SentryLogEventAttributeValue("string", "production"))
            setAttribute("server.address", SentryLogEventAttributeValue("string", "dyno-host"))
            setAttribute("user.email", SentryLogEventAttributeValue("string", "someone@example.com"))
            setAttribute("sentry.message.parameter.0", SentryLogEventAttributeValue("string", "card text"))
        }
        val kept = CrashReports.scrubLog(log).attributes!!.keys
        assertEquals(setOf("job", "sentry.environment"), kept)
    }

    @Test
    fun `a statement is labelled by its first word only`() {
        assertEquals("SELECT", Telemetry.operation("  select id FROM teams"))
        assertEquals("UPDATE", Telemetry.operation("UPDATE cards SET body = ?"))
        assertEquals("OTHER", Telemetry.operation("WITH x AS (SELECT 1) SELECT * FROM x"))
        assertEquals("SELECT a FROM t1 WHERE b = ? AND c = ?", Tracing.describe("SELECT a\n  FROM t1 WHERE b = 'it''s' AND c = 3.5"))
    }
}
