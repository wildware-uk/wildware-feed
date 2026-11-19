package dev.wildware.agents.app.crash

/**
 * The web app sends no crash reports: Sentry has no SDK for Kotlin/Wasm, and the browser's own
 * console already shows what went wrong to whoever is looking.
 */
internal actual fun platformCrashSdk(): CrashSdk = object : CrashSdk {
    override fun start(config: CrashReportConfig, allowed: () -> Boolean) = Unit

    override fun stop() = Unit
}
