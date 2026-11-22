package dev.wildware.feed.server

import dev.wildware.feed.server.http.ServerInfo
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions

/**
 * Crash reports for a server that is given somewhere to send them: `SENTRY_DSN`. Without it, the
 * default, nothing is sent anywhere. A report says what failed and where in the code: the error's
 * type, its stack, the HTTP method and route pattern, the server's version. Never what the request
 * carried, who made it, their address, error messages (they can quote a card), or this machine's name.
 */
object CrashReports {
    /** Where a failed request is reported; null until [start] runs with a DSN. Tests set their own. */
    @Volatile
    internal var reporter: ((Throwable, String, String?) -> Unit)? = null

    /** Starts reporting if [env] has `SENTRY_DSN`. Returns whether it did. */
    fun start(env: Map<String, String>): Boolean {
        val dsn = env["SENTRY_DSN"]?.trim().orEmpty()
        if (dsn.isEmpty()) return false
        Sentry.init { options -> options.forCrashReports(dsn, env["SENTRY_ENVIRONMENT"]?.trim()?.ifEmpty { null } ?: "production") }
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

    internal fun SentryOptions.forCrashReports(dsn: String, environment: String) {
        this.dsn = dsn
        this.environment = environment
        release = "wildware-feed-server@${ServerInfo.version}"
        isSendDefaultPii = false
        isAttachServerName = false
        serverName = null
        isEnableUncaughtExceptionHandler = true
        isAttachStacktrace = true
        tracesSampleRate = null
        profilesSampleRate = null
        isEnableAutoSessionTracking = false
        isSendClientReports = false
        logs.isEnabled = false
        beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
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
}
