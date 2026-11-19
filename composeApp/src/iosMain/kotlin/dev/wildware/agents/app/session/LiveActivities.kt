package dev.wildware.agents.app.session

import dev.wildware.agents.app.crash.CrashReporting
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * What Swift's `LiveActivityController` tells the app about Live Activities: whether iOS allows
 * them for this app, and why the last one would not start, for the settings sheet.
 */
object LiveActivities {
    internal val status = LiveActivityStatus(openSystemSettings = ::openAppSettings)

    fun update(allowed: Boolean, problem: String?) = status.update(allowed, problem)

    /** A note for crash reports, such as the kind of error a start ran into. Never the owner's data. */
    fun note(message: String) = CrashReporting.note("live_activity", message)

    private fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
