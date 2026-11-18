package dev.wildware.agents.app.local

import kotlinx.coroutines.flow.StateFlow

/**
 * A Wildware Feed server running inside this app ("This computer"), where the platform can host one
 * (the desktop app). Phones and other computers can be let in later; until then it answers only on
 * 127.0.0.1.
 */
interface LocalServerHost {
    /** Whether it is running, and how others can reach it. */
    val state: StateFlow<LocalServerState>

    /** Starts it if needed and gives this app its own device key. No password: it is this computer. */
    suspend fun signInHere(deviceName: String): LocalSignIn

    /** Starts it if needed (on launch, for a session that was signed in here). */
    suspend fun ensureRunning()

    /** Stops it; agents and phones reconnect when it is back. */
    suspend fun stop()

    /**
     * Lets phones on this network connect with [password], or shuts them out again when [on] is false.
     * Restarts the server on the new address.
     */
    suspend fun setPhoneAccess(on: Boolean, password: String?)

    /** A token for an agent on this computer, with the settings its plugin needs. */
    suspend fun connectAgent(name: String): AgentConnection
}

data class LocalSignIn(val server: String, val token: String, val deviceId: String, val deviceName: String)

data class LocalServerState(
    val running: Boolean = false,
    /** The address agents on this computer use. */
    val localUrl: String? = null,
    /** The address a phone uses, when phone access is on. */
    val phoneUrl: String? = null,
    val phoneAccess: Boolean = false,
    /** Why it could not start, fit to show. */
    val problem: String? = null,
)

data class AgentConnection(val dashboardUrl: String, val agentToken: String)

/** What the "Let my phone connect" switch says before it is turned on. */
const val PHONE_ACCESS_WARNING =
    "Phones on this Wi-Fi will connect over plain http, which isn't encrypted. Anyone else on the same " +
        "network could read your feed and your phone's sign-in key. Only turn this on at home or on " +
        "another network you trust, or use a private network like Tailscale instead."
