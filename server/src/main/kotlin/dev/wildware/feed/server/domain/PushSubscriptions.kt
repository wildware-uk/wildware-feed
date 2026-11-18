package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Web Push subscriptions as the owner's browsers register them (spec/03 §4; TS `src/domain/push.ts`,
 * `src/db/push.ts`). Storage and checks only: sending is phase 4.
 */
class PushSubscriptions(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** `subscribeToPush`: store or refresh one browser; answers the trimmed endpoint. */
    fun subscribe(endpoint: String, p256dh: String, auth: String, label: String?): String {
        val trimmed = endpoint.jsTrim()
        if (trimmed.isEmpty()) throw invalid("endpoint is required")
        when (urlScheme(trimmed)) {
            null -> throw invalid("endpoint must be an absolute URL")
            "https" -> {}
            else -> throw invalid("endpoint must be an https URL")
        }
        val key = p256dh.jsTrim()
        val secret = auth.jsTrim()
        if (key.isEmpty() || secret.isEmpty()) throw invalid("keys.p256dh and keys.auth are required")
        val checkedLabel = label?.jsTrim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > LABEL_MAX_LENGTH) throw invalid("label must be at most $LABEL_MAX_LENGTH characters")
        }
        db.update(
            "INSERT INTO push_subscriptions (id, endpoint, p256dh, auth, label, created_at, prefs) VALUES (?, ?, ?, ?, ?, ?, NULL) " +
                "ON CONFLICT (endpoint) DO UPDATE SET p256dh = excluded.p256dh, auth = excluded.auth, label = excluded.label, " +
                "failures = 0", // prefs are kept: a browser re-subscribing must not wipe them
            Ulid.next(clock()), trimmed, key, secret, checkedLabel, clock(),
        )
        return trimmed
    }

    /** `unsubscribeFromPush`: idempotent; true when a row went. */
    fun unsubscribe(endpoint: String): Boolean {
        val trimmed = endpoint.jsTrim()
        if (trimmed.isEmpty()) throw invalid("endpoint is required")
        return db.update("DELETE FROM push_subscriptions WHERE endpoint = ?", trimmed) > 0
    }

    /** `setDevicePrefs`: checked prefs (null is the default) stored on one subscription. */
    fun setPrefs(endpoint: String, prefs: JsonElement?): JsonObject? {
        val checked = checkPrefs(prefs)
        val changed = db.update("UPDATE push_subscriptions SET prefs = ? WHERE endpoint = ?", checked?.let(Wire::encode), endpoint)
        if (changed == 0) throw notFound("no such subscription: $endpoint")
        return checked
    }

    companion object {
        const val LABEL_MAX_LENGTH = 200
        val TYPES = listOf("request", "update", "message", "comment")
        val LEVELS = listOf("info", "success", "warn", "error")
        val PRIORITIES = listOf("low", "medium", "high")

        /** `assertPushPrefs`: `types`, `levels`, `priorities`, each a list of known words, deduped. */
        fun checkPrefs(input: JsonElement?): JsonObject? {
            if (input == null || input is JsonNull) return null
            if (input !is JsonObject) throw invalid("preferences must be an object, or null for the default")
            val out = LinkedHashMap<String, JsonElement>()
            fun list(field: String, allowed: List<String>) {
                val value = input[field] ?: return
                if (value !is JsonArray) throw invalid("$field must be a list")
                for (member in value) {
                    val s = member.jsString
                    if (s == null || s !in allowed) throw invalid("$field must be any of: ${allowed.joinToString(", ")}")
                }
                out[field] = JsonArray(value.map { it.jsString!! }.distinct().map(::JsonPrimitive))
            }
            list("types", TYPES)
            list("levels", LEVELS)
            list("priorities", PRIORITIES)
            return if (out.isEmpty()) null else JsonObject(out)
        }

        /**
         * The scheme of an absolute URL as WHATWG `new URL` would parse it (lower case), or null when
         * it would throw. Close enough for the checks here: special schemes need a usable host.
         */
        fun urlScheme(text: String): String? {
            val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
            val scheme = match.groupValues[1].lowercase()
            if (scheme !in setOf("http", "https", "ws", "wss", "ftp")) return scheme
            val authority = match.groupValues[2].trimStart('/', '\\').takeWhile { it !in "/?#\\" }
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore("]") + "]" else hostPort.substringBefore(':')
            val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]").removePrefix(":") else hostPort.substringAfter(':', "")
            if (host.isEmpty() || host.any { it in " <>^|%\"`{}" || it.code < 0x20 }) return null
            if (port.isNotEmpty() && (!port.all(Char::isDigit) || port.length > 5 || port.toInt() > 65535)) return null
            return scheme
        }
    }
}
