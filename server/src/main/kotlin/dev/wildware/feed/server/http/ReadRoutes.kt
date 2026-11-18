package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.Reads
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsTrim
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The read-only owner API (phase 1): summary, snapshots, messages, project tags, the bell and push
 * status. [lastSeq] is the event bus's newest seq, read before any state (spec/03 §9).
 */
fun Route.readRoutes(app: App, lastSeq: () -> Long) {
    val reads = app.reads

    /** `{seq, at, ...state}` with seq read before the state. */
    suspend fun RoutingContext.stamped(state: () -> List<Pair<String, JsonElement>>) {
        val seq = lastSeq()
        val body = state()
        call.respondOwner(200, buildJsonObject {
            put("seq", seq)
            put("at", Wire.isoOf(app.clock()))
            body.forEach { (k, v) -> put(k, v) }
        })
    }

    endpoint("/api/summary", HttpMethod.Get to { call.respondOwner(200, reads.summary(Wire::isoOf)) })

    endpoint(
        "/api/snapshot",
        HttpMethod.Get to {
            val query = SnapshotQuery.of(call.request.queryParameters)
            stamped {
                val projects = reads.listProjects(query.status)
                val messages = reads.listThread(Reads.ThreadQuery(project = query.project))
                val updates = reads.updatesPage(query.project, query.limit, query.cursor)
                val messageIds = messages.map { (it["id"] as JsonPrimitive).content }
                val scope = Reads.CardScope(
                    update = updates.ids,
                    message = messageIds,
                    request = reads.pendingRequests().map { (it["id"] as JsonPrimitive).content },
                )
                val cardTags = reads.cardTags(scope)
                val cardFields = reads.cardFields(scope)
                val cardApps = reads.cardApps(scope)
                listOf(
                    "projects" to JsonArray(projects),
                    "tags" to JsonArray(reads.listTags(query.project)),
                    "cardTags" to JsonArray(cardTags),
                    "cardFields" to JsonArray(cardFields),
                    "cardApps" to JsonArray(cardApps),
                    "updates" to updates.json,
                    "agentNames" to reads.agentNames(),
                    "messages" to JsonArray(messages),
                    "acks" to JsonArray(reads.acksForMessages(messageIds)),
                    "messageMedia" to reads.byMessage(messageIds) { reads.mediaFor("message_id", it) },
                    "messageEmbeds" to reads.byMessage(messageIds) { reads.embedsFor("message_id", it) },
                )
            }
        },
    )
    endpoint(
        "/api/snapshot/updates",
        HttpMethod.Get to {
            val query = SnapshotQuery.of(call.request.queryParameters)
            stamped { listOf("updates" to reads.updatesPage(query.project, query.limit, query.cursor).json) }
        },
    )
    endpoint(
        "/api/snapshot/requests",
        HttpMethod.Get to { stamped { listOf("requests" to JsonArray(reads.requestsSnapshot())) } },
    )
    endpoint(
        "/api/snapshot/agents",
        HttpMethod.Get to { stamped { listOf("agents" to JsonArray(reads.liveAgents())) } },
    )

    endpoint(
        "/api/messages",
        HttpMethod.Get to {
            val query = threadQuery(call.request.queryParameters)
            stamped {
                val messages = reads.listThread(query)
                val ids = messages.map { (it["id"] as JsonPrimitive).content }
                listOf(
                    "messages" to JsonArray(messages),
                    "acks" to JsonArray(reads.acksForMessages(ids)),
                    "media" to reads.byMessage(ids) { reads.mediaFor("message_id", it) },
                    "embeds" to reads.byMessage(ids) { reads.embedsFor("message_id", it) },
                    "deliveries" to JsonArray(reads.deliveriesFor(ids)),
                    "reactions" to JsonArray(reads.reactionsFor("message", ids)),
                )
            }
        },
    )

    endpoint(
        "/api/projects/{reference}/tags",
        HttpMethod.Get to {
            val tags = reads.listTags(call.parameters["reference"] ?: "")
            call.respondOwner(200, buildJsonObject { put("tags", JsonArray(tags)) })
        },
    )

    endpoint(
        "/api/notifications",
        HttpMethod.Get to {
            val params = call.request.queryParameters
            val unseenOnly = params["unseen"] == "true"
            // `Number(param ?? '')`: whole and positive, capped at 100; anything else is 50.
            val asked = Js.number(params["limit"] ?: "")
            val limit = if (Js.isInteger(asked) && asked > 0) minOf(asked, 100.0).toInt() else 50
            stamped {
                listOf(
                    "notifications" to JsonArray(reads.notifications(unseenOnly, limit)),
                    "unseen" to JsonPrimitive(reads.unseenNotifications()),
                )
            }
        },
    )

    endpoint(
        "/api/push",
        HttpMethod.Get to {
            val vapid = app.config.vapid
            val devices = reads.pushDevices()
            call.respondOwner(200, buildJsonObject {
                put("enabled", vapid != null)
                put("publicKey", vapid?.publicKey?.let(::JsonPrimitive) ?: JsonNull)
                put("subscriptions", devices.size)
                put("devices", JsonArray(devices))
            })
        },
    )
}

/**
 * `readSnapshotQuery` (TS `src/http/stream/snapshot.ts`): a malformed filter is dropped, never
 * refused; only an unknown project (404) or a foreign cursor (400) fails, later, in the read.
 */
internal data class SnapshotQuery(val limit: Int, val project: String?, val cursor: String?, val status: String?) {
    companion object {
        const val DEFAULT_LIMIT = 50

        fun of(params: Parameters): SnapshotQuery {
            val limit = params["limit"]?.let { raw ->
                if (!Regex("^\\d+$").matches(raw.jsTrim())) return@let null
                val n = Js.number(raw)
                if (Js.isSafeInteger(n) && n > 0) n else null
            }
            return SnapshotQuery(
                // Anything past 200 is capped by the read, so an Int is plenty.
                limit = limit?.let { minOf(it, 1_000_000.0).toInt() } ?: DEFAULT_LIMIT,
                project = params["project"]?.jsTrim()?.takeIf { it.isNotEmpty() },
                cursor = params["cursor"]?.jsTrim()?.takeIf { it.isNotEmpty() },
                status = params["status"]?.takeIf { it == "active" || it == "archived" },
            )
        }
    }
}

/** `readThreadQuery` (TS `src/http/owner/messages.ts`): empty values are ignored, except `limit`. */
internal fun threadQuery(params: Parameters): Reads.ThreadQuery {
    fun given(name: String) = params[name]?.takeIf { it.isNotEmpty() }
    val limit = params["limit"]?.let {
        if (!Regex("^[0-9]+$").matches(it)) throw invalid("limit must be a positive integer")
        Js.number(it)
    }
    return Reads.ThreadQuery(
        updateId = given("update"),
        requestId = given("request"),
        taskId = given("task"),
        replyTo = given("replyTo"),
        project = given("project"),
        limit = limit,
    )
}
