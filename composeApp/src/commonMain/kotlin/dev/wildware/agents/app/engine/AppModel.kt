package dev.wildware.agents.app.engine

import dev.wildware.agents.SignIn
import dev.wildware.agents.SignInException
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen the app is on. */
sealed interface AppScreen {
    /** Signed out. [notice] says why, when the app signed itself out. */
    data class SigningIn(val notice: String? = null) : AppScreen

    data class Signed(val session: DashboardSession) : AppScreen
}

/**
 * The app's top: signed in or not. A saved key opens the feed straight away; signing in saves
 * one; signing out (or the dashboard refusing the key) forgets it and everything kept for it.
 */
class AppModel(private val platform: AppPlatform, private val scope: CoroutineScope) {
    private val mutableScreen = MutableStateFlow<AppScreen>(AppScreen.SigningIn())
    val screen: StateFlow<AppScreen> = mutableScreen.asStateFlow()

    val deviceName: String get() = platform.deviceName

    private var watcher: Job? = null

    init {
        platform.sessions.load()?.let(::open)
    }

    /** Signs in and opens the feed. Throws [SignInException] with what went wrong. */
    @Throws(SignInException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun signIn(server: String, password: String, deviceName: String) {
        val signed = SignIn.signIn(server, password, deviceName, platform.httpEngine())
        val saved = SavedSession(signed.server, signed.token, signed.device.name)
        platform.sessions.save(saved)
        open(saved)
    }

    fun signOut(notice: String? = null) {
        watcher?.cancel()
        (mutableScreen.value as? AppScreen.Signed)?.session?.stop()
        platform.sessions.clear()
        platform.clearLocalData()
        mutableScreen.value = AppScreen.SigningIn(notice)
    }

    private fun open(saved: SavedSession) {
        val session = DashboardSession(saved, platform, scope)
        session.start()
        mutableScreen.value = AppScreen.Signed(session)
        watcher = scope.launch {
            session.signedOut.first { it }
            signOut("This device was signed out on the dashboard. Sign in again to carry on.")
        }
    }
}
