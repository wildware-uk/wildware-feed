package dev.wildware.agents.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The iPhone's Live Activities, as the settings sheet shows them: whether iOS lets this app show
 * them, and why the last one would not start. Swift keeps it current through [update].
 */
class LiveActivityStatus(private val openSystemSettings: () -> Unit) {
    /** [allowed] is false when Live Activities are off for this app in iOS Settings. */
    data class State(val allowed: Boolean, val problem: String?)

    private val flow = MutableStateFlow(State(allowed = true, problem = null))

    val state: StateFlow<State> = flow.asStateFlow()

    fun update(allowed: Boolean, problem: String?) {
        flow.value = State(allowed, problem)
    }

    /** Opens this app's page in the system Settings, where Live Activities are turned on. */
    fun openSettings() = openSystemSettings()
}
