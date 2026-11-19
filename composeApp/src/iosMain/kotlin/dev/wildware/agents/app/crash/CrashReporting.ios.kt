package dev.wildware.agents.app.crash

import cocoapods.Sentry.SentryBreadcrumb
import cocoapods.Sentry.SentryEvent
import cocoapods.Sentry.SentryException
import cocoapods.Sentry.SentryMessage
import cocoapods.Sentry.SentryOptions
import cocoapods.Sentry.SentryUser
import io.sentry.kotlin.multiplatform.Sentry
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Sentry's Cocoa SDK, through the multiplatform one: native crashes (a bad memory access, an abort)
 * and a Kotlin exception nothing caught, with its Kotlin stack, plus the app hanging. It hooks
 * nothing else: no network notes or failed-request reports (they carry addresses), no screenshots,
 * no screen layout, no replay, no tracing.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCrashSdk(): CrashSdk = object : CrashSdk {
    override fun start(config: CrashReportConfig, allowed: () -> Boolean) {
        Sentry.initWithPlatformOptions { options -> options.forCrashReports(config, allowed) }
    }

    override fun stop() = Sentry.close()
}

@OptIn(ExperimentalForeignApi::class)
private fun SentryOptions.forCrashReports(config: CrashReportConfig, allowed: () -> Boolean) {
    setDsn(config.dsn)
    setReleaseName(config.release)
    setEnvironment(config.environment)
    setSendDefaultPii(false)
    setAttachStacktrace(true)
    setEnableAutoSessionTracking(false)
    setEnableAppHangTracking(true)
    setEnableWatchdogTerminationTracking(true)
    setEnableCaptureFailedRequests(false)
    setEnableNetworkBreadcrumbs(false)
    setEnableNetworkTracking(false)
    setEnableAutoBreadcrumbTracking(false)
    setEnableAutoPerformanceTracing(false)
    setEnableSwizzling(false)
    setAttachScreenshot(false)
    setAttachViewHierarchy(false)
    setSendClientReports(false)
    setTracesSampleRate(null)
    setBeforeSend { event -> if (event != null && allowed()) scrub(event) else null }
    setBeforeBreadcrumb { crumb -> crumb?.let(::scrub) }
}

/** [event] as [CrashScrub] allows it: what went wrong and where, without anything of the owner's. */
@OptIn(ExperimentalForeignApi::class)
private fun scrub(event: SentryEvent): SentryEvent {
    event.user = SentryUser().apply { ipAddress = CrashScrub.NoAddress }
    event.request = null
    event.serverName = null
    event.transaction = null
    event.extra = null
    event.message = event.message?.let { m -> SentryMessage(CrashScrub.text(m.formatted)) }
    event.exceptions?.forEach { item ->
        val exception = item as? SentryException ?: return@forEach
        exception.value = CrashScrub.exceptionMessage(exception.type, exception.value)
    }
    event.breadcrumbs = event.breadcrumbs?.mapNotNull { (it as? SentryBreadcrumb)?.let(::scrub) }
    event.context = event.context?.let { contexts ->
        contexts.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            if (name == "response") return@mapNotNull null
            // The device's own name and id, and the app's per-device hash.
            val private = when (name) {
                "device" -> setOf("name", "id")
                "app" -> setOf("device_app_hash")
                else -> emptySet()
            }
            val inner = (value as? Map<*, *>)?.filterKeys { it !in private } ?: value
            name to inner
        }.toMap()
    }
    return event
}

@OptIn(ExperimentalForeignApi::class)
private fun scrub(crumb: SentryBreadcrumb): SentryBreadcrumb? {
    if (!CrashScrub.keepBreadcrumb(crumb.type, crumb.category)) return null
    crumb.data = null
    crumb.message = crumb.message?.let(CrashScrub::text)
    return crumb
}
