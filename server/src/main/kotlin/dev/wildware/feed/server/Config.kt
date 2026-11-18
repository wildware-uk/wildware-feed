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
    val vapid: Vapid?,
    val apns: Apns?,
    /** Where to listen (adapter-node's PORT/HOST; same defaults). */
    val port: Int,
    val host: String,
    /** The origin requests are taken to come from, for the CSRF check and redirects. */
    val origin: String,
) {
    data class Vapid(val publicKey: String, val privateKey: String, val subject: String)
    data class Apns(val keyId: String, val teamId: String, val keyPath: String, val bundleId: String)

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

            val subject = get("VAPID_SUBJECT")
            if (subject != null && !Regex("^(mailto:|https://)").containsMatchIn(subject)) {
                issues += "VAPID_SUBJECT: VAPID_SUBJECT must be a mailto: address or an https:// URL"
            }

            val fieldsOk = issues.isEmpty()
            val vapidPublic = get("VAPID_PUBLIC_KEY")
            val vapidPrivate = get("VAPID_PRIVATE_KEY")
            if (fieldsOk && (vapidPublic == null) != (vapidPrivate == null)) {
                issues += "VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY must be set together, or neither"
            }
            val apnsParts = listOf("APNS_KEY_ID", "APNS_TEAM_ID", "APNS_KEY_PATH", "APNS_BUNDLE_ID").map(get)
            if (fieldsOk && apnsParts.any { it == null } && apnsParts.any { it != null }) {
                issues += "APNS_KEY_ID, APNS_TEAM_ID, APNS_KEY_PATH and APNS_BUNDLE_ID must be set together, or none"
            }

            val vapid = if (vapidPublic != null && vapidPrivate != null) Vapid(vapidPublic, vapidPrivate, subject ?: base) else null
            // Decided fix 6: push that would sign with an http address cannot work, so refuse to boot.
            if (vapid != null && !Regex("^(mailto:|https://)").containsMatchIn(vapid.subject)) {
                issues += "VAPID_SUBJECT: unset, so push would sign with PUBLIC_BASE_URL ($base), which is not https: and which push services reject. Set VAPID_SUBJECT=mailto:<your address>"
            }

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
                vapid = vapid,
                apns = if (apnsParts.all { it != null }) Apns(apnsParts[0]!!, apnsParts[1]!!, apnsParts[2]!!, apnsParts[3]!!) else null,
                port = port,
                host = host,
                origin = origin,
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
