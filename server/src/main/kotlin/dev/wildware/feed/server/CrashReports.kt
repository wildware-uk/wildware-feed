package dev.wildware.feed.server

import dev.wildware.feed.server.http.ServerInfo
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SentryEvent
import io.sentry.SentryLogEvent
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.logger.SentryLogParameters
import io.sentry.protocol.SentryTransaction

/**
 * Crash reports for a server that is given somewhere to send them: `SENTRY_DSN`. Without it, the
 * default, nothing is sent anywhere. A report says what failed and where in the code: the error's
 * type, its stack, the HTTP method and route pattern, the server's version. Never what the request
 * carried, who made it, their address, error messages (they can quote a card), or this machine's name.
 *
 * With the same DSN it also sends a sample of performance traces ([Tracing],
 * `SENTRY_TRACES_SAMPLE_RATE`) and the server's operational events as Sentry logs ([Telemetry.event],
 * `SENTRY_LOGS`), scrubbed the same way (server/deploy/OBSERVABILITY.md).
 */
object CrashReports {
    /** Where a failed request is reported; null until [start] runs with a DSN. Tests set their own. */
    @Volatile
    internal var reporter: ((Throwable, String, String?) -> Unit)? = null

    /** Where [Telemetry.event] sends a Sentry log; null unless logs are on. Tests set their own. */
    @Volatile
    internal var logger: ((Telemetry.Level, String, Map<String, Any>) -> Unit)? = null

    /** `SENTRY_TRACES_SAMPLE_RATE`: 0.05 unless it says otherwise; 0 (or anything not a number from 0 to 1) is none. */
    internal fun tracesRate(env: Map<String, String>): Double {
        val text = env["SENTRY_TRACES_SAMPLE_RATE"]?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_TRACES_RATE
        return text.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 0.0
    }

    /** `SENTRY_LOGS`: on unless it is `false`, `off` or `0`. */
    internal fun logsOn(env: Map<String, String>) = env["SENTRY_LOGS"]?.trim()?.lowercase() !in setOf("false", "off", "0")

    const val DEFAULT_TRACES_RATE = 0.05

    /** Starts reporting if [env] has `SENTRY_DSN`. Returns whether it did. */
    fun start(env: Map<String, String>): Boolean {
        val dsn = env["SENTRY_DSN"]?.trim().orEmpty()
        if (dsn.isEmpty()) return false
        val rate = tracesRate(env)
        val logs = logsOn(env)
        Sentry.init { options -> options.forCrashReports(dsn, env["SENTRY_ENVIRONMENT"]?.trim()?.ifEmpty { null } ?: "production", rate, logs) }
        Tracing.rate = rate
        if (logs) {
            logger = { level, body, attributes ->
                val sentryLevel = when (level) {
                    Telemetry.Level.INFO -> SentryLogLevel.INFO
                    Telemetry.Level.WARN -> SentryLogLevel.WARN
                    Telemetry.Level.ERROR -> SentryLogLevel.ERROR
                }
                Sentry.logger().log(sentryLevel, SentryLogParameters.create(SentryAttributes.fromMap(attributes)), body)
            }
        }
        // Feedback someone chose to send goes to the same project's user feedback (server/spec/14-feedback.md).
        FeedbackReports.useSentry()
        reporter = { error, method, route ->
            Sentry.captureException(error) { scope ->
                scope.setTag("http.method", method)
                route?.let { scope.setTag("http.route", it) }
            }
        }
        return true
    }

    /** A request that failed with [error] (its answer is a 500): reported, if reports are on. */
    fun requestFailed(error: Throwable, method: String, route: String?) {
        reporter?.let { report -> runCatching { report(error, method, route) } }
    }

    internal fun SentryOptions.forCrashReports(dsn: String, environment: String, tracesRate: Double = 0.0, logsOn: Boolean = false) {
        this.dsn = dsn
        this.environment = environment
        release = "wildware-feed-server@${ServerInfo.version}"
        isSendDefaultPii = false
        isAttachServerName = false
        serverName = null
        isEnableUncaughtExceptionHandler = true
        isAttachStacktrace = true
        // Tracing.startRequest picks the sample; Sentry needs a rate only to know tracing is on.
        tracesSampleRate = tracesRate.takeIf { it > 0.0 }
        profilesSampleRate = null
        isEnableAutoSessionTracking = false
        isSendClientReports = false
        logs.isEnabled = logsOn
        logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { log -> scrubLog(log) }
        beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
        beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { transaction, _ -> scrubTransaction(transaction) }
        // Feedback keeps its words (they were sent on purpose) and loses the rest.
        beforeSendFeedback = SentryOptions.BeforeSendCallback { event, _ -> FeedbackReports.scrub(event) }
        // Nothing records breadcrumbs on the server; any that appear could carry anything.
        beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { _, _ -> null }
    }

    /** [event] with only what went wrong and where: no messages, request, user, extras or machine name. */
    internal fun scrub(event: SentryEvent): SentryEvent {
        // An address that finds no town: with none, Sentry would look one up from the connection.
        event.user = io.sentry.protocol.User().apply { ipAddress = "0.0.0.0" }
        event.request = null
        event.serverName = null
        event.transaction = null
        event.extras = null
        event.message = null
        event.breadcrumbs = null
        event.exceptions?.forEach { it.value = null }
        event.contexts.remove("response")
        return event
    }

    /** Attributes a trace may keep: the method and the status, nothing about who or what. */
    private val TRACE_TAGS = setOf("http.method", "http.status_code")

    /** [transaction] with only the route, timings, method, status and statement shapes. */
    internal fun scrubTransaction(transaction: SentryTransaction): SentryTransaction {
        transaction.user = io.sentry.protocol.User().apply { ipAddress = "0.0.0.0" }
        transaction.request = null
        transaction.serverName = null
        transaction.extras = null
        transaction.breadcrumbs = null
        transaction.contexts.remove("response")
        transaction.tags = transaction.tags?.filterKeys { it in TRACE_TAGS }?.toMutableMap()
        for (span in transaction.spans) span.data = null
        return transaction
    }

    /** [log] with only Sentry's own fields and the attributes [Telemetry.EVENT_KEYS] allows: no address, user or machine. */
    internal fun scrubLog(log: SentryLogEvent): SentryLogEvent {
        log.attributes = log.attributes?.filterKeys { key ->
            key in Telemetry.EVENT_KEYS || (key.startsWith("sentry.") && !key.startsWith("sentry.message.parameter"))
        }?.toMutableMap()
        return log
    }
}
