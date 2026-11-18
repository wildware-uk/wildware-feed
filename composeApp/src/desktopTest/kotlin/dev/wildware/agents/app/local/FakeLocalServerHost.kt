package dev.wildware.agents.app.local

import kotlinx.coroutines.flow.MutableStateFlow

/** A [LocalServerHost] that runs nothing: it records what it was asked and answers with made-up values. */
class FakeLocalServerHost(
    initial: LocalServerState = LocalServerState(running = true, localUrl = "http://127.0.0.1:4317"),
    /** Thrown by every call, when set. */
    var failure: Exception? = null,
) : LocalServerHost {
    override val state = MutableStateFlow(initial)

    val phoneCalls = mutableListOf<Pair<Boolean, String?>>()
    val agentNames = mutableListOf<String>()
    val signedInAs = mutableListOf<String>()

    override suspend fun signInHere(deviceName: String): LocalSignIn {
        failure?.let { throw it }
        signedInAs += deviceName
        return LocalSignIn("http://127.0.0.1:4317", "device-key", "d1", deviceName)
    }

    override suspend fun ensureRunning() {
        failure?.let { throw it }
    }

    override suspend fun stop() {
        state.value = LocalServerState()
    }

    override suspend fun setPhoneAccess(on: Boolean, password: String?) {
        failure?.let { throw it }
        phoneCalls += on to password
        state.value = state.value.copy(phoneAccess = on, phoneUrl = if (on) "http://192.0.2.10:4317" else null)
    }

    override suspend fun connectAgent(name: String): AgentConnection {
        failure?.let { throw it }
        agentNames += name
        return AgentConnection("http://127.0.0.1:4317", FAKE_TOKEN)
    }

    companion object {
        const val FAKE_TOKEN = "agt_3fK9xQ2mVb7LpR8sT1wZ4nY6cH0dJ5eA"
    }
}
