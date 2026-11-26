package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Tenant
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
        val k = key ?: loadKey(settings.keyText ?: readKey(settings.keyPath)).also { key = it }
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

/** A device as the pushers need it; [userId] is whose phone it is (migration 055). */
data class PushDevice(
    val id: String, val apnsToken: String?, val environment: String?, val liveActivityToken: String?,
    val pushToStartToken: String?, val alertProjectIds: List<String>?, val alertPrefs: JsonObject? = null,
    val userId: String = dev.wildware.feed.server.domain.People.FOUNDER,
)

/**
 * iOS alerts (spec/08 §6): on `request.created`, `update.created` and `message.created`, an alert to
 * every phone with an APNs token that wants the project; on `pairing.created`, to every phone. The alert is built inside the event, as the
 * TS server does, and sent off the bus thread. On `notifications.cleared`, a background push to
 * every phone naming the alerts it can take down (§6.9).
 */
class Apns(
    private val app: App,
    private val settings: Config.Apns,
    /** How long clears gather before one background push takes them all (spec/08 §6.9). */
    private val clearDelayMs: Long = CLEAR_DELAY_MS,
    private val transport: ApnsTransport = HttpApnsTransport(settings, app.clock),
) {
    private val messages = PushMessages(app)
    private val sender = Executors.newScheduledThreadPool(2) { r -> Thread(r, "apns").apply { isDaemon = true } }

    /**
     * Tags waiting for the next background clear, by team, then oldest first by whose phones they
     * clear (null: everyone's); a flush is due while it is not empty.
     */
    private val clearing = LinkedHashMap<String, LinkedHashMap<String?, LinkedHashSet<String>>>()

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
        /** Whether the person follows what this is about (migration 052); a phone's own preferences come after. */
        val followed: Boolean = true,
        /** The person who did it (migration 055): their own phones are not told. */
        val actor: String? = null,
        /** A message that names the owner (`@you`, `@owner`): it plays the mention sound. */
        val mention: Boolean = false,
        /** Only owners and admins are told (an agent asking to join is theirs to allow). */
        val admins: Boolean = false,
    ) {
        /**
         * The team's slug, for any team but the home team (docs/HOSTING.md §4): the alert says whose
         * it is (`team`), and its thread id starts with it, as two teams can both have a `web`.
         * Null for the home team, so a self-hosted server's alerts are as before.
         */
        var team: String? = null
    }

    fun devices(): List<PushDevice> = app.db.query(
        "SELECT id, apns_token, apns_environment, live_activity_token, push_to_start_token, alert_project_ids, alert_prefs, user_id FROM owner_devices " +
            "WHERE team_id = :team ORDER BY seq",
    ) { rs ->
        PushDevice(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6)?.let { text -> runCatching { (Wire.json.parseToJsonElement(text) as JsonArray).map { it.jsonPrimitive.content } }.getOrNull() },
            rs.getString(7)?.let { text -> runCatching { Wire.json.parseToJsonElement(text) as JsonObject }.getOrNull() },
            rs.getString(8) ?: dev.wildware.feed.server.domain.People.FOUNDER,
        )
    }

    private fun slug(projectId: String?) = projectId?.let { app.db.one("SELECT slug FROM projects WHERE team_id = :team AND id = ?", it) { rs -> rs.getString(1) } }

    private fun thread(projectId: String?) = slug(projectId) ?: "dashboard"

    /** [team]'s slug, or null for the home team. */
    private fun teamSlug(team: String): String? = if (team == Tenant.HOME) null else app.teams.find(team)?.slug

    /** The alert [member]'s phones would get for [event] (migration 055: each person their own), or null. */
    fun alertFor(event: AppEvent, member: String = dev.wildware.feed.server.domain.People.FOUNDER): Alert? {
        fun id(key: String) = (event.payload[key] as? JsonPrimitive)?.content
        val follows = dev.wildware.feed.server.domain.Subscriptions(app.db, app.clock)
        val who = dev.wildware.feed.server.domain.People.subscriber(member)
        return when (event.type) {
            "request.created" -> {
                val requestId = id("requestId") ?: return null
                val message = messages.request(requestId) ?: return null
                val request = app.requests.find(requestId) ?: return null
                Alert(
                    message, "REQUEST", thread(request.projectId), request.projectId, request.question,
                    request = Triple(request.id, request.kind, request.options?.takeIf { it.isNotEmpty() }),
                    about = dev.wildware.feed.server.domain.AlertPrefs.About(
                        "request", priority = request.priority, agentId = request.agentId, projectSlug = slug(request.projectId),
                    ),
                    followed = follows.hearsRequest(who, request.id, request.projectId, request.priority),
                )
            }
            "update.created" -> {
                val updateId = id("updateId") ?: return null
                val built = messages.update(updateId) ?: return null
                Alert(
                    built.message, "UPDATE", thread(built.projectId), built.projectId, built.body, reply = "update" to updateId,
                    about = dev.wildware.feed.server.domain.AlertPrefs.About("update", built.level, built.priority, built.agentId, slug(built.projectId)),
                    followed = follows.hearsUpdate(who, built.projectId, built.priority),
                )
            }
            "message.created" -> {
                val messageId = id("messageId") ?: return null
                val built = messages.reply(messageId, member) ?: return null
                Alert(
                    built.message, "MESSAGE", thread(built.projectId), built.projectId, built.body, reply = "answers" to messageId,
                    // Who wrote it, when a person did (migration 055), for the app's own "Sam replied".
                    extra = listOfNotNull(built.actorName?.let { "actorName" to JsonPrimitive(it) }),
                    about = dev.wildware.feed.server.domain.AlertPrefs.About(if (built.answered) "message" else "comment"),
                    followed = follows.hearsMessage(who, messageId, built.answered),
                    actor = built.actor,
                    mention = dev.wildware.feed.server.domain.Subscriptions.OWNER_MENTION.containsMatchIn(built.body),
                )
            }
            "pairing.created" -> {
                val pairingId = id("pairingId") ?: return null
                val pairing = app.pairings.find(pairingId)?.takeIf { it.state == "pending" } ?: return null
                val message = messages.pairing(pairing)
                Alert(message, "PAIRING", "dashboard", null, message.body, about = dev.wildware.feed.server.domain.AlertPrefs.About("pairing"), admins = true)
            }
            else -> null
        // Nobody's phone hears about a channel they can't read, not even a critical alert (spec/15).
        }?.takeIf { dev.wildware.feed.server.domain.ChannelAccess(app.db, app.clock).canRead(who, it.projectId) }
    }

    /** Start listening; returns how to stop. */
    fun start(): () -> Unit {
        // Every team's events, each heard inside its own team; the sends carry that team with them.
        val stop = app.bus.subscribeEveryTeam { event ->
            if (event.type == "notifications.cleared") return@subscribeEveryTeam queueClear(event)
            if (event.type !in ALERTING) return@subscribeEveryTeam
            val now = app.clock()
            // Each person's phones, with that person's alert: what they follow decides whether to alert
            // at all, then each phone's own projects, kinds, levels and priorities (migration 047).
            val phones = devices().filter { it.apnsToken != null }.groupBy { it.userId }
            for ((member, theirs) in phones) {
                val alert = runCatching { alertFor(event, member)?.also { it.team = teamSlug(event.team) } }
                    .onFailure { System.err.println("apple push failed: $it") }.getOrNull() ?: continue
                if (!alert.followed || alert.actor == member) continue
                if (alert.admins && (app.people.find(member)?.role?.rank ?: 0) < dev.wildware.feed.server.domain.People.Role.ADMIN.rank) continue
                val wanted = theirs.filter {
                    (alert.projectId == null || it.alertProjectIds == null || alert.projectId in it.alertProjectIds) &&
                        dev.wildware.feed.server.domain.AlertPrefs.notifies(it.alertPrefs, alert.about)
                }
                if (wanted.isEmpty()) continue
                val headers = alertHeaders(settings.bundleId, alert.message.tag, now)
                val normal = Wire.encode(alertPayload(alert))
                val paged = Wire.encode(alertPayload(alert, page = CRITICAL_STYLE))
                for (device in wanted) {
                    // Paged only on a phone that turned critical alerts on, for this agent and project.
                    val payload = if (dev.wildware.feed.server.domain.AlertPrefs.pages(device.alertPrefs, alert.about)) paged else normal
                    val team = event.team
                    sender.execute { Tenant.with(team) { deliver(device, "apns_token", headers, payload) } }
                }
            }
        }
        return { stop(); sender.shutdownNow() }
    }

    /**
     * Gather a `notifications.cleared` event's tags. iOS rations background pushes, so clears that
     * land close together (a few cards read in a row) go out as one, [clearDelayMs] after the first.
     */
    private fun queueClear(event: AppEvent) {
        val tags = (event.payload["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (tags.isEmpty()) return
        val member = (event.payload["memberId"] as? JsonPrimitive)?.content
        val first = synchronized(clearing) {
            clearing.isEmpty().also { clearing.getOrPut(event.team) { LinkedHashMap() }.getOrPut(member) { LinkedHashSet() } += tags }
        }
        if (first) runCatching { sender.schedule(::flushClears, clearDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /**
     * One background push to each phone, naming the alerts it can take off its screen: everyone's,
     * and those of its own person (migration 055).
     */
    fun flushClears() {
        val byTeam = synchronized(clearing) { clearing.mapValues { (_, m) -> m.mapValues { it.value.toList() } }.also { clearing.clear() } }
        for ((team, pending) in byTeam) Tenant.with(team) { flushClears(pending) }
    }

    private fun flushClears(pending: Map<String?, List<String>>) {
        if (pending.isEmpty()) return
        val phones = devices().filter { it.apnsToken != null }
        if (phones.isEmpty()) return
        val headers = clearHeaders(settings.bundleId, app.clock())
        val everyone = pending[null].orEmpty()
        for (device in phones) {
            val tags = (everyone + pending[device.userId].orEmpty()).distinct()
            if (tags.isEmpty()) continue
            deliver(device, "apns_token", headers, Wire.encode(clearPayload(tags)))
        }
    }

    /** Send to one device's token in [column]; `sent`, `removed` (a dead token was cleared) or `failed`. */
    fun deliver(device: PushDevice, column: String, headers: List<Pair<String, String>>, payload: String): String =
        send(device, column, headers, payload).also { outcome ->
            val kind = when (column) {
                "apns_token" -> "alert"
                "live_activity_token" -> "live_activity"
                else -> "push_to_start"
            }
            dev.wildware.feed.server.Telemetry.push(kind, outcome)
        }

    private fun send(device: PushDevice, column: String, headers: List<Pair<String, String>>, payload: String): String {
        val token = when (column) {
            "apns_token" -> device.apnsToken
            "live_activity_token" -> device.liveActivityToken
            else -> device.pushToStartToken
        } ?: return "failed"
        return try {
            val response = transport.send(ApnsRequest(device.environment ?: "production", token, headers, payload))
            when {
                response.status == 200 -> {
                    app.db.update("UPDATE owner_devices SET failures = 0 WHERE team_id = :team AND id = ?", device.id)
                    "sent"
                }
                response.status == 410 || (response.status == 400 && response.reason in setOf("BadDeviceToken", "DeviceTokenNotForTopic")) -> {
                    app.db.update("UPDATE owner_devices SET $column = NULL WHERE team_id = :team AND id = ? AND $column = ?", device.id, token)
                    "removed"
                }
                else -> {
                    app.db.update("UPDATE owner_devices SET failures = failures + 1 WHERE team_id = :team AND id = ?", device.id)
                    "failed"
                }
            }
        } catch (e: Exception) {
            runCatching { app.db.update("UPDATE owner_devices SET failures = failures + 1 WHERE team_id = :team AND id = ?", device.id) }
            System.err.println("apple push failed: $e")
            "failed"
        }
    }

    companion object {
        /** The events that can raise an alert. */
        private val ALERTING = setOf("request.created", "update.created", "message.created", "pairing.created")

        const val EXPIRATION_S = 15 * 60

        /** A clear is still worth having after a phone was off for a while: its own catch-up covers the rest. */
        const val CLEAR_EXPIRATION_S = 6 * 60 * 60
        const val CLEAR_DELAY_MS = 2_000L

        /**
         * At most this many tags in one clear, to stay well inside Apple's 4 KB. The phone asks the
         * dashboard about everything it still shows when the push wakes it, so none are lost.
         */
        const val CLEAR_MAX_TAGS = 60
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

        /**
         * A background push (spec/08 §6.9): no alert, no sound, low priority. It wakes the app,
         * which takes these alerts off the screen and then catches up with the dashboard.
         */
        fun clearHeaders(bundleId: String, now: Long) = listOf(
            "apns-push-type" to "background",
            "apns-topic" to bundleId,
            "apns-priority" to "5",
            "apns-expiration" to (now / 1000 + CLEAR_EXPIRATION_S).toString(),
        )

        /** `{"aps":{"content-available":1},"clear":[tag, …]}`: the newest [CLEAR_MAX_TAGS] tags. */
        fun clearPayload(tags: List<String>): JsonObject = buildJsonObject {
            put("aps", buildJsonObject { put("content-available", JsonPrimitive(1)) })
            put("clear", JsonArray(tags.takeLast(CLEAR_MAX_TAGS).map(::JsonPrimitive)))
        }

        /**
         * How a page reaches a phone that turned critical alerts on: "time-sensitive" for now, which
         * gets through Focus and Do Not Disturb; "critical" (loud through the silent switch) only once
         * Apple grants the app the Critical Alerts entitlement. Without it, keep "time-sensitive".
         */
        const val CRITICAL_STYLE = "time-sensitive"

        /** The critical page's sound: a question marked critical, or any alert sent as Apple's critical alert. */
        const val CRITICAL_SOUND = "critical.wav"

        /**
         * The sound an alert plays: one of the app's own (composeApp's `Sound`, bundled in the iPhone
         * app as `iosApp/Sounds/<file>.wav`). An update plays the grid sound for its priority and level
         * ([gridSound]); a question, an agent asking to join, a reply and a mention each their moment.
         *
         * An older app bundles only `question`, `message`, `done`, `error` and `critical`, and the
         * server can't tell which app a phone runs. So a question and a reply keep their old names
         * (the new app holds the new sounds under them), and an older app plays the system's default
         * for the new names: updates, mentions and agents asking to join.
         */
        fun soundFor(alert: Alert): String = when (alert.category) {
            "UPDATE" -> gridSound(alert.about.priority, alert.about.level)
            "REQUEST" -> if (alert.about.priority == "critical") CRITICAL_SOUND else "question.wav"
            "PAIRING" -> "permission.wav"
            "MESSAGE" -> if (alert.mention) "mention.wav" else "message.wav"
            else -> "default"
        }

        /** `<priority>-<level>.wav`, as the app's `Sound.grid`: no priority is medium, no level is info. */
        fun gridSound(priority: String?, level: String?): String {
            val row = if (priority in listOf("low", "high", "critical")) priority else "medium"
            val column = when (level) {
                "success", "error" -> level
                "warn" -> "warning"
                else -> "info"
            }
            return "$row-$column.wav"
        }

        /**
         * spec/08 §6.7, key order as the TS server builds it; [Alert.extra] keys come last. [page] is
         * how this phone is paged ("time-sensitive" or "critical"), or null when it isn't.
         */
        fun alertPayload(alert: Alert, page: String? = null): JsonObject = buildJsonObject {
            put("aps", buildJsonObject {
                put("alert", buildJsonObject { put("title", JsonPrimitive(alert.message.title)); put("body", JsonPrimitive(alert.message.body)) })
                if (page == "critical") {
                    // A critical alert's sound plays through the silent switch, loud.
                    put("sound", buildJsonObject { put("critical", JsonPrimitive(1)); put("name", JsonPrimitive(CRITICAL_SOUND)); put("volume", JsonPrimitive(1)) })
                } else {
                    put("sound", JsonPrimitive(soundFor(alert)))
                }
                put("thread-id", JsonPrimitive(alert.team?.let { "$it:${alert.threadId}" } ?: alert.threadId))
                put("category", JsonPrimitive(alert.category))
                if (alert.category == "REQUEST") put("mutable-content", JsonPrimitive(1))
                // A question, or an agent asking to join: both wait on the owner, so both get through Focus.
                put("interruption-level", JsonPrimitive(page ?: if (alert.category == "REQUEST" || alert.category == "PAIRING") "time-sensitive" else "active"))
            })
            put("url", JsonPrimitive(alert.message.url))
            put("tag", JsonPrimitive(alert.message.tag))
            put("markdown", JsonPrimitive(truncateBytes(alert.markdown, MARKDOWN_MAX_BYTES)))
            // The app marks it as critical wherever it shows it, paged or not.
            if (alert.about.priority == "critical") put("priority", JsonPrimitive("critical"))
            alert.request?.let { (id, kind, options) ->
                put("requestId", JsonPrimitive(id))
                put("requestKind", JsonPrimitive(kind))
                put("options", options?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            }
            alert.reply?.let { (k, v) -> put("reply", buildJsonObject { put(k, JsonPrimitive(v)) }) }
            alert.extra.forEach { (k, v) -> put(k, v) }
            alert.team?.let { put("team", JsonPrimitive(it)) }
        }
    }
}
