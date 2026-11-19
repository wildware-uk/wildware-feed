package dev.wildware.agents.app.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.User

/**
 * The options the desktop and Android share, on Sentry's Java SDK: crash reports and nothing
 * else (no performance tracing, no profiling, no sessions, no logs), nothing that identifies
 * anyone, and every report through [scrub] first. Each is sent only while [allowed] is true.
 */
internal fun SentryOptions.forCrashReports(config: CrashReportConfig, allowed: () -> Boolean) {
    dsn = config.dsn
    release = config.release
    environment = config.environment
    config.proguardUuid?.let { proguardUuid = it }
    isSendDefaultPii = false
    // The computer's name.
    isAttachServerName = false
    serverName = null
    isEnableUncaughtExceptionHandler = true
    isAttachStacktrace = true
    isAttachThreads = false
    tracesSampleRate = null
    profilesSampleRate = null
    isEnableAutoSessionTracking = false
    isSendClientReports = false
    logs.isEnabled = false
    beforeSend = SentryOptions.BeforeSendCallback { event, _ -> if (allowed()) scrub(event) else null }
    beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrub(crumb) }
}

/** [event] as [CrashScrub] allows it: what went wrong and where, without anything of the owner's. */
internal fun scrub(event: SentryEvent): SentryEvent {
    event.user = User().apply { ipAddress = CrashScrub.NoAddress }
    event.request = null
    event.serverName = null
    event.transaction = null
    event.extras = null
    event.message = event.message?.let { m ->
        Message().apply { formatted = CrashScrub.text(m.formatted ?: m.message.orEmpty()) }
    }
    event.exceptions?.forEach { it.value = CrashScrub.exceptionMessage(it.type, it.value) }
    event.breadcrumbs = event.breadcrumbs?.mapNotNull(::scrub)?.toMutableList()
    event.contexts.device?.apply {
        id = null
        name = null
    }
    event.contexts.app?.deviceAppHash = null
    event.contexts.remove("response")
    return event
}

/** [crumb] without its details and with its message scrubbed, or null if its kind is dropped whole. */
internal fun scrub(crumb: Breadcrumb): Breadcrumb? {
    if (!CrashScrub.keepBreadcrumb(crumb.type, crumb.category)) return null
    return Breadcrumb(crumb.timestamp).apply {
        type = crumb.type
        category = crumb.category
        level = crumb.level
        message = crumb.message?.let(CrashScrub::text)
    }
}
