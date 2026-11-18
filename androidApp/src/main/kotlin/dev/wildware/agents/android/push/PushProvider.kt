package dev.wildware.agents.android.push

/**
 * Where real background push plugs in. Today the app only notifies while it runs (or while "Stay
 * connected" keeps it running); push would wake it with the phone asleep and the app closed.
 *
 * To add it (see docs/INSTALL.md, "Android push"): create a Firebase project, add the Firebase
 * Messaging library and google-services.json, implement this with `FirebaseMessaging`, send the
 * token to the dashboard (it needs an FCM endpoint beside its APNs one, `PUT /api/devices/current/fcm`),
 * and show the pushed alert with the same notifier the app uses.
 */
interface PushProvider {
    /** A short name for logs and settings: "none", "fcm". */
    val name: String

    /** The token the dashboard pushes to, or null when push isn't available here. */
    suspend fun token(): String?

    /** Stops push for this device: on sign out. */
    suspend fun forget()
}

/** No push: the app notifies only while it runs. */
object NoPush : PushProvider {
    override val name = "none"
    override suspend fun token(): String? = null
    override suspend fun forget() = Unit
}
