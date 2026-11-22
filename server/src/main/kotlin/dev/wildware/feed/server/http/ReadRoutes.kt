package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.Reads
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsTrim
import dev.wildware.feed.server.domain.notFound
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

    // A suspended team's summary says so (docs/TEAMS.md §4: the one key it gains, add-only); others are as before.
    endpoint("/api/summary", HttpMethod.Get to {
        val summary = reads.summary(Wire::isoOf)
        val suspended = call.attributes.getOrNull(TeamKey)?.suspended == true
        call.respondOwner(200, if (suspended) kotlinx.serialization.json.JsonObject(summary + ("suspended" to kotlinx.serialization.json.JsonPrimitive(true))) else summary)
    })

    endpoint(
        "/api/snapshot",
        HttpMethod.Get to {
            val query = SnapshotQuery.of(call.request.queryParameters)
            stamped { reads.snapshot(Reads.SnapshotRequest(query.project, query.status, query.limit, query.cursor)) + people(app) }
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
        HttpMethod.Get to {
            stamped {
                // On a team (migration 055), what was answered lately too, with who answered it:
                // the apps keep those in the feed. A team of one reads exactly as before.
                val answered = if (app.people.isTeam()) listOf("answered" to JsonArray(reads.answeredRequests())) else emptyList()
                listOf("requests" to JsonArray(reads.requestsSnapshot())) + answered
            }
        },
    )
    endpoint(
        "/api/snapshot/agents",
        HttpMethod.Get to { stamped { listOf("agents" to JsonArray(reads.liveAgents())) } },
    )

    endpoint(
        "/api/messages",
        HttpMethod.Get to {
            val query = threadQuery(call.request.queryParameters)
            stamped { reads.messagesPage(query) }
        },
    )

    // One card and its thread, by its id or a reply's: how the apps open a card the feed hasn't paged
    // to, on its own, instead of reading every page down to it. Hidden or missing is a 404.
    endpoint(
        "/api/threads/{id}",
        HttpMethod.Get to {
            val id = call.parameters["id"].orEmpty()
            stamped { reads.cardThread(id) ?: throw notFound("no such card: $id") }
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
                    "notifications" to JsonArray(reads.notifications(unseenOnly, limit, call.person.id)),
                    "unseen" to JsonPrimitive(reads.unseenNotifications(call.person.id)),
                )
            }
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

/**
 * The snapshot's `people` (migration 055): everyone who has been in the team, so the apps can name
 * who wrote or answered what. Only on a team: a team of one's snapshot reads as it always has.
 */
private fun people(app: dev.wildware.feed.server.App): List<Pair<String, JsonElement>> {
    if (!app.people.isTeam()) return emptyList()
    return listOf("people" to JsonArray(app.people.list().map { personView(it, email = false) }))
}
