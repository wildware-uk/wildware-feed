package dev.wildware.agents.android

import android.net.Uri

/**
 * What only an on-screen activity can do (open the photo picker, ask for a permission), for
 * things that outlive it: the picker and the mic belong to the app, the activity comes and goes.
 * [MainActivity] fills these in while it exists.
 */
class ActivityHooks {
    /** Opens the photo picker; the activity hands the chosen items to [onPicked]. */
    var pickMedia: ((onPicked: (List<Uri>) -> Unit) -> Unit)? = null

    /** Opens the documents picker, for any file; the activity hands the chosen ones to [onPicked]. */
    var pickDocuments: ((onPicked: (List<Uri>) -> Unit) -> Unit)? = null

    /** Asks for a runtime permission; [onResult] gets whether it was granted. */
    var askPermission: ((permission: String, onResult: (Boolean) -> Unit) -> Unit)? = null
}
