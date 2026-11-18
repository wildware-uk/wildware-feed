package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors

/** One push to Apple, and what came back. */
data class ApnsRequest(val environment: String, val deviceToken: String, val headers: List<Pair<String, String>>, val payload: String)
data class ApnsResponse(val status: Int, val reason: String?)

fun interface ApnsTransport {
    fun send(request: ApnsRequest): ApnsResponse
}

/**
 * The provider token (spec/08 §6.5): an ES256 JWT, `{"alg":"ES256","kid":…}.{"iss":…,"iat":…}`,
 * signed in raw r||s form, reused for 50 minutes and re-signed after Apple rejects it.
 */
class ProviderToken(private val settings: Config.Apns, private val readKey: (String) -> String = { Files.readString(Path.of(it)) }) {
    private var key: PrivateKey? = null
    private var cached: Pair<String, Long>? = null

    @Synchronized
    fun token(now: Long): String {
        cached?.let { (token, at) -> if (now - at < TTL_MS) return token }
        val k = key ?: loadKey(readKey(settings.keyPath)).also { key = it }
        val header = b64(Wire.encode(buildJsonObject { put("alg", JsonPrimitive("ES256")); put("kid", JsonPrimitive(settings.keyId)) }))
        val claims = b64(Wire.encode(buildJsonObject { put("iss", JsonPrimitive(settings.teamId)); put("iat", JsonPrimitive(now / 1000)) }))
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(k)
            update("$header.$claims".toByteArray(Charsets.UTF_8))
            Tokens.base64url(sign())
        }
        return "$header.$claims.$signature".also { cached = it to now }
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    private fun b64(text: String) = Tokens.base64url(text.toByteArray(Charsets.UTF_8))

    companion object {
        const val TTL_MS = 50 * 60 * 1000L

        /** A `.p8` file: PKCS#8 PEM. */
        fun loadKey(pem: String): PrivateKey {
            val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body)))
        }
    }
}

/** HTTP/2 to `api.push.apple.com` (or the sandbox), with the JDK's client (spec/08 §6.4). */
class HttpApnsTransport(settings: Config.Apns, private val clock: () -> Long = System::currentTimeMillis) : ApnsTransport {
    private val signer = ProviderToken(settings)
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(request: ApnsRequest): ApnsResponse {
        val host = if (request.environment == "sandbox") "https://api.sandbox.push.apple.com" else "https://api.push.apple.com"
        val builder = HttpRequest.newBuilder(URI.create("$host/3/device/${request.deviceToken}"))
            .timeout(Duration.ofSeconds(10))
            .header("authorization", "bearer ${signer.token(clock())}")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(request.payload))
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val reason = response.body().takeIf { it.isNotEmpty() }?.let {
            runCatching { ((Wire.json.parseToJsonElement(it) as JsonObject)["reason"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull()
        }
        if (response.statusCode() == 403 && reason?.contains("ProviderToken") == true) signer.invalidate()
        return ApnsResponse(response.statusCode(), reason)
    }
}

/** A device as the pushers need it. */
data class PushDevice(
    val id: String, val apnsToken: String?, val environment: String?, val liveActivityToken: String?,
    val pushToStartToken: String?, val alertProjectIds: List<String>?, val alertPrefs: JsonObject? = null,
)

/**
 * iOS alerts (spec/08 §6): on `request.created`, `update.created` and `message.created`, an alert to
 * every phone with an APNs token that wants the project. The alert is built inside the event, as the
 * TS server does, and sent off the bus thread.
 */
class Apns(private val app: App, private val settings: Config.Apns, private val transport: ApnsTransport = HttpApnsTransport(settings, app.clock)) {
    private val messages = PushMessages(app)
    private val sender = Executors.newFixedThreadPool(2) { r -> Thread(r, "apns").apply { isDaemon = true } }

    /** One alert, with room for optional fields after today's (they are appended in order). */
    class Alert(
        val message: PushMessage,
        val category: String,
        val threadId: String,
        val projectId: String?,
        val markdown: String,
        val request: Triple<String, String, List<String>?>? = null,
        val reply: Pair<String, String>? = null,
        val extra: List<Pair<String, JsonElement>> = emptyList(),
        val about: dev.wildware.feed.server.domain.AlertPrefs.About = dev.wildware.feed.server.domain.AlertPrefs.About(category.lowercase()),
    )

    fun devices(): List<PushDevice> = app.db.query(
        "SELECT id, apns_token, apns_environment, live_activity_token, push_to_start_token, alert_project_ids, alert_prefs FROM owner_devices ORDER BY seq",
    ) { rs ->
        PushDevice(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6)?.let { text -> runCatching { (Wire.json.parseToJsonElement(text) as JsonArray).map { it.jsonPrimitive.content } }.getOrNull() },
            rs.getString(7)?.let { text -> runCatching { Wire.json.parseToJsonElement(text) as JsonObject }.getOrNull() },
        )
    }

    private fun thread(projectId: String?) = projectId?.let { app.db.one("SELECT slug FROM projects WHERE id = ?", it) { rs -> rs.getString(1) } } ?: "dashboard"

    fun alertFor(event: AppEvent): Alert? {
        fun id(key: String) = (event.payload[key] as? JsonPrimitive)?.content
        return when (event.type) {
            "request.created" -> {
                val requestId = id("requestId") ?: return null
                val message = messages.request(requestId) ?: return null
                val request = app.requests.find(requestId) ?: return null
                Alert(
                    message, "REQUEST", thread(request.projectId), request.projectId, request.question,
                    request = Triple(request.id, request.kind, request.options?.takeIf { it.isNotEmpty() }),
                )
            }
            "update.created" -> {
                val updateId = id("updateId") ?: return null
                val built = messages.update(updateId) ?: return null
                Alert(
                    built.message, "UPDATE", thread(built.projectId), built.projectId, built.body, reply = "update" to updateId,
                    about = dev.wildware.feed.server.domain.AlertPrefs.About("update", built.level, built.priority),
                )
            }
            "message.created" -> {
                val messageId = id("messageId") ?: return null
                val built = messages.reply(messageId) ?: return null
                Alert(
                    built.message, "MESSAGE", thread(built.projectId), built.projectId, built.body, reply = "answers" to messageId,
                    about = dev.wildware.feed.server.domain.AlertPrefs.About(if (built.answered) "message" else "comment"),
                )
            }
            else -> null
        }
    }

    /** Start listening; returns how to stop. */
    fun start(): () -> Unit {
        val stop = app.bus.subscribe { event ->
            val alert = runCatching { alertFor(event) }.onFailure { System.err.println("apple push failed: $it") }.getOrNull() ?: return@subscribe
            val now = app.clock()
            // Each phone's own projects, and its own kinds, levels and priorities (migration 047).
            val wanted = devices().filter {
                it.apnsToken != null && (alert.projectId == null || it.alertProjectIds == null || alert.projectId in it.alertProjectIds) &&
                    dev.wildware.feed.server.domain.AlertPrefs.notifies(it.alertPrefs, alert.about)
            }
            if (wanted.isEmpty()) return@subscribe
            val headers = alertHeaders(settings.bundleId, alert.message.tag, now)
            val payload = Wire.encode(alertPayload(alert))
            for (device in wanted) sender.execute { deliver(device, "apns_token", headers, payload) }
        }
        return { stop(); sender.shutdownNow() }
    }

    /** Send to one device's token in [column]; `sent`, `removed` (a dead token was cleared) or `failed`. */
    fun deliver(device: PushDevice, column: String, headers: List<Pair<String, String>>, payload: String): String {
        val token = when (column) {
            "apns_token" -> device.apnsToken
            "live_activity_token" -> device.liveActivityToken
            else -> device.pushToStartToken
        } ?: return "failed"
        return try {
            val response = transport.send(ApnsRequest(device.environment ?: "production", token, headers, payload))
            when {
                response.status == 200 -> {
                    app.db.update("UPDATE owner_devices SET failures = 0 WHERE id = ?", device.id)
                    "sent"
                }
                response.status == 410 || (response.status == 400 && response.reason in setOf("BadDeviceToken", "DeviceTokenNotForTopic")) -> {
                    app.db.update("UPDATE owner_devices SET $column = NULL WHERE id = ? AND $column = ?", device.id, token)
                    "removed"
                }
                else -> {
                    app.db.update("UPDATE owner_devices SET failures = failures + 1 WHERE id = ?", device.id)
                    "failed"
                }
            }
        } catch (e: Exception) {
            runCatching { app.db.update("UPDATE owner_devices SET failures = failures + 1 WHERE id = ?", device.id) }
            System.err.println("apple push failed: $e")
            "failed"
        }
    }

    companion object {
        const val EXPIRATION_S = 15 * 60
        const val COLLAPSE_ID_MAX_BYTES = 64
        const val MARKDOWN_MAX_BYTES = 2048

        /** Cut [text] to at most [max] UTF-8 bytes without splitting a character. */
        fun truncateBytes(text: String, max: Int): String {
            if (text.toByteArray(Charsets.UTF_8).size <= max) return text
            val out = StringBuilder()
            var used = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (used + n > max) break
                out.appendCodePoint(cp)
                used += n
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        fun alertHeaders(bundleId: String, tag: String, now: Long) = listOf(
            "apns-push-type" to "alert",
            "apns-topic" to bundleId,
            "apns-priority" to "10",
            "apns-expiration" to (now / 1000 + EXPIRATION_S).toString(),
            "apns-collapse-id" to truncateBytes(tag, COLLAPSE_ID_MAX_BYTES),
        )

        /** spec/08 §6.7, key order as the TS server builds it; [Alert.extra] keys come last. */
        fun alertPayload(alert: Alert): JsonObject = buildJsonObject {
            put("aps", buildJsonObject {
                put("alert", buildJsonObject { put("title", JsonPrimitive(alert.message.title)); put("body", JsonPrimitive(alert.message.body)) })
                put("sound", JsonPrimitive("default"))
                put("thread-id", JsonPrimitive(alert.threadId))
                put("category", JsonPrimitive(alert.category))
                if (alert.category == "REQUEST") put("mutable-content", JsonPrimitive(1))
                put("interruption-level", JsonPrimitive(if (alert.category == "REQUEST") "time-sensitive" else "active"))
            })
            put("url", JsonPrimitive(alert.message.url))
            put("tag", JsonPrimitive(alert.message.tag))
            put("markdown", JsonPrimitive(truncateBytes(alert.markdown, MARKDOWN_MAX_BYTES)))
            alert.request?.let { (id, kind, options) ->
                put("requestId", JsonPrimitive(id))
                put("requestKind", JsonPrimitive(kind))
                put("options", options?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            }
            alert.reply?.let { (k, v) -> put("reply", buildJsonObject { put(k, JsonPrimitive(v)) }) }
            alert.extra.forEach { (k, v) -> put(k, v) }
        }
    }
}
