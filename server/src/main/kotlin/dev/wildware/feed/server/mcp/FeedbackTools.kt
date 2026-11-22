package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Feedback
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `send_feedback`, `list_feedback` (server/spec/14-feedback.md, spec/05-mcp.md). */
fun ToolRegistry.registerFeedbackTools(app: App) {
    register("send_feedback") { call ->
        val a = call.args
        val named = listOfNotNull(
            a["update_id"].jsString?.let { CardRef("update", it) },
            a["message_id"].jsString?.let { CardRef("message", it) },
            a["request_id"].jsString?.let { CardRef("request", it) },
        )
        if (named.size > 1) throw invalid("name at most one card: update_id, message_id or request_id")
        val input = Feedback.Input(
            kind = a["kind"].jsString!!,
            area = a["area"].jsString,
            title = a["title"].jsString!!,
            body = a["body"].jsString!!,
            mediaIds = (a["media_ids"] as? JsonArray)?.map { it.jsString!! },
            card = named.firstOrNull(),
        )
        val (item, request) = app.feedback.submitByAgent(call.agent, input, call.channel())
        ToolResult.ok(
            "Your owner decides first: \"${request.question}\" is waiting on their dashboard. Nothing is sent until an owner or " +
                "an admin sends it; they may edit it, or discard it. You hear the outcome as an answer event, or call " +
                "await_request({request_id: \"${request.id}\"}). Don't send it again.",
            buildJsonObject {
                put("feedback", app.feedback.agentView(item, full = true))
                put("request_id", request.id)
                put("state", "pending")
            },
        )
    }

    register("list_feedback") { call ->
        val a = call.args
        val mine = a["mine"].jsBoolean ?: false
        val limit = a["limit"].jsNumber?.toInt() ?: 50
        val items = app.feedback.list(a["status"].jsString, if (mine) call.agent.id else null, limit.coerceIn(1, 100))
        val summary = if (items.isEmpty()) "Your team has sent no feedback${if (mine) " from you" else ""} yet." else
            "${items.size} item${if (items.size == 1) "" else "s"}, newest first. Before you send one, check it isn't here already."
        ToolResult.ok(summary, buildJsonObject {
            put("feedback", JsonArray(items.map { app.feedback.agentView(it, full = false) }))
        })
    }
}
