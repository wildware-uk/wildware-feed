package dev.wildware.agents.app.crash

import dev.wildware.agents.app.session.IosTextStore

/**
 * Starts crash reports, from the app delegate as the app launches, before anything else can crash.
 * The switch is kept in [dataDir], the Compose app's folder. Does nothing in a build with nowhere
 * to send reports.
 */
fun startCrashReporting(dataDir: String) {
    CrashReporting.start(IosTextStore("$dataDir/crash-reports.txt"))
}
