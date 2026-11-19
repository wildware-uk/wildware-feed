package dev.wildware.feed.server

/**
 * Configuration, from the environment, with the TS server's names, defaults and rules
 * (spec/01-config-and-auth.md §1). A bad value fails at boot naming the variable.
 */
data class Config(
    val dataDir: String,
    val adminPasswordHash: String,
    val sessionSecret: String,
    val tokenSecret: String,
    val publicBaseUrl: String,
    val maxImageBytes: Long,
    val maxVideoBytes: Long,
    val maxFileBytes: Long,
    val holdSeconds: Int,
    val apns: Apns?,
    /** Where to listen (adapter-node's PORT/HOST; same defaults). */
    val port: Int,
    val host: String,
    /** The origin requests are taken to come from, for the CSRF check and redirects. */
    val origin: String,
    /** adapter-node's ADDRESS_HEADER and XFF_DEPTH: where the client address comes from (null: the socket). */
    val addressHeader: String? = null,
    val xffDepth: Int = 1,
    /**
     * Exempt loopback clients (127.0.0.0/8, ::1) from the sign-in lockout. Only a desktop app hosting
     * the server sets it: there every client is 127.0.0.1, so one bad script would lock the owner out.
     */
    val trustLoopback: Boolean = false,
    /** DATABASE_URL: Postgres (Heroku's `postgres://` form or a JDBC URL). Unset: SQLite in [dataDir]. */
    val databaseUrl: String? = null,
    /** MEDIA_STORE=s3 and S3_*: media in a bucket (Cloudflare R2, S3, MinIO). Null: on disk under [dataDir]. */
    val s3: S3? = null,
    /** FFMPEG_THREADS: ffmpeg's thread cap (null: ffmpeg decides). DERIVE_WORKERS: derivations at once. */
    val ffmpegThreads: Int? = null,
    val deriveWorkers: Int = 2,
    /**
     * WEB_DIR: the web app's bundle, served at the site's root. Unset: the `web` folder of the
     * installed server, when it has one. Null: no web app; every page path answers 404.
     */
    val webDir: String? = null,
    /** SECRETS_KEY and SECRETS_KEY_OLD: what seals shared secrets (docs/SECRETS.md). Null: secrets are off. */
    val secretsKeys: SecretsKeys? = null,
) {
    /** The key secrets are sealed under now, and older ones still accepted while they are re-sealed. [toString] leaves them out. */
    class SecretsKeys(val current: String, val old: List<String> = emptyList()) {
        override fun toString() = "SecretsKeys(${old.size} old)"
    }

    /** A private S3-compatible bucket, addressed path-style. [toString] leaves the secret out. */
    data class S3(val endpoint: String, val bucket: String, val accessKeyId: String, val secretAccessKey: String, val region: String) {
        override fun toString() = "S3(endpoint=$endpoint, bucket=$bucket)"
    }

    /** [keyPath] is APNS_KEY_PATH; on a host with no lasting files, [keyText] (APNS_KEY, the .p8 contents) instead. */
    data class Apns(val keyId: String, val teamId: String, val keyPath: String, val bundleId: String, val keyText: String? = null) {
        override fun toString() = "Apns(keyId=$keyId, teamId=$teamId, keyPath=$keyPath, bundleId=$bundleId)"
    }

    class Invalid(val issues: List<String>) :
        IllegalArgumentException("Invalid configuration:\n  " + issues.joinToString("\n  "))

    companion object {
        const val MIN_SECRET_LENGTH = 32

        /** Read and check [env]. An empty string counts as unset, as in the TS server. */
        fun load(env: Map<String, String> = System.getenv()): Config {
            val get = { name: String -> env[name]?.takeIf { it.isNotEmpty() } }
            val issues = mutableListOf<String>()

            fun bytes(name: String, fallback: Long): Long {
                val raw = get(name) ?: return fallback
                val n = jsNumber(raw)
                return when {
                    n == null -> { issues += "$name: Invalid input: expected number, received NaN"; fallback }
                    n != Math.floor(n) || n.isInfinite() -> { issues += "$name: Invalid input: expected int, received number"; fallback }
                    n <= 0 -> { issues += "$name: Too small: expected number to be >0"; fallback }
                    else -> n.toLong()
                }
            }

            fun secret(name: String): String {
                val value = get(name)
                if (value == null) issues += "$name: $name is required"
                else if (value.length < MIN_SECRET_LENGTH) issues += "$name: $name must be at least $MIN_SECRET_LENGTH characters"
                return value ?: ""
            }

            val dataDir = get("DATA_DIR") ?: "data"
            val hash = get("ADMIN_PASSWORD_HASH")
            if (hash == null) issues += "ADMIN_PASSWORD_HASH: ADMIN_PASSWORD_HASH is required"
            else if (!hash.startsWith("\$argon2id\$")) issues += "ADMIN_PASSWORD_HASH: ADMIN_PASSWORD_HASH must be an argon2id hash"
            val sessionSecret = secret("SESSION_SECRET")
            val tokenSecret = secret("TOKEN_SECRET")

            val rawBase = get("PUBLIC_BASE_URL") ?: "http://localhost:5173"
            if (!Regex("^https?://[^/]+").containsMatchIn(rawBase)) issues += "PUBLIC_BASE_URL: PUBLIC_BASE_URL must be an absolute http(s) URL"
            val base = rawBase.trimEnd('/')

            val maxImage = bytes("MAX_IMAGE_BYTES", 10L * 1024 * 1024)
            val maxVideo = bytes("MAX_VIDEO_BYTES", 200L * 1024 * 1024)
            val maxFile = bytes("MAX_FILE_BYTES", 50L * 1024 * 1024)

            var hold = 55
            get("HOLD_S")?.let { raw ->
                val n = jsNumber(raw)
                when {
                    n == null -> issues += "HOLD_S: Invalid input: expected number, received NaN"
                    n != Math.floor(n) -> issues += "HOLD_S: Invalid input: expected int, received number"
                    n < 1 -> issues += "HOLD_S: Too small: expected number to be >=1"
                    n > 59 -> issues += "HOLD_S: Too big: expected number to be <=59"
                    else -> hold = n.toInt()
                }
            }

            val fieldsOk = issues.isEmpty()
            // APNS_KEY (the key itself, for hosts like Heroku) stands in for APNS_KEY_PATH.
            val apnsKey = get("APNS_KEY")?.replace("\\n", "\n")
            val apnsParts = listOf("APNS_KEY_ID", "APNS_TEAM_ID", "APNS_KEY_PATH", "APNS_BUNDLE_ID").map(get)
                .let { if (apnsKey != null && it[2] == null) it.toMutableList().apply { this[2] = "APNS_KEY" } else it }
            if (fieldsOk && apnsParts.any { it == null } && apnsParts.any { it != null }) {
                issues += "APNS_KEY_ID, APNS_TEAM_ID, APNS_KEY_PATH and APNS_BUNDLE_ID must be set together, or none"
            }

            val s3 = when (val store = get("MEDIA_STORE") ?: "local") {
                "local" -> null
                "s3" -> {
                    val parts = listOf("S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY_ID", "S3_SECRET_ACCESS_KEY").associateWith(get)
                    parts.filterValues { it == null }.keys.forEach { issues += "$it: $it is required when MEDIA_STORE is s3" }
                    val endpoint = parts["S3_ENDPOINT"]
                    if (endpoint != null && !Regex("^https?://[^/]+").containsMatchIn(endpoint)) issues += "S3_ENDPOINT: S3_ENDPOINT must be an absolute http(s) URL"
                    if (parts.values.all { it != null }) {
                        S3(endpoint!!.trimEnd('/'), parts["S3_BUCKET"]!!, parts["S3_ACCESS_KEY_ID"]!!, parts["S3_SECRET_ACCESS_KEY"]!!, get("S3_REGION") ?: "auto")
                    } else {
                        null
                    }
                }
                else -> { issues += "MEDIA_STORE: MEDIA_STORE must be local or s3, not $store"; null }
            }

            val secretsKey = get("SECRETS_KEY")
            if (secretsKey != null && secretsKey.length < MIN_SECRET_LENGTH) issues += "SECRETS_KEY: SECRETS_KEY must be at least $MIN_SECRET_LENGTH characters"
            val oldSecretsKeys = get("SECRETS_KEY_OLD")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (oldSecretsKeys.any { it.length < MIN_SECRET_LENGTH }) issues += "SECRETS_KEY_OLD: each old key must be at least $MIN_SECRET_LENGTH characters"
            if (oldSecretsKeys.isNotEmpty() && secretsKey == null) issues += "SECRETS_KEY_OLD: SECRETS_KEY_OLD needs SECRETS_KEY, the key to re-seal under"

            if (issues.isNotEmpty()) throw Invalid(issues)

            val port = env["PORT"]?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 3000
            val host = env["HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
            val origin = env["ORIGIN"]?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: base

            return Config(
                dataDir = dataDir,
                adminPasswordHash = hash!!,
                sessionSecret = sessionSecret,
                tokenSecret = tokenSecret,
                publicBaseUrl = base,
                maxImageBytes = maxImage,
                maxVideoBytes = maxVideo,
                maxFileBytes = maxFile,
                holdSeconds = hold,
                apns = if (apnsParts.all { it != null }) Apns(apnsParts[0]!!, apnsParts[1]!!, apnsParts[2]!!, apnsParts[3]!!, apnsKey.takeIf { apnsParts[2] == "APNS_KEY" }) else null,
                port = port,
                host = host,
                origin = origin,
                addressHeader = env["ADDRESS_HEADER"]?.takeIf { it.isNotBlank() }?.lowercase(),
                xffDepth = env["XFF_DEPTH"]?.toIntOrNull() ?: 1,
                databaseUrl = env["DATABASE_URL"]?.takeIf { it.isNotBlank() },
                s3 = s3,
                ffmpegThreads = env["FFMPEG_THREADS"]?.toIntOrNull()?.takeIf { it > 0 },
                deriveWorkers = env["DERIVE_WORKERS"]?.toIntOrNull()?.takeIf { it > 0 } ?: 2,
                webDir = env["WEB_DIR"]?.takeIf { it.isNotBlank() } ?: dev.wildware.feed.server.http.WebApp.besideInstall(),
                secretsKeys = secretsKey?.let { SecretsKeys(it, oldSecretsKeys.filter { old -> old != it }) },
            )
        }

        /** JavaScript's `Number(string)`, enough of it: trims, accepts decimal, hex and exponents; null for NaN. */
        internal fun jsNumber(raw: String): Double? {
            val s = raw.trim()
            if (s.isEmpty()) return 0.0
            if (Regex("^0[xX][0-9a-fA-F]+$").matches(s)) return s.substring(2).toLong(16).toDouble()
            if (!Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s)) return null
            return s.toDouble()
        }
    }
}
