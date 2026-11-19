package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
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
 * is pushed one (at most every 5 minutes) when a new request waits on the owner, or when agents
 * start working after a quiet spell. Once nothing has waited on him and nothing has been worked on
 * for [IDLE_END_MS], each activity is ended. State is kept in memory only.
 */
class LiveActivity(private val app: App, private val apns: Apns, private val bundleId: String) {
    private val messages = PushMessages(app)
    private val lastSent = HashMap<String, JsonObject>()
    private val startedAt = HashMap<String, Long>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "live-activity").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var newRequestId: String? = null
    /** Whether the last run found the owner [busy]; null before the first, so a restart starts nothing. */
    private var wasBusy: Boolean? = null
    /** Since when nothing has been [busy], or null while something is. */
    private var idleSince: Long? = null

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

    @Synchronized
    private fun schedule() {
        if (pending != null) return
        pending = timer.schedule({
            synchronized(this) { pending = null }
            runCatching { run() }.onFailure { System.err.println("live activity push failed: $it") }
        }, COALESCE_MS, TimeUnit.MILLISECONDS)
    }

    fun start(): () -> Unit {
        val stop = app.bus.subscribe { event ->
            if (event.type !in WATCHED) return@subscribe
            if (event.type == "request.created") newRequestId = (event.payload["requestId"] as? JsonPrimitive)?.content
            schedule()
        }
        val refresh = timer.scheduleAtFixedRate({ schedule() }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
        return { stop(); refresh.cancel(false); timer.shutdownNow() }
    }

    /** One run: runs one at a time on the timer thread. */
    fun run() {
        val requestId = newRequestId
        newRequestId = null
        val devices = apns.devices()
        if (devices.none { it.liveActivityToken != null || it.pushToStartToken != null }) return
        val now = app.clock()
        val state = state()
        val waiting = state["waiting"]!!.jsonPrimitive.int
        val timestamp = now / 1000
        val busy = busy(state)
        val workBegan = busy && wasBusy == false && waiting == 0
        wasBusy = busy
        idleSince = if (busy) null else idleSince ?: now
        val ending = idleSince?.let { now - it >= IDLE_END_MS } == true
        for (device in devices) {
            val token = device.liveActivityToken ?: continue
            if (ending) {
                end(device, token, state, timestamp)
                continue
            }
            val key = "${device.id}:$token"
            val previous = lastSent[key]
            if (previous != null && Wire.encode(previous) == Wire.encode(state)) continue
            val urgent = waiting > 0 || previous?.get("waiting")?.jsonPrimitive?.int != waiting
            val payload = buildJsonObject {
                put("aps", buildJsonObject {
                    put("timestamp", JsonPrimitive(timestamp))
                    put("event", JsonPrimitive("update"))
                    put("content-state", state)
                })
            }
            val outcome = apns.deliver(device, "live_activity_token", headers(if (urgent) 10 else 5), Wire.encode(payload))
            if (outcome == "sent") lastSent[key] = state else lastSent.remove(key)
        }
        val asked = requestId != null && waiting > 0
        if (!asked && !workBegan) return
        for (device in devices) {
            if (device.pushToStartToken == null || device.liveActivityToken != null) continue
            if (now - (startedAt[device.id] ?: Long.MIN_VALUE / 2) < PUSH_TO_START_COOLDOWN_MS) continue
            val alert = if (asked) requestAlert(requestId!!, state) else workAlert(state)
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

    /** What a push-to-start for agents getting to work says: who is on what. */
    private fun workAlert(state: JsonObject): JsonObject {
        val first = (state["working"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
        val inProgress = state["inProgress"]!!.jsonPrimitive.int
        return buildJsonObject {
            put("title", JsonPrimitive(first?.text("agent")?.let { "$it is working" } ?: "Agents are working"))
            put("body", JsonPrimitive(first?.text("task") ?: if (inProgress == 1) "1 task in progress" else "$inProgress tasks in progress"))
        }
    }

    /**
     * Ends [device]'s activity and forgets its token, so the server stops updating it and a later
     * request or burst of work can start a new one.
     */
    private fun end(device: PushDevice, token: String, state: JsonObject, timestamp: Long) {
        val payload = buildJsonObject {
            put("aps", buildJsonObject {
                put("timestamp", JsonPrimitive(timestamp))
                put("event", JsonPrimitive("end"))
                put("content-state", state)
                put("dismissal-date", JsonPrimitive(timestamp))
            })
        }
        val outcome = apns.deliver(device, "live_activity_token", headers(5), Wire.encode(payload))
        if (outcome == "sent") {
            app.db.update("UPDATE owner_devices SET live_activity_token = NULL WHERE id = ? AND live_activity_token = ?", device.id, token)
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
        /** How long nothing must wait on the owner or be worked on before the activity ends. */
        const val IDLE_END_MS = 10 * 60 * 1000L

        /**
         * Whether the owner has something to watch: a question waiting on him, or agents online
         * with cards in progress. Cards left in progress while every agent is offline don't count,
         * or a forgotten card would keep the activity up for good.
         */
        fun busy(state: JsonObject): Boolean {
            val waiting = state["waiting"]!!.jsonPrimitive.int
            val inProgress = state["inProgress"]!!.jsonPrimitive.int
            val online = state["online"]!!.jsonPrimitive.int
            return waiting > 0 || (inProgress > 0 && online > 0)
        }
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
