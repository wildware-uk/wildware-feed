package dev.wildware.agents.app.local

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.http.FeedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.channels.FileLock
import java.security.SecureRandom
import java.util.Base64

/** What this computer's server keeps between runs, in `server/settings.json` (private to the user). */
@Serializable
internal data class LocalServerSettings(
    val tokenSecret: String,
    val sessionSecret: String,
    val port: Int,
    /** argon2id of the owner password; only set once phones are let in. */
    val passwordHash: String? = null,
    val phoneAccess: Boolean = false,
)

/**
 * The Wildware Feed server running inside the desktop app (server/PLAN.md, phase 7): the same code
 * as the standalone server, on a database in the app's own folder, on 127.0.0.1 until the owner lets
 * phones in. A lock file keeps a second copy of the app from hosting the same data.
 */
class LocalServer(
    private val dir: File,
    private val preferredPort: Int = DEFAULT_PORT,
    private val writePrivately: (File, String) -> Unit = { file, text -> file.writeText(text) },
) : LocalServerHost {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalServerState())
    override val state: StateFlow<LocalServerState> = mutableState

    private var server: FeedServer? = null
    private var lock: FileLock? = null
    private var lockFile: RandomAccessFile? = null

    private val settingsFile get() = File(dir, "settings.json")

    internal fun settings(): LocalServerSettings {
        dir.mkdirs()
        settingsFile.takeIf { it.isFile }?.let { return json.decodeFromString(LocalServerSettings.serializer(), it.readText()) }
        val fresh = LocalServerSettings(tokenSecret = secret(), sessionSecret = secret(), port = freePort(preferredPort))
        save(fresh)
        return fresh
    }

    private fun save(settings: LocalServerSettings) = writePrivately(settingsFile, json.encodeToString(LocalServerSettings.serializer(), settings))

    override suspend fun ensureRunning() {
        mutex.withLock { startLocked() }
    }

    private suspend fun startLocked(): FeedServer = withContext(Dispatchers.IO) {
        server?.let { return@withContext it }
        val settings = settings()
        takeLock()
        try {
            val host = if (settings.phoneAccess) "0.0.0.0" else "127.0.0.1"
            val base = if (settings.phoneAccess) "http://${lanAddress() ?: "127.0.0.1"}:${settings.port}" else "http://127.0.0.1:${settings.port}"
            val config = Config(
                dataDir = File(dir, "data").absolutePath,
                // Without phone access nobody signs in with a password: this app mints its own key.
                adminPasswordHash = settings.passwordHash ?: Passwords.hash(secret()),
                sessionSecret = settings.sessionSecret,
                tokenSecret = settings.tokenSecret,
                publicBaseUrl = base,
                maxImageBytes = 10L * 1024 * 1024,
                maxVideoBytes = 200L * 1024 * 1024,
                maxFileBytes = 50L * 1024 * 1024,
                holdSeconds = 55,
                apns = null,
                port = settings.port,
                host = host,
                origin = base,
                trustLoopback = true,
            )
            val app = App(config, Database.open(config.dataDir))
            val started = FeedServer(app).start()
            server = started
            mutableState.value = LocalServerState(
                running = true,
                localUrl = "http://127.0.0.1:${settings.port}",
                phoneUrl = if (settings.phoneAccess) base else null,
                phoneAccess = settings.phoneAccess,
            )
            started
        } catch (e: Exception) {
            releaseLock()
            mutableState.value = LocalServerState(problem = e.message ?: "The server on this computer didn't start.")
            throw e
        }
    }

    override suspend fun signInHere(deviceName: String): LocalSignIn = mutex.withLock {
        val running = startLocked()
        val (device, key) = running.app.devices.mint(deviceName)
        LocalSignIn(server = "http://127.0.0.1:${settings().port}", token = key, deviceId = device.id, deviceName = device.name)
    }

    override suspend fun stop() {
        mutex.withLock { stopLocked() }
    }

    private suspend fun stopLocked() = withContext(Dispatchers.IO) {
        server?.stop()
        server = null
        releaseLock()
        mutableState.value = LocalServerState()
    }

    override suspend fun setPhoneAccess(on: Boolean, password: String?) {
        mutex.withLock {
            val current = settings()
            val updated = if (on) {
                require(!password.isNullOrBlank()) { "Choose a password your phone will sign in with." }
                current.copy(phoneAccess = true, passwordHash = Passwords.hash(password))
            } else {
                current.copy(phoneAccess = false)
            }
            save(updated)
            if (server != null) {
                stopLocked()
                startLocked()
            }
        }
    }

    override suspend fun connectAgent(name: String): AgentConnection = mutex.withLock {
        val running = startLocked()
        val token = withContext(Dispatchers.IO) { Agents(running.app.db, settings().tokenSecret).mint(name).second }
        AgentConnection(dashboardUrl = "http://127.0.0.1:${settings().port}", agentToken = token)
    }

    private fun takeLock() {
        if (lock != null) return
        val file = RandomAccessFile(File(dir, "server.lock"), "rw")
        val taken = runCatching { file.channel.tryLock() }.getOrNull()
        if (taken == null) {
            file.close()
            throw IllegalStateException("Another copy of Wildware Feed is already running the server on this computer.")
        }
        lockFile = file
        lock = taken
    }

    private fun releaseLock() {
        runCatching { lock?.release() }
        runCatching { lockFile?.close() }
        lock = null
        lockFile = null
    }

    companion object {
        /** Picked once, then kept, so agents' settings stay valid. */
        const val DEFAULT_PORT = 4317

        private val random = SecureRandom()

        private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        /** [preferred] if nothing listens there, else the next free port above it. */
        internal fun freePort(preferred: Int): Int {
            for (port in preferred until preferred + 100) {
                val free = runCatching { ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) } }.isSuccess
                if (free) return port
            }
            return ServerSocket(0).use { it.localPort }
        }

        /** This computer's address on its local network, for phones. */
        internal fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
