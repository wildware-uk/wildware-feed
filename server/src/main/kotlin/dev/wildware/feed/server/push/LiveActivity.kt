package dev.wildware.feed.server.push

import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.ChannelAccess
import dev.wildware.feed.server.App
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.Reads
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The Live Activity on the owner's phone (spec/08 §7): after any watched event (coalesced over 2 s,
 * and every minute anyway) each activity gets the new state if it changed. A phone that has none
 * is pushed one (at most every 5 minutes) when a new request waits on the owner. The activity is
 * there for questions only: the moment none waits on him, each one is ended and taken off the
 * lock screen, never left up as an empty panel. State is kept in memory only, per team: each
 * team's phones get their own team's state.
 */
class LiveActivity(private val app: App, private val apns: Apns, private val bundleId: String) {
    private val messages = PushMessages(app)
    private val lastSent = HashMap<String, JsonObject>()
    private val startedAt = HashMap<String, Long>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "live-activity").apply { isDaemon = true } }

    /** What one team's activities remember between runs. */
    private class TeamState {
        var pending: ScheduledFuture<*>? = null
        @Volatile var newRequestId: String? = null
    }

    private val teams = HashMap<String, TeamState>()

    /** This team's state ([Tenant.id]). */
    private fun team(): TeamState = synchronized(teams) { teams.getOrPut(Tenant.id) { TeamState() } }

    /** The content state; optional fields for later designs are appended through [extra]. */
    fun state(extra: List<Pair<String, JsonElement>> = emptyList()): JsonObject {
        val summary = app.reads.summary(Wire::isoOf)
        val requests = summary["requests"]!!.jsonArray
        val top = requests.firstOrNull()?.jsonObject
        fun topField(key: String): JsonElement = top?.get(key)?.takeIf { it !is JsonNull } ?: JsonNull
        return buildJsonObject {
            put("online", JsonPrimitive(summary["onlineAgents"]!!.jsonArray.size))
            put("waiting", JsonPrimitive(requests.size))
            put("inProgress", JsonPrimitive(summary["work"]!!.jsonObject["inProgress"]!!.jsonPrimitive.int))
            put("topAgent", topField("agentName"))
            put("topQuestion", topField("question"))
            put("topRequestId", topField("id"))
            put("topKind", topField("kind"))
            put("topAgentId", topField("agentId"))
            val projectName = top?.get("projectName")?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content
            put("topProject", if (!projectName.isNullOrEmpty()) JsonPrimitive(Reads.cut(projectName, Reads.NAME_MAX)) else JsonNull)
            put("topExpiresAt", top?.let { JsonPrimitive(Math.floorDiv(java.time.Instant.parse(it["expiresAt"]!!.jsonPrimitive.content).toEpochMilli(), 1000L)) } ?: JsonNull)
            put("topOptions", top?.let { lockScreenOptions(it) } ?: JsonNull)
            val default = top?.get("default")
            // The phone decodes topDefault as a string, and one value of the wrong type blanks the
            // whole Live Activity, so a list default (multi_choice) or a long one goes as null.
            val fits = default is JsonPrimitive && default.isString && default.content.length <= OPTION_MAX
            put("topDefault", if (fits) default!! else JsonNull)
            val working = summary["working"]!!.jsonArray
            put("working", if (working.isNotEmpty()) working else JsonNull)
            extra.forEach { (k, v) -> put(k, v) }
        }
    }

    /** A run for [teamId] in [COALESCE_MS], unless one is already due. */
    @Synchronized
    private fun schedule(teamId: String) {
        val t = Tenant.with(teamId) { team() }
        if (t.pending != null) return
        t.pending = timer.schedule({
            synchronized(this) { t.pending = null }
            runCatching { Tenant.with(teamId) { run() } }.onFailure { System.err.println("live activity push failed: $it") }
        }, COALESCE_MS, TimeUnit.MILLISECONDS)
    }

    fun start(): () -> Unit {
        val stop = app.bus.subscribeEveryTeam { event ->
            if (event.type !in WATCHED) return@subscribeEveryTeam
            if (event.type == "request.created") requested((event.payload["requestId"] as? JsonPrimitive)?.content)
            schedule(event.team)
        }
        // Every minute anyway, for each team heard from since the server started.
        val refresh = timer.scheduleAtFixedRate({
            synchronized(teams) { teams.keys.toList() }.forEach(::schedule)
        }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
        return { stop(); refresh.cancel(false); timer.shutdownNow() }
    }

    /** A new request waits on the owner: the next run may push a phone an activity for it. */
    internal fun requested(requestId: String?) {
        team().newRequestId = requestId
    }

    /** One run: runs one at a time on the timer thread. */
    fun run() {
        val t = team()
        val requestId = t.newRequestId
        t.newRequestId = null
        val devices = apns.devices()
        if (devices.none { it.liveActivityToken != null || it.pushToStartToken != null }) return
        val now = app.clock()
        // Each person's own state: what waits and who works only in channels they can read (spec/14).
        val states = HashMap<String, JsonObject>()
        fun stateOf(device: PushDevice) = states.getOrPut(device.userId) { Viewer.with(Viewer.member(device.userId)) { state() } }
        val timestamp = now / 1000
        for (device in devices) {
            val token = device.liveActivityToken ?: continue
            val state = stateOf(device)
            if (!needed(state)) {
                end(device, token, state, timestamp)
                continue
            }
            val key = "${device.id}:$token"
            val previous = lastSent[key]
            if (previous != null && Wire.encode(previous) == Wire.encode(state)) continue
            val payload = buildJsonObject {
                put("aps", buildJsonObject {
                    put("timestamp", JsonPrimitive(timestamp))
                    put("event", JsonPrimitive("update"))
                    put("content-state", state)
                })
            }
            // Only sent while a question waits, so always at once.
            val outcome = apns.deliver(device, "live_activity_token", headers(10), Wire.encode(payload))
            if (outcome == "sent") lastSent[key] = state else lastSent.remove(key)
        }
        if (requestId == null) return
        val asked = app.db.one("SELECT project_id FROM approvals WHERE team_id = :team AND id = ?", requestId) { it.getString(1) }
        val access = ChannelAccess(app.db, app.clock)
        for (device in devices) {
            if (device.pushToStartToken == null || device.liveActivityToken != null) continue
            val state = stateOf(device)
            if (!needed(state) || !access.canRead(Viewer.MEMBER + device.userId, asked)) continue
            if (now - (startedAt[device.id] ?: Long.MIN_VALUE / 2) < PUSH_TO_START_COOLDOWN_MS) continue
            val alert = requestAlert(requestId, state)
            startedAt[device.id] = now
            val payload = buildJsonObject {
                put("aps", buildJsonObject {
                    put("timestamp", JsonPrimitive(timestamp))
                    put("event", JsonPrimitive("start"))
                    put("content-state", state)
                    put("attributes-type", JsonPrimitive(ATTRIBUTES_TYPE))
                    put("attributes", JsonObject(emptyMap()))
                    put("alert", alert)
                })
            }
            apns.deliver(device, "push_to_start_token", headers(10), Wire.encode(payload))
        }
    }

    /** What a push-to-start for a new request says: the alert's own words for it. */
    private fun requestAlert(requestId: String, state: JsonObject): JsonObject {
        val message = messages.request(requestId)
        return buildJsonObject {
            put("title", JsonPrimitive(message?.title ?: "${state.text("topAgent") ?: "An agent"} is waiting on you"))
            put("body", JsonPrimitive(message?.body ?: state.text("topQuestion") ?: ""))
        }
    }

    /**
     * Ends [device]'s activity and forgets its token, so the server stops updating it and the next
     * question can start a new one at once (the start cooldown only guards a phone whose activity
     * never sent its token, and this one did). It goes with a whole, all-clear state and a
     * dismissal date already passed, at high priority, so iOS takes it off the lock screen and the
     * Dynamic Island at once rather than leaving an empty box there.
     */
    private fun end(device: PushDevice, token: String, state: JsonObject, timestamp: Long) {
        val payload = buildJsonObject {
            put("aps", buildJsonObject {
                put("timestamp", JsonPrimitive(timestamp))
                put("event", JsonPrimitive("end"))
                put("content-state", allClear(state))
                put("dismissal-date", JsonPrimitive(timestamp - 1))
            })
        }
        val outcome = apns.deliver(device, "live_activity_token", headers(10), Wire.encode(payload))
        if (outcome == "sent") {
            startedAt.remove(device.id)
            app.db.update("UPDATE owner_devices SET live_activity_token = NULL WHERE team_id = :team AND id = ? AND live_activity_token = ?", device.id, token)
        }
        if (outcome != "failed") lastSent.remove("${device.id}:$token")
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content

    /** The top request's options as lock-screen buttons, only when all 1-4 of them fit uncut. */
    private fun lockScreenOptions(top: JsonObject): JsonElement {
        val kind = top["kind"]?.jsonPrimitive?.content
        if (kind != "buttons" && kind != "choice") return JsonNull
        val options = top["options"] as? kotlinx.serialization.json.JsonArray ?: return JsonNull
        if (options.size !in 1..OPTIONS_MAX) return JsonNull
        return if (options.all { it.jsonPrimitive.content.length <= OPTION_MAX }) options else JsonNull
    }

    fun headers(priority: Int) = listOf(
        "apns-push-type" to "liveactivity",
        "apns-topic" to "$bundleId.push-type.liveactivity",
        "apns-priority" to priority.toString(),
    )

    companion object {
        const val ATTRIBUTES_TYPE = "AgentsActivityAttributes"
        const val OPTION_MAX = 40
        const val OPTIONS_MAX = 4
        const val COALESCE_MS = 2_000L
        const val REFRESH_MS = 60_000L
        const val PUSH_TO_START_COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * [state] with nothing waiting: every key the phone decodes, with the question fields and the
         * working list null, so the ending activity draws its calm panel and never a blank one.
         */
        fun allClear(state: JsonObject): JsonObject = buildJsonObject {
            state.forEach { (key, value) -> put(key, value) }
            put("waiting", JsonPrimitive(0))
            for (key in QUESTION_KEYS) put(key, JsonNull)
            put("working", JsonNull)
        }

        private val QUESTION_KEYS = listOf(
            "topAgent", "topQuestion", "topRequestId", "topKind", "topAgentId", "topProject", "topExpiresAt", "topOptions", "topDefault",
        )

        /**
         * Whether the activity should be up: a question waits on the owner. Work in progress alone
         * doesn't keep it, or cards left in progress would hold an activity with nothing to ask on
         * the lock screen for good. The app's `needsOwner` follows the same rule.
         */
        fun needed(state: JsonObject): Boolean = state["waiting"]!!.jsonPrimitive.int > 0

        val WATCHED = setOf(
            "request.created", "request.answered", "request.updated", "agent.presence", "agent.renamed",
            "update.created", "update.updated", "update.deleted", "message.created", "message.updated",
            "message.deleted", "project.created", "project.updated",
        )
    }
}

/** Start the iOS pushers when APNs is configured; returns how to stop them. */
fun App.startApplePush(): () -> Unit {
    val settings = config.apns ?: return {}
    val apns = dev.wildware.feed.server.push.Apns(this, settings)
    val stopAlerts = apns.start()
    val stopActivity = LiveActivity(this, apns, settings.bundleId).start()
    return { stopAlerts(); stopActivity() }
}
