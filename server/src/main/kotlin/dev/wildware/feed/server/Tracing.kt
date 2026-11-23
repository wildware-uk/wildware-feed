package dev.wildware.feed.server

import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TracesSamplingDecision
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.protocol.TransactionNameSource
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.ThreadLocalRandom

/**
 * Performance traces for Sentry: a sample of requests (`SENTRY_TRACES_SAMPLE_RATE`, 5% by
 * default, 0 for none), each with its database statements as spans. Only with `SENTRY_DSN`.
 *
 * A trace is named by the route's pattern (`GET /api/cards/{id}`), and a statement by its SQL
 * with any literal written in it replaced by `?`: the server binds every value, so the text is the
 * code's, never a team's. [CrashReports.scrubTransaction] takes off the rest before it is sent.
 */
object Tracing {
    /** The share of requests traced, 0 to 1; 0 (the default, and with no DSN) traces none. */
    @Volatile internal var rate: Double = 0.0

    /** Starts a trace; Sentry's unless a test sets its own. */
    @Volatile internal var begin: (String) -> ITransaction = { name ->
        Sentry.startTransaction(
            TransactionContext(name, TransactionNameSource.ROUTE, "http.server", TracesSamplingDecision(true)),
            TransactionOptions().apply { isBindToScope = false },
        )
    }

    /** The span a statement on this thread belongs to: the request's trace, carried by its coroutine. */
    private val current = ThreadLocal<ISpan?>()

    /** A trace for one request, or null for the requests not sampled (most of them, and all when off). */
    fun startRequest(method: String): ITransaction? {
        val r = rate
        if (r <= 0.0 || (r < 1.0 && ThreadLocalRandom.current().nextDouble() >= r)) return null
        return runCatching { begin(method) }.getOrNull()
    }

    /** Puts [span] on the coroutine, so statements made while it runs land in it. */
    fun element(span: ISpan): ThreadContextElement<ISpan?> = current.asContextElement(span)

    /** Name [trace] by its route, say how it ended, and send it. */
    fun finishRequest(trace: ITransaction?, method: String, route: String?, status: Int) {
        trace ?: return
        runCatching {
            trace.setName("$method ${route ?: "other"}", TransactionNameSource.ROUTE)
            trace.setTag("http.method", method)
            trace.setTag("http.status_code", status.toString())
            trace.status = if (status in 100..599) SpanStatus.fromHttpStatusCode(status, SpanStatus.UNKNOWN) else SpanStatus.UNKNOWN
            trace.finish()
        }
    }

    /** Run one statement [sql] as a span of this request's trace, when it has one. */
    inline fun <T> statement(sql: String, block: () -> T): T {
        val span = childSpan(sql) ?: return block()
        try {
            return block().also { span.status = SpanStatus.OK }
        } catch (e: Throwable) {
            span.status = SpanStatus.INTERNAL_ERROR
            throw e
        } finally {
            span.finish()
        }
    }

    @PublishedApi
    internal fun childSpan(sql: String): ISpan? {
        val parent = current.get() ?: return null
        return runCatching { parent.startChild("db.sql.query", describe(sql)) }.getOrNull()
    }

    /** [sql] on one line, each quoted string and number written in it as `?`, at most 300 characters. */
    internal fun describe(sql: String): String = sql
        .replace(Regex("'(?:[^']|'')*'"), "?")
        .replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), "?")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(300)
}
