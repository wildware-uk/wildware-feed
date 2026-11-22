package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.NewRequest
import dev.wildware.feed.server.domain.Secrets
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsNumber
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * `list_secrets`, `secret_url`, `create_secret_upload` and `request_secret` (docs/SECRETS.md).
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
}
