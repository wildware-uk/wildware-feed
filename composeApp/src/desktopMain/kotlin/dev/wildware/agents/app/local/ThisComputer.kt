package dev.wildware.agents.app.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The shortest phone password allowed: the server is on the network once phones are let in. */
const val MIN_PHONE_PASSWORD = 8

/** What is wrong with the phone password typed twice, or null when it will do. */
fun checkPhonePassword(first: String, second: String): String? = when {
    first.isBlank() -> "Choose a password your phone will sign in with."
    first.length < MIN_PHONE_PASSWORD -> "Use at least $MIN_PHONE_PASSWORD characters."
    first != second -> "The two passwords don't match. Type them again."
    else -> null
}

/** Which page the "This computer" dialog is on. */
sealed interface ThisComputerStep {
    /** The phone switch and the "Connect Claude Code" button. */
    data object Overview : ThisComputerStep

    /** What letting phones in means, before anything changes. */
    data object PhoneWarning : ThisComputerStep

    /** The password a phone signs in with, typed twice. */
    data object PhonePassword : ThisComputerStep

    /** A new agent's settings, shown once. */
    data class AgentConnected(val connection: AgentConnection) : ThisComputerStep
}

/** The dialog: closed when [step] is null. [problem] is what went wrong on the current page. */
data class ThisComputerView(
    val step: ThisComputerStep? = null,
    val busy: Boolean = false,
    val problem: String? = null,
)

/**
 * Settings for the server this app runs ("This computer"): letting phones connect, and giving
 * Claude Code a key. Turning phones on goes through a warning and a password; turning them off
 * doesn't ask.
 */
class ThisComputer(
    private val host: LocalServerHost,
    private val scope: CoroutineScope,
    /** What a new agent is called in the device list. */
    private val agentName: String = "Claude Code",
) {
    val server: StateFlow<LocalServerState> get() = host.state

    private val mutableView = MutableStateFlow(ThisComputerView())
    val view: StateFlow<ThisComputerView> = mutableView.asStateFlow()

    fun open() {
        if (mutableView.value.busy) return
        mutableView.value = ThisComputerView(ThisComputerStep.Overview)
    }

    fun close() {
        if (mutableView.value.busy) return
        mutableView.value = ThisComputerView()
    }

    /** Back to the overview from any other page. */
    fun back() {
        if (mutableView.value.busy) return
        mutableView.value = ThisComputerView(ThisComputerStep.Overview)
    }

    /** The switch: on shows the warning first; off shuts phones out at once. */
    fun setPhoneAccess(on: Boolean) {
        if (mutableView.value.busy) return
        if (on) {
            mutableView.value = ThisComputerView(ThisComputerStep.PhoneWarning)
        } else {
            work(ThisComputerStep.Overview) {
                host.setPhoneAccess(false, null)
                ThisComputerStep.Overview
            }
        }
    }

    /** "Continue" on the warning. */
    fun acceptWarning() {
        if (mutableView.value.step != ThisComputerStep.PhoneWarning) return
        mutableView.value = ThisComputerView(ThisComputerStep.PhonePassword)
    }

    /** Lets phones in with [first], once it is typed the same [second] time. */
    fun choosePassword(first: String, second: String) {
        if (mutableView.value.busy) return
        checkPhonePassword(first, second)?.let { problem ->
            mutableView.value = ThisComputerView(ThisComputerStep.PhonePassword, problem = problem)
            return
        }
        work(ThisComputerStep.PhonePassword) {
            host.setPhoneAccess(true, first)
            ThisComputerStep.Overview
        }
    }

    /** "Connect Claude Code": a new agent key, shown once. */
    fun connectAgent() {
        if (mutableView.value.busy) return
        work(ThisComputerStep.Overview) { ThisComputerStep.AgentConnected(host.connectAgent(agentName)) }
    }

    /** Runs [action] from [from], then shows the page it returns, or [from] with what went wrong. */
    private fun work(from: ThisComputerStep, action: suspend () -> ThisComputerStep) {
        mutableView.value = ThisComputerView(from, busy = true)
        scope.launch {
            val next = try {
                ThisComputerView(action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                ThisComputerView(from, problem = failed.message ?: "That didn't work. Try again.")
            }
            mutableView.value = next
        }
    }
}
