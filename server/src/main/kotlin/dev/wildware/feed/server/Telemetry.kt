package dev.wildware.feed.server

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Operational numbers and events, sent over OTLP/HTTP (to Grafana Cloud) only when
 * `OTEL_EXPORTER_OTLP_ENDPOINT` is set. Without it, the default, every call here returns at once
 * and nothing is sent anywhere (server/deploy/OBSERVABILITY.md).
 *
 * What is sent is counts, durations and sizes, labelled only by things the code names: a route's
 * pattern (`/api/cards/{id}`, never its path), a status class, a tool's name, a kind. Never a
 * team's id or name, a person, an address, a card's text, a token or a value from a request.
 * [LABELS] lists every label a metric may carry, and [EVENT_KEYS] every attribute an event may.
 */
object Telemetry {
    /** Every label a metric point may have. Nothing outside this set is ever put on one. */
    val LABELS = setOf(
        "http.request.method", "http.route", "http.response.status_class",
        "stream.kind", "db.operation.name", "mcp.tool", "outcome", "push.kind", "media.kind", "sign_in.route",
        "jvm.memory.type", "jvm.gc.name",
    )

    /** Every attribute an event may carry; [event] drops any other. */
    val EVENT_KEYS = setOf(
        "event.name", "version", "db.system", "multi_team", "job", "error.type", "http.request.method", "http.route",
        "wait_ms", "duration_ms", "db.operation.name", "mcp.tool", "push.kind", "outcome",
    )

    /** The routes where someone signs in with a password: their answers are counted as sign-ins too. */
    val SIGN_IN_ROUTES = setOf("POST /api/devices/sign-in", "POST /oauth/authorize")

    enum class Level(val severity: Severity) { INFO(Severity.INFO), WARN(Severity.WARN), ERROR(Severity.ERROR) }

    /** What [start] read from the environment. */
    data class Settings(
        val endpoint: String,
        val headers: Map<String, String>,
        val environment: String,
        val instance: String?,
        val intervalMs: Long,
        val logs: Boolean,
    ) {
        /** Never the headers' values: they hold the token. */
        override fun toString() = "Settings(endpoint=$endpoint, headers=${headers.keys}, environment=$environment, intervalMs=$intervalMs, logs=$logs)"
    }

    @Volatile private var meters: Meters? = null
    @Volatile private var logger: Logger? = null
    @Volatile private var close: () -> Unit = {}

    /** Whether metrics are being recorded. */
    val on: Boolean get() = meters != null

    /** How many open teams there are, refreshed every few minutes by [watch]; -1 until then. */
    private val teams = AtomicLong(-1)

    /** [Settings] from [env], or null when nothing should be sent. */
    fun settings(env: Map<String, String>): Settings? {
        if (env["OTEL_SDK_DISABLED"]?.trim().equals("true", ignoreCase = true)) return null
        val endpoint = env["OTEL_EXPORTER_OTLP_ENDPOINT"]?.trim()?.trimEnd('/').orEmpty()
        if (endpoint.isEmpty()) return null
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            System.err.println("telemetry: OTEL_EXPORTER_OTLP_ENDPOINT is not an http(s) address; sending nothing")
            return null
        }
        val resource = pairs(env["OTEL_RESOURCE_ATTRIBUTES"].orEmpty())
        val environment = resource["deployment.environment.name"] ?: resource["deployment.environment"]
            ?: env["SENTRY_ENVIRONMENT"]?.trim()?.ifEmpty { null } ?: "production"
        val interval = env["OTEL_METRIC_EXPORT_INTERVAL"]?.trim()?.toLongOrNull()?.coerceAtLeast(10_000) ?: 60_000
        val logs = env["OTEL_LOGS_EXPORTER"]?.trim()?.lowercase() != "none"
        return Settings(endpoint, pairs(env["OTEL_EXPORTER_OTLP_HEADERS"].orEmpty()), environment, env["DYNO"]?.trim()?.ifEmpty { null }, interval, logs)
    }

    /**
     * `a=1,b=2` as OTEL_* variables spell them, `%XX` in values decoded (`Basic%20...`). A `+` stays
     * a `+`, not a space as in a form: a base64 token has them. A value may hold `=` (base64 padding).
     */
    internal fun pairs(text: String): Map<String, String> = text.split(',').mapNotNull { part ->
        val at = part.indexOf('=')
        if (at <= 0) return@mapNotNull null
        val key = part.substring(0, at).trim()
        if (key.isEmpty()) null else key to percentDecoded(part.substring(at + 1).trim())
    }.toMap()

    private fun percentDecoded(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).takeIf { h -> h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }?.toInt(16) else null
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Starts sending if [env] has `OTEL_EXPORTER_OTLP_ENDPOINT`. Returns whether it did. */
    fun start(env: Map<String, String>): Boolean {
        val s = settings(env) ?: return false
        val resource = Resource.create(
            Attributes.builder()
                .put("service.name", "wildware-feed-server")
                .put("service.version", dev.wildware.feed.server.http.ServerInfo.version)
                .put("deployment.environment", s.environment)
                .put("deployment.environment.name", s.environment)
                .apply { s.instance?.let { put("service.instance.id", it) } }
                .build(),
        )
        val metrics = OtlpHttpMetricExporter.builder()
            .setEndpoint("${s.endpoint}/v1/metrics")
            // Metric points are reused between sends rather than made afresh each minute.
            .setMemoryMode(io.opentelemetry.sdk.common.export.MemoryMode.REUSABLE_DATA)
            .setCompression("gzip")
            .setTimeout(Duration.ofSeconds(10))
            .apply { s.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        val meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metrics).setInterval(Duration.ofMillis(s.intervalMs)).build())
            .build()
        val loggerProvider = if (!s.logs) null else SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(
                BatchLogRecordProcessor.builder(
                    OtlpHttpLogRecordExporter.builder()
                        .setEndpoint("${s.endpoint}/v1/logs")
                        .setCompression("gzip")
                        .setTimeout(Duration.ofSeconds(10))
                        .apply { s.headers.forEach { (k, v) -> addHeader(k, v) } }
                        .build(),
                ).setMaxQueueSize(512).setMaxExportBatchSize(128).build(),
            )
            .build()
        install(meterProvider, loggerProvider) {
            meterProvider.shutdown().join(3, TimeUnit.SECONDS)
            loggerProvider?.shutdown()?.join(2, TimeUnit.SECONDS)
        }
        return true
    }

    /** Record into [meterProvider] (and events into [loggerProvider]); tests pass in-memory ones. */
    internal fun install(meterProvider: MeterProvider, loggerProvider: LoggerProvider?, onStop: () -> Unit = {}) {
        val meter = meterProvider.get("dev.wildware.feed.server")
        meters = Meters(meter)
        logger = loggerProvider?.get("dev.wildware.feed.server")
        close = onStop
    }

    /** Send what is waiting and stop. Safe to call when nothing was started, and more than once. */
    fun stop() {
        val done = close
        close = {}
        meters = null
        logger = null
        teams.set(-1)
        runCatching(done)
    }

    /** Count the open teams now and every five minutes (one cheap query), for the `feed.teams` gauge. */
    fun watch(app: App) {
        if (!on) return
        val count = { teams.set(app.db.one("SELECT count(*) FROM teams WHERE state = 'active'") { it.getLong(1) } ?: 0) }
        runCatching(count)
        app.every(5 * 60_000L, "team count", count)
    }

    /** One HTTP request answered with [status] after [nanos]. A stream isn't timed: it lasts as long as its client stays. */
    fun request(method: String, route: String?, status: Int, nanos: Long, stream: Boolean = false) {
        val m = meters ?: return
        val routeLabel = route ?: "other"
        val statusClass = if (status in 100..599) "${status / 100}xx" else "none"
        if (!stream) {
            m.httpDuration.record(nanos / 1e9, Attributes.of(METHOD, method, ROUTE, routeLabel, STATUS_CLASS, statusClass))
        }
        val signIn = "$method $routeLabel"
        if (signIn in SIGN_IN_ROUTES) {
            val outcome = when {
                status in 200..399 -> "ok"
                status == 429 -> "blocked"
                status in 400..499 -> "refused"
                else -> "error"
            }
            m.signIns.add(1, Attributes.of(SIGN_IN_ROUTE, routeLabel, OUTCOME, outcome))
        }
    }

    /** A live stream opened ([delta] 1) or closed (-1); [kind] is `owner` or `agent`. */
    fun stream(kind: String, delta: Long) {
        meters?.streams?.add(delta, Attributes.of(STREAM_KIND, kind))
    }

    /** One statement that took [nanos]; labelled by its first word only. */
    fun statement(sql: String, nanos: Long) {
        val m = meters ?: return
        m.dbDuration.record(nanos / 1e9, Attributes.of(DB_OPERATION, operation(sql)))
    }

    /** Waited [nanos] for the one database connection. */
    fun connectionWait(nanos: Long) {
        meters?.dbWait?.record(nanos / 1e9)
    }

    /** One MCP tool call: [tool] (a registered name, or `unknown`) and how it went. */
    fun toolCall(tool: String, outcome: String, nanos: Long) {
        val m = meters ?: return
        val attributes = Attributes.of(MCP_TOOL, tool, OUTCOME, outcome)
        m.mcpCalls.add(1, attributes)
        m.mcpDuration.record(nanos / 1e9, attributes)
    }

    /** One push to Apple: [kind] is `alert`, `live_activity` or `push_to_start`; [outcome] `sent`, `removed` or `failed`. */
    fun push(kind: String, outcome: String) {
        meters?.pushSends?.add(1, Attributes.of(PUSH_KIND, kind, OUTCOME, outcome))
    }

    /** An upload of [bytes] landed; [kind] is `image`, `video`, `audio` or `file`. */
    fun uploaded(kind: String, bytes: Long) {
        val m = meters ?: return
        val attributes = Attributes.of(MEDIA_KIND, kind)
        m.uploads.add(1, attributes)
        m.uploadBytes.add(bytes, attributes)
    }

    /**
     * Something worth a line in the operations log: [name] is a fixed name (`job.failed`), and
     * [attributes] only from [EVENT_KEYS] (any other is dropped). Goes to Grafana's logs and to
     * Sentry's, whichever are on; the full line still goes to the server's own output as before.
     */
    fun event(level: Level, name: String, vararg attributes: Pair<String, Any>) {
        val otel = logger
        val sentry = CrashReports.logger
        if (otel == null && sentry == null) return
        val kept = attributes.filter { it.first in EVENT_KEYS }.toMap() + ("event.name" to name)
        otel?.let { log ->
            val builder = log.logRecordBuilder().setSeverity(level.severity).setSeverityText(level.name).setBody(name)
            for ((k, v) in kept) {
                when (v) {
                    is Long -> builder.setAttribute(AttributeKey.longKey(k), v)
                    is Int -> builder.setAttribute(AttributeKey.longKey(k), v.toLong())
                    is Boolean -> builder.setAttribute(AttributeKey.booleanKey(k), v)
                    else -> builder.setAttribute(AttributeKey.stringKey(k), v.toString())
                }
            }
            runCatching { builder.emit() }
        }
        sentry?.let { runCatching { it(level, name, kept) } }
    }

    /** The error's type, never its message (it can quote a card). */
    fun errorType(e: Throwable): String = e::class.java.simpleName.ifEmpty { e::class.java.name }

    /** `SELECT`, `INSERT`, `UPDATE`, `DELETE`, or `OTHER`: never more of the statement. */
    internal fun operation(sql: String): String {
        val word = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
        return if (word in OPERATIONS) word else "OTHER"
    }

    private val OPERATIONS = setOf("SELECT", "INSERT", "UPDATE", "DELETE")

    private val METHOD = AttributeKey.stringKey("http.request.method")
    private val ROUTE = AttributeKey.stringKey("http.route")
    private val STATUS_CLASS = AttributeKey.stringKey("http.response.status_class")
    private val STREAM_KIND = AttributeKey.stringKey("stream.kind")
    private val DB_OPERATION = AttributeKey.stringKey("db.operation.name")
    private val MCP_TOOL = AttributeKey.stringKey("mcp.tool")
    private val OUTCOME = AttributeKey.stringKey("outcome")
    private val PUSH_KIND = AttributeKey.stringKey("push.kind")
    private val MEDIA_KIND = AttributeKey.stringKey("media.kind")
    private val SIGN_IN_ROUTE = AttributeKey.stringKey("sign_in.route")
    private val MEMORY_TYPE = AttributeKey.stringKey("jvm.memory.type")
    private val GC_NAME = AttributeKey.stringKey("jvm.gc.name")

    /** Request and statement times, in seconds: few buckets, as each one is a series of its own. */
    private val SECONDS = listOf(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    private val TOOL_SECONDS = listOf(0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 15.0, 30.0, 60.0)

    private class Meters(meter: Meter) {
        val httpDuration: DoubleHistogram = meter.histogramBuilder("http.server.request.duration")
            .setUnit("s").setDescription("How long requests took, by route pattern and status class")
            .setExplicitBucketBoundariesAdvice(SECONDS).build()
        val streams: LongUpDownCounter = meter.upDownCounterBuilder("feed.streams.active")
            .setUnit("{stream}").setDescription("Live event streams open now, owners' and agents'").build()
        val dbDuration: DoubleHistogram = meter.histogramBuilder("db.client.operation.duration")
            .setUnit("s").setDescription("How long database statements took").setExplicitBucketBoundariesAdvice(SECONDS).build()
        val dbWait: DoubleHistogram = meter.histogramBuilder("db.client.connection.wait_time")
            .setUnit("s").setDescription("How long work waited for the database connection").setExplicitBucketBoundariesAdvice(SECONDS).build()
        val mcpCalls: LongCounter = meter.counterBuilder("feed.mcp.tool.calls")
            .setUnit("{call}").setDescription("MCP tool calls, by tool and outcome").build()
        val mcpDuration: DoubleHistogram = meter.histogramBuilder("feed.mcp.tool.duration")
            .setUnit("s").setDescription("How long MCP tool calls took (some wait on purpose)").setExplicitBucketBoundariesAdvice(TOOL_SECONDS).build()
        val signIns: LongCounter = meter.counterBuilder("feed.sign_in.attempts")
            .setUnit("{attempt}").setDescription("Password sign-ins: ok, refused, blocked (rate limited) or error").build()
        val pushSends: LongCounter = meter.counterBuilder("feed.push.sends")
            .setUnit("{push}").setDescription("Pushes sent to Apple, by kind and outcome").build()
        val uploads: LongCounter = meter.counterBuilder("feed.uploads")
            .setUnit("{upload}").setDescription("Uploads that landed, by kind").build()
        val uploadBytes: LongCounter = meter.counterBuilder("feed.upload.bytes")
            .setUnit("By").setDescription("Bytes of uploads that landed, by kind").build()

        init {
            meter.gaugeBuilder("feed.teams").ofLongs().setUnit("{team}").setDescription("Open teams on this server")
                .buildWithCallback { out -> teams.get().takeIf { it >= 0 }?.let { out.record(it) } }

            val memory = ManagementFactory.getMemoryMXBean()
            val heap = Attributes.of(MEMORY_TYPE, "heap")
            val nonHeap = Attributes.of(MEMORY_TYPE, "non_heap")
            meter.upDownCounterBuilder("jvm.memory.used").setUnit("By").setDescription("Memory in use")
                .buildWithCallback { out ->
                    out.record(memory.heapMemoryUsage.used, heap)
                    out.record(memory.nonHeapMemoryUsage.used, nonHeap)
                }
            meter.upDownCounterBuilder("jvm.memory.committed").setUnit("By").setDescription("Memory the JVM has taken from the system")
                .buildWithCallback { out ->
                    out.record(memory.heapMemoryUsage.committed, heap)
                    out.record(memory.nonHeapMemoryUsage.committed, nonHeap)
                }
            meter.upDownCounterBuilder("jvm.memory.limit").setUnit("By").setDescription("The most heap the JVM may use")
                .buildWithCallback { out -> memory.heapMemoryUsage.max.takeIf { it > 0 }?.let { out.record(it, heap) } }

            val collectors = ManagementFactory.getGarbageCollectorMXBeans()
            meter.counterBuilder("jvm.gc.collections").setUnit("{collection}").setDescription("Garbage collections, by collector")
                .buildWithCallback { out -> collectors.forEach { gc -> gc.collectionCount.takeIf { it >= 0 }?.let { out.record(it, Attributes.of(GC_NAME, gc.name)) } } }
            meter.counterBuilder("jvm.gc.collection_time").ofDoubles().setUnit("s").setDescription("Time spent collecting garbage, by collector")
                .buildWithCallback { out -> collectors.forEach { gc -> gc.collectionTime.takeIf { it >= 0 }?.let { out.record(it / 1000.0, Attributes.of(GC_NAME, gc.name)) } } }

            val threads = ManagementFactory.getThreadMXBean()
            meter.upDownCounterBuilder("jvm.thread.count").setUnit("{thread}").setDescription("Live threads")
                .buildWithCallback { out -> out.record(threads.threadCount.toLong()) }

            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            if (os != null) {
                meter.gaugeBuilder("jvm.cpu.recent_utilization").setUnit("1").setDescription("This process's recent CPU use, 0 to 1")
                    .buildWithCallback { out -> os.processCpuLoad.takeIf { it >= 0 }?.let { out.record(it) } }
            }
            // The figure Heroku compares with the dyno's 512 MB: resident memory, from Linux.
            if (java.io.File("/proc/self/status").canRead()) {
                meter.upDownCounterBuilder("process.memory.usage").setUnit("By").setDescription("Resident memory (RSS)")
                    .buildWithCallback { out -> residentBytes()?.let { out.record(it) } }
            }
        }
    }

    /** VmRSS from /proc/self/status, in bytes, or null off Linux. */
    internal fun residentBytes(): Long? = runCatching {
        java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        }
    }.getOrNull()
}
