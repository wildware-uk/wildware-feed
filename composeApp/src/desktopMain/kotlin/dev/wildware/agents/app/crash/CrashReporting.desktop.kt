package dev.wildware.agents.app.crash

import io.sentry.kotlin.multiplatform.Sentry

/**
 * Sentry's Java SDK. Its uncaught-exception handler sees a crash on any thread, the window's event
 * thread (where Compose draws) included, and a coroutine that fails with nothing to catch it.
 */
internal actual fun platformCrashSdk(): CrashSdk = object : CrashSdk {
    override fun start(config: CrashReportConfig, allowed: () -> Boolean) {
        Sentry.initWithPlatformOptions { it.forCrashReports(config, allowed) }
    }

    override fun stop() = Sentry.close()
}
