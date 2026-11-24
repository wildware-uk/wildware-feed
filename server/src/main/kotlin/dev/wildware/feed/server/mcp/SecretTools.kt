package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.NewRequest
import dev.wildware.feed.server.domain.Requests
import dev.wildware.feed.server.domain.Secrets
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.notFound
import dev.wildware.feed.server.domain.requiredText
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * `list_secrets`, `secret_url`, `create_secret_upload`, `request_secret` and `request_secret_access`
 * (docs/SECRETS.md).
 * None of them ever puts a secret's value in its result: they hand out names and single-use URLs.
 */
fun ToolRegistry.registerSecretTools(app: App) {
    register("list_secrets") { call ->
        val mine = app.secrets.visibleTo(call.agent.id)
        ToolResult.ok(
            if (!app.secrets.enabled) "Secrets are not set up on this dashboard (no SECRETS_KEY)."
            else if (mine.isEmpty()) "No secrets are shared with you. Ask for one with request_secret."
            else "${mine.size} secret(s) shared with you. Fetch one with secret_url; never print its value.",
            buildJsonObject {
                put("enabled", app.secrets.enabled)
                put("secrets", JsonArray(mine.map { s ->
                    buildJsonObject {
                        put("name", s.name)
                        put("note", s.note)
                        put("bytes", s.bytes)
                        put("updated_at", Wire.isoOf(s.updatedAt))
                        put("paused", s.pausedAt != null)
                    }
                }))
            },
        )
    }

    register("secret_url") { call ->
        val grant = app.secrets.issueRead(call.agent, call.args["name"].jsString!!)
        ToolResult.ok(
            "Single-use URL for ${grant.name}, valid 5 minutes. Use it at once in one command that writes the value " +
                "to a file and prints nothing, e.g. curl -fsS '<url>' -o secret.txt. Never cat, echo or post the value.",
            buildJsonObject {
                put("name", grant.name)
                put("url", grant.url)
                put("expires_at", Wire.isoOf(grant.expiresAt))
            },
        )
    }

    register("create_secret_upload") { call ->
        val grant = app.secrets.issueWrite(call.agent, call.args["name"].jsString!!, call.args["note"].jsString)
        ToolResult.ok(
            "Single-use upload URL for ${grant.name}, valid 5 minutes. Send the bytes from a file: " +
                "curl -fsS -T <file> '<upload_url>'. Never type the value into the command.",
            buildJsonObject {
                put("name", grant.name)
                put("upload_url", grant.url)
                put("expires_at", Wire.isoOf(grant.expiresAt))
                put("max_bytes", Secrets.VALUE_MAX_BYTES)
            },
        )
    }

    register("request_secret") { call ->
        val a = call.args
        val listed = a["fields"] as? JsonArray
        val single = a["name"].jsString
        if (listed != null && single != null) throw invalid("pass name for one secret or fields for several, not both")
        val fields = listed?.let { list ->
            app.secrets.checkRequestableFields(call.agent.id, list.map { item ->
                val o = item.jsonObject
                Secrets.Field(
                    name = o["name"].jsString!!,
                    label = o["label"].jsString,
                    hint = o["hint"].jsString,
                    multiline = o["multiline"].jsBoolean == true,
                    optional = o["optional"].jsBoolean == true,
                )
            })
        }
        val name = fields?.first()?.name
            ?: app.secrets.checkRequestable(call.agent.id, single ?: throw invalid("name the secret you need, or list several in fields"))
        val input = NewRequest(
            kind = "secret",
            question = a["question"].jsString!!,
            detail = a["detail"].jsString,
            project = call.channel(),
            update = a["update"].jsString,
            message = a["message_id"].jsString,
            timeoutS = a["timeout_s"].jsNumber,
            priority = a["priority"].jsString,
            secretName = name,
            secretFields = fields,
        )
        requestResult(app.requests.requestInput(call.agent.id, input), app.db)
    }

    // An existing secret someone else saved: the owner allows or denies it on a card in the thread.
    register("request_secret_access") { call ->
        val a = call.args
        val check = app.secrets.checkAccess(call.agent.id, a["name"].jsString!!)
        if (check is Secrets.AccessCheck.AlreadyShared) {
            return@register ToolResult.ok(
                "You can already use ${check.name}: fetch it with secret_url into a file. No card was made.",
                buildJsonObject {
                    put("state", "already_shared")
                    put("name", check.name)
                },
            )
        }
        val secret = (check as Secrets.AccessCheck.Ask).secret
        val reason = requiredText(a["reason"].jsString!!, "reason", ACCESS_REASON_MAX)
        val names = app.agents.list(includeRevoked = true).associate { it.id to it.name }
        val card = app.secrets.accessCard(secret) { names[it] }
        val lead = "${call.agent.name} asks to use secret ${secret.name}: "
        val room = Requests.QUESTION_MAX_LENGTH - lead.length
        val question = lead + if (reason.length <= room) reason else reason.take(maxOf(0, room - 1)) + "…"
        val savedBy = (card["createdByName"] as? JsonPrimitive)?.contentOrNull?.let { "Saved by $it." } ?: "Saved by a person on the team."
        val detail = listOfNotNull(
            secret.note?.let { "Note: $it" },
            savedBy,
            "Allow shares it with ${call.agent.name}, as sharing it in Settings would. The value is never shown here.",
        ).joinToString("\n")
        // Asked in a question's own thread: the card goes where that question is.
        val parent = a["request_id"].jsString?.let { app.requests.find(it) ?: throw notFound("no such request: $it") }
        val input = NewRequest(
            kind = "confirm",
            question = question,
            detail = detail,
            project = call.channel() ?: parent?.takeIf { it.messageId == null && it.updateId == null }?.projectId,
            update = a["update_id"].jsString ?: parent?.updateId?.takeIf { parent.messageId == null },
            message = a["message_id"].jsString ?: parent?.messageId,
            timeoutS = a["timeout_s"].jsNumber,
            secretAccess = card,
        )
        requestResult(app.requests.requestInput(call.agent.id, input), app.db)
    }
}

/** The most a `request_secret_access` reason may say. */
private const val ACCESS_REASON_MAX = 300
