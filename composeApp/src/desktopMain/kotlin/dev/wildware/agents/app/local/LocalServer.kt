package dev.wildware.agents.app.local

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.http.FeedServer
import dev.wildware.feed.server.http.ServerInfo
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
    /** What seals shared secrets (docs/SECRETS.md). Added on first run after secrets arrived. */
    val secretsKey: String? = null,
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

    /** Made fresh each time the server starts, and only while it runs: what it proves itself with. */
    @Volatile
    private var proofKey: String? = null

    /** Every port this server has been saved at or run on, while the app runs: [LocalServerCheck] guards them all. */
    private val ports: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val settingsFile get() = File(dir, "settings.json")

    internal fun settings(): LocalServerSettings {
        dir.mkdirs()
        settingsFile.takeIf { it.isFile }?.let { file ->
            val saved = json.decodeFromString(LocalServerSettings.serializer(), file.readText())
            ports += saved.port
            if (saved.secretsKey != null) return saved
            return saved.copy(secretsKey = secret()).also(::save)
        }
        val fresh = LocalServerSettings(tokenSecret = secret(), sessionSecret = secret(), port = freePort(preferredPort), secretsKey = secret())
        save(fresh)
        ports += fresh.port
        return fresh
    }

    private fun save(settings: LocalServerSettings) = writePrivately(settingsFile, json.encodeToString(LocalServerSettings.serializer(), settings))

    override suspend fun ensureRunning(): String = mutex.withLock {
        startLocked()
        "http://127.0.0.1:${settings().port}"
    }

    /** Whether a request to [host]:[port] is one to this server, which must prove itself first. */
    internal fun guards(host: String, port: Int): Boolean = port in ports && isLoopbackHost(host)

    /** What this server answers [nonce] with while it runs, or null when it isn't running. */
    internal fun proofFor(nonce: String): String? = proofKey?.let { ServerInfo.proof(it, nonce) }

    private suspend fun startLocked(): FeedServer = withContext(Dispatchers.IO) {
        server?.let { return@withContext it }
        takeLock()
        try {
            var settings = settings()
            var bindFailure: Exception? = null
            repeat(3) {
                // Another program may have taken the saved port since: then this server moves to a free one.
                val port = freePort(settings.port, anyAddress = settings.phoneAccess)
                if (port != settings.port) settings = settings.copy(port = port).also(::save)
                ports += port
                val key = secret()
                val feed = FeedServer(App(config(settings, key), Database.open(File(dir, "data").absolutePath)))
                try {
                    feed.start()
                } catch (e: Exception) {
                    runCatching { feed.stop() }
                    if (!e.isBindFailure()) throw e
                    bindFailure = e
                    settings = settings.copy(port = freePort(port + 1, anyAddress = settings.phoneAccess)).also(::save)
                    return@repeat
                }
                server = feed
                proofKey = key
                mutableState.value = LocalServerState(
                    running = true,
                    localUrl = "http://127.0.0.1:${settings.port}",
                    phoneUrl = if (settings.phoneAccess) base(settings) else null,
                    phoneAccess = settings.phoneAccess,
                )
                return@withContext feed
            }
            throw IllegalStateException("The server on this computer couldn't start: its port is in use.", bindFailure)
        } catch (e: Exception) {
            releaseLock()
            mutableState.value = LocalServerState(problem = e.message ?: "The server on this computer didn't start.")
            throw e
        }
    }

    private fun base(settings: LocalServerSettings) =
        if (settings.phoneAccess) "http://${lanAddress() ?: "127.0.0.1"}:${settings.port}" else "http://127.0.0.1:${settings.port}"

    private fun config(settings: LocalServerSettings, proofKey: String, base: String = base(settings)) = Config(
        dataDir = File(dir, "data").absolutePath,
        // Without phone access nobody signs in with a password: this app mints its own key. The
        // phone password is kept for when phones are let in again, but no sign-in takes it meanwhile.
        adminPasswordHash = settings.passwordHash?.takeIf { settings.phoneAccess } ?: Passwords.hash(secret()),
        sessionSecret = settings.sessionSecret,
        tokenSecret = settings.tokenSecret,
        publicBaseUrl = base,
        maxImageBytes = 10L * 1024 * 1024,
        maxVideoBytes = 200L * 1024 * 1024,
        maxFileBytes = 50L * 1024 * 1024,
        holdSeconds = 55,
        apns = null,
        port = settings.port,
        host = if (settings.phoneAccess) "0.0.0.0" else "127.0.0.1",
        origin = base,
        trustLoopback = true,
        secretsKeys = settings.secretsKey?.let { Config.SecretsKeys(it) },
        localProofKey = proofKey,
    )

    override suspend fun signInHere(deviceName: String): LocalSignIn = mutex.withLock {
        val running = startLocked()
        val (device, key) = running.app.devices.mint(deviceName)
        LocalSignIn(server = "http://127.0.0.1:${settings().port}", token = key, deviceId = device.id, deviceName = device.name)
    }

    override suspend fun stop() {
        mutex.withLock { stopLocked() }
    }

    private suspend fun stopLocked() = withContext(Dispatchers.IO) {
        proofKey = null
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
        /** Picked once, then kept while it stays free, so agents' settings stay valid. */
        const val DEFAULT_PORT = 4317

        private val random = SecureRandom()

        private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        /**
         * [preferred] if nothing listens there, else the next free port above it. Free on 127.0.0.1,
         * and on every address too when [anyAddress] (phone access listens on all of them).
         */
        internal fun freePort(preferred: Int, anyAddress: Boolean = false): Int {
            fun free(host: String, port: Int) = runCatching { ServerSocket().use { it.bind(InetSocketAddress(host, port)) } }.isSuccess
            for (port in preferred until preferred + 100) {
                if (free("127.0.0.1", port) && (!anyAddress || free("0.0.0.0", port))) return port
            }
            return ServerSocket(0).use { it.localPort }
        }

        private fun Throwable.isBindFailure(): Boolean = generateSequence(this) { it.cause }.any { it is java.net.BindException }

        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]").lowercase()
            return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" || Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$").matches(h)
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
