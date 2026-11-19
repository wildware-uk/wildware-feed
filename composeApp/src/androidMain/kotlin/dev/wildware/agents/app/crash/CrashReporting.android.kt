package dev.wildware.agents.app.crash

import io.sentry.kotlin.multiplatform.Sentry

/**
 * Sentry's Android SDK: crashes on any thread and the app freezing (an ANR). None of its extras:
 * no screenshots, no screen layout, no replay, no taps, no network or system-event notes.
 */
internal actual fun platformCrashSdk(): CrashSdk = object : CrashSdk {
    override fun start(config: CrashReportConfig, allowed: () -> Boolean) {
        Sentry.initWithPlatformOptions { options ->
            options.forCrashReports(config, allowed)
            options.isAnrEnabled = true
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isEnableUserInteractionTracing = false
            options.isEnableNetworkEventBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAutoActivityLifecycleTracing = false
            options.isEnableActivityLifecycleTracingAutoFinish = false
            options.isEnableFramesTracking = false
            options.isEnableAppStartProfiling = false
            options.isEnableNdk = false
            options.isCollectAdditionalContext = false
            options.sessionReplay.sessionSampleRate = null
            options.sessionReplay.onErrorSampleRate = null
        }
    }

    override fun stop() = Sentry.close()
}
