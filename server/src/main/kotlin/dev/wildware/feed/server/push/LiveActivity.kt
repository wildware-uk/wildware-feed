package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
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
 * and every minute anyway) each activity gets the new state if it changed, and a new request may
 * start one on a phone that has none (at most every 5 minutes). State is kept in memory only.
 */
class LiveActivity(private val app: App, private val apns: Apns, private val bundleId: String) {
    private val messages = PushMessages(app)
    private val lastSent = HashMap<String, JsonObject>()
    private val startedAt = HashMap<String, Long>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "live-activity").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var newRequestId: String? = null

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
        for (device in devices) {
            val token = device.liveActivityToken ?: continue
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
        if (requestId == null || waiting == 0) return
        for (device in devices) {
            if (device.pushToStartToken == null || device.liveActivityToken != null) continue
            if (now - (startedAt[device.id] ?: Long.MIN_VALUE / 2) < PUSH_TO_START_COOLDOWN_MS) continue
            val message = messages.request(requestId)
            val alert = buildJsonObject {
                put("title", JsonPrimitive(message?.title ?: "${state["topAgent"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: "An agent"} is waiting on you"))
                put("body", JsonPrimitive(message?.body ?: state["topQuestion"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: ""))
            }
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

    fun headers(priority: Int) = listOf(
        "apns-push-type" to "liveactivity",
        "apns-topic" to "$bundleId.push-type.liveactivity",
        "apns-priority" to priority.toString(),
    )

    companion object {
        const val ATTRIBUTES_TYPE = "AgentsActivityAttributes"
        const val COALESCE_MS = 2_000L
        const val REFRESH_MS = 60_000L
        const val PUSH_TO_START_COOLDOWN_MS = 5 * 60 * 1000L
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
