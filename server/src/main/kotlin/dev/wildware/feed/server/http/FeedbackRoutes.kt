package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Feedback
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A team's feedback and feature requests (server/spec/14-feedback.md): anyone on the team sends
 * one, and sees the team's own with their status and the product team's reply.
 */
fun Route.feedbackRoutes(app: App) {
    endpoint(
        "/api/feedback",
        HttpMethod.Get to {
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotEmpty() }
            val items = app.feedback.list(status)
            call.respondOwner(200, buildJsonObject {
                put("recipient", app.feedback.recipient)
                put("sends", app.feedback.sends)
                put("kinds", JsonArray(Feedback.KINDS.map { JsonPrimitive(it) }))
                put("areas", JsonArray(Feedback.AREAS.map { JsonPrimitive(it) }))
                put("feedback", JsonArray(items.map(app.feedback::ownerView)))
            })
        },
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val item = app.feedback.submitByPerson(call.person, inputOf(body))
            call.respondOwner(201, buildJsonObject { put("feedback", app.feedback.ownerView(item)) })
        },
    )
}

/** The owner API's body: `{kind, area?, title, body, mediaIds?, card?: {kind, id}, includeEmail?, client?}`. */
private fun inputOf(body: JsonObject): Feedback.Input {
    val card = when (val c = body["card"]) {
        null, is JsonNull -> null
        is JsonObject -> CardRef(c["kind"].jsString ?: throw invalid("card.kind is required"), c["id"].jsString ?: throw invalid("card.id is required"))
        else -> throw invalid("card must be {kind, id}")
    }
    val media = when (val m = body["mediaIds"]) {
        null, is JsonNull -> null
        is JsonArray -> m.map { it.jsString ?: throw invalid("mediaIds must be a list of media ids") }
        else -> throw invalid("mediaIds must be a list of media ids")
    }
    return Feedback.Input(
        kind = body["kind"].jsString ?: throw invalid("kind is required: feedback or feature_request"),
        area = body["area"].jsString,
        title = body["title"].jsString ?: throw invalid("title is required"),
        body = body["body"].jsString ?: throw invalid("body is required"),
        mediaIds = media,
        card = card,
        includeEmail = body["includeEmail"]?.let { it.jsBoolean ?: throw invalid("includeEmail must be true or false") } ?: false,
        client = body["client"].jsString,
    )
}
