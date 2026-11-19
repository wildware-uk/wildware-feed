package dev.wildware.agents.app.crash

import dev.wildware.agents.TextStore
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's "Send crash reports" switch, kept on this device. On unless it was turned off.
 * [changed] hears each flip, so reports stop the moment it goes off.
 */
class CrashReportSetting(private val store: TextStore, private val changed: (Boolean) -> Unit = {}) {
    private val state = MutableStateFlow(runCatching { store.read() }.getOrNull()?.trim() != Off)

    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun set(on: Boolean) {
        if (state.value == on) return
        state.value = on
        runCatching { store.write(if (on) On else Off) }
        changed(on)
    }

    private companion object {
        const val On = "on"
        const val Off = "off"
    }
}

/** Where and as what this build reports crashes. */
data class CrashReportConfig(val dsn: String, val release: String, val environment: String, val proguardUuid: String?)

/** The crash SDK on one platform: started at most once at a time, and stopped when the switch goes off. */
interface CrashSdk {
    /** Starts sending crash reports for [config]; each report is sent only while [allowed] is true. */
    fun start(config: CrashReportConfig, allowed: () -> Boolean)

    fun stop()
}

/**
 * Crash reports, following the owner's switch: the SDK runs only while it is on, and every report
 * checks it again before it leaves, so turning it off stops even one already on its way.
 */
class CrashReporter(private val config: CrashReportConfig, private val sdk: CrashSdk) {
    @Volatile
    private var allowed = false
    private var running = false

    /** Reads the switch from [store], starts the SDK if it is on, and returns the switch. */
    fun start(store: TextStore): CrashReportSetting {
        val setting = CrashReportSetting(store, ::follow)
        follow(setting.enabled.value)
        return setting
    }

    private fun follow(on: Boolean) {
        allowed = on
        if (on && !running) {
            running = runCatching { sdk.start(config) { allowed } }.isSuccess
        } else if (!on && running) {
            runCatching { sdk.stop() }
            running = false
        }
    }
}

/** This platform's crash SDK: Sentry's, set up the way [CrashScrub] says. */
internal expect fun platformCrashSdk(): CrashSdk

/** The app's crash reports. Only a build made with somewhere to send them has any. */
object CrashReporting {
    /** Whether this build reports crashes at all: release builds made by CI do; others send nothing. */
    val available: Boolean get() = CrashReportingBuild.Dsn.isNotBlank()

    /**
     * Leaves a note that a later crash report carries, such as why a Live Activity would not
     * start. [message] must hold nothing of the owner's: no names, text, servers or keys.
     */
    fun note(category: String, message: String) {
        if (setting?.enabled?.value != true) return
        runCatching { Sentry.addBreadcrumb(Breadcrumb(level = SentryLevel.WARNING, category = category, message = message)) }
    }

    /** The switch, once [start] has run in a build that reports crashes; null otherwise. */
    var setting: CrashReportSetting? = null
        private set

    /**
     * Starts crash reports as early as the app can, before anything else that might crash, and
     * keeps the switch in [store]. Does nothing in a build with nowhere to send them.
     */
    fun start(store: TextStore): CrashReportSetting? {
        setting?.let { return it }
        if (!available) return null
        val config = CrashReportConfig(
            dsn = CrashReportingBuild.Dsn,
            release = CrashReportingBuild.Release,
            environment = CrashReportingBuild.Environment,
            proguardUuid = CrashReportingBuild.ProguardUuid.ifBlank { null },
        )
        return CrashReporter(config, platformCrashSdk()).start(store).also { setting = it }
    }
}
