package dev.wildware.agents.android

import android.content.Context
import android.os.Build

/**
 * What the app may show, by where this copy came from.
 *
 * A copy from Google Play has no link to signing up or to buying the Team plan on the website:
 * Play's payments policy lets an app sell its service only through Play's own billing, and the app
 * sells nothing. It still signs in to any team, as the iPhone app does. A copy from the GitHub
 * release, or a build of your own, links to the website as the desktop app does.
 */
object StoreRules {
    /** The package that installs every copy from Google Play, test tracks included. */
    const val GooglePlay = "com.android.vending"

    /** Whether a copy put on the phone by [installer] may link to the website's sign-up and plans. */
    fun linksToWebsite(installer: String?): Boolean = installer != GooglePlay

    /** The package that installed this app, or null when Android doesn't say (adb, a file manager). */
    fun installer(context: Context): String? = runCatching {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) {
            packages.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
}
