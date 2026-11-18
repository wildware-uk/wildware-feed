package dev.wildware.feed.server.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/** The owner's session cookie (spec/01-config-and-auth.md §5.4). No server-side store. */
object Sessions {
    const val COOKIE = "ad_session"
    const val MAX_AGE_S = 2_592_000L
    const val SET_COOKIE_ATTRIBUTES = "Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Lax"
    const val CLEAR = "$COOKIE=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax"

    fun issue(secret: String, issuedAt: Long = System.currentTimeMillis()): String {
        val json = """{"issuedAt":$issuedAt,"expiresAt":${issuedAt + MAX_AGE_S * 1000}}"""
        val payload = Tokens.base64url(json.toByteArray(Charsets.UTF_8))
        return "$payload.${sign(payload, secret)}"
    }

    fun setCookie(value: String) = "$COOKIE=$value; $SET_COOKIE_ATTRIBUTES"

    fun valid(value: String?, secret: String, now: Long = System.currentTimeMillis()): Boolean {
        if (value.isNullOrEmpty() || secret.length < 32) return false
        val dot = value.indexOf('.')
        if (dot <= 0 || dot == value.length - 1) return false
        val payload = value.substring(0, dot)
        val signature = value.substring(dot + 1)
        if (!Tokens.same(sign(payload, secret), signature)) return false
        val body = runCatching {
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return false
        val expiresAt = (body["expiresAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return false
        if ((body["issuedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null) return false
        return expiresAt > now
    }

    private fun sign(payload: String, secret: String) = Tokens.base64url(Tokens.hmac(secret, payload))
}
