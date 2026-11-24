package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.Groups
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.Js
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Who is calling and with what: the agent is fixed by its token; no tool takes an agent argument. */
class ToolCall(val app: App, val agent: Agents.Agent, val args: JsonObject) {
    val author get() = "agent:${agent.id}"

    /**
     * The channel the call names: `channel`, or `project`, its older name (migration 053). Both may
     * be sent only when they say the same.
     */
    fun channel(): String? {
        val channel = (args["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val project = (args["project"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (channel != null && project != null && channel.trim().lowercase() != project.trim().lowercase()) {
            throw DomainError("invalid_argument", "pass channel or project (its older name), not both", 400)
        }
        return channel ?: project
    }

    /** [channel], where one is needed. */
    fun requiredChannel(): String = channel() ?: throw DomainError("invalid_argument", "channel is required", 400)
}

/** A tool's answer, in the TS `ok`/`failed` envelope (spec/05-mcp.md §3). */
class ToolResult private constructor(val json: JsonObject, val invalidArguments: Boolean = false) {
    /** The same answer with [hint] added to its first text: how a discovery tool points at the next step. */
    fun withHint(hint: String): ToolResult {
        val content = json["content"] as? JsonArray ?: return this
        val first = content.firstOrNull() as? JsonObject ?: return this
        val text = (first["text"] as? JsonPrimitive)?.content ?: return this
        val hinted = JsonObject(first + ("text" to JsonPrimitive("$text\n$hint")))
        return ToolResult(JsonObject(json + ("content" to JsonArray(listOf(hinted) + content.drop(1)))), invalidArguments)
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** `<summary>\n<JSON.stringify(data, null, 2)>`, with [data] as structuredContent. */
        fun ok(summary: String, data: JsonObject, extra: List<JsonElement> = emptyList()) = ToolResult(buildJsonObject {
            put("content", JsonArray(listOf(text("$summary\n${Js.escapeLoneSurrogates(pretty.encodeToString(JsonElement.serializer(), data))}")) + extra))
            put("structuredContent", data)
        })

        fun failed(code: String, message: String) = ToolResult(buildJsonObject {
            put("content", JsonArray(listOf(text("$code: $message"))))
            put("structuredContent", buildJsonObject { put("error", JsonPrimitive(code)); put("message", JsonPrimitive(message)) })
            put("isError", JsonPrimitive(true))
        })

        /** What the MCP SDK sends for an unknown tool or bad arguments: text only, no structuredContent. */
        fun sdkError(message: String, invalidArguments: Boolean = false) = ToolResult(buildJsonObject {
            put("content", JsonArray(listOf(text("MCP error -32602: $message"))))
            put("isError", JsonPrimitive(true))
        }, invalidArguments)

        private fun text(t: String) = buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(t)) }
    }
}

typealias ToolHandler = suspend (ToolCall) -> ToolResult

/**
 * Every tool, by name. The advertised schemas are the TS server's, verbatim (`/mcp/tools-list.json`);
 * arguments are checked against them before a handler runs, with the SDK's wording.
 */
class ToolRegistry {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private val handlers = LinkedHashMap<String, ToolHandler>()

    /** Every tool as `tools/list` advertises it, in order: name, title, description, inputSchema, annotations. */
    val advertised: JsonArray by lazy {
        Json.parseToJsonElement(ToolRegistry::class.java.getResource("/mcp/tools-list.json")!!.readText()) as JsonArray
    }

    val schemas: Map<String, JsonObject> by lazy {
        advertised.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["inputSchema"]!!.jsonObject }
    }

    fun has(name: String) = name in handlers

    fun register(name: String, handler: ToolHandler) {
        require(name in schemas) { "no advertised schema for tool $name" }
        handlers[name] = handler
    }

    /**
     * The SDK checks a `tools/call` request's own params before it looks for the tool: params an
     * object, `name` a string, `arguments` (when sent) a record. Failing that, the whole call is a
     * JSON-RPC error -32603 whose message is zod's issue list, pretty-printed. Null when they are fine.
     */
    fun paramsError(params: JsonElement?): String? {
        fun issue(expected: String, path: List<String>, got: JsonElement?) = buildJsonObject {
            put("expected", JsonPrimitive(expected))
            put("code", JsonPrimitive("invalid_type"))
            put("path", JsonArray(path.map(::JsonPrimitive)))
            put("message", JsonPrimitive("Invalid input: expected $expected, received ${Schema.received(got)}"))
        }
        val issues = mutableListOf<JsonObject>()
        if (params !is JsonObject) {
            issues += issue("object", listOf("params"), params)
        } else {
            val name = params["name"]
            if (name !is JsonPrimitive || !name.isString) issues += issue("string", listOf("params", "name"), name)
            if ("arguments" in params && params["arguments"] !is JsonObject) {
                issues += issue("record", listOf("params", "arguments"), params["arguments"])
            }
        }
        return if (issues.isEmpty()) null else pretty.encodeToString(JsonElement.serializer(), JsonArray(issues))
    }

    /**
     * A channel reference that names a group instead says so (migration 053): the app calls a group
     * a project, so an agent told "the X project" may pass a group's slug as `project`.
     */
    private fun groupHint(app: App, e: DomainError): String {
        val prefix = "no such project: "
        if (e.code != "not_found" || !e.message.startsWith(prefix)) return e.message
        val ref = e.message.removePrefix(prefix)
        if (runCatching { Groups(app.feed).find(ref) }.getOrNull() == null) return e.message
        return "${e.message} (that is a project, a group of channels: pass it as group where a tool takes one, " +
            "or name one of its channels; list_projects shows them)"
    }

    /**
     * `tools/call`, as the SDK would answer it. Each call is counted by tool and outcome (Telemetry):
     * `ok`, `refused` (the tool said no), `invalid` (no such tool, or bad arguments), `failed` (a bug).
     */
    suspend fun call(app: App, agent: Agents.Agent, params: JsonObject?): ToolResult {
        val started = System.nanoTime()
        val name = (params?.get("name") as? JsonPrimitive)?.content ?: ""
        var outcome = "ok"
        try {
            val handler = handlers[name] ?: return ToolResult.sdkError("Tool $name not found").also { outcome = "invalid" }
            val raw = params?.get("arguments")
            val (args, issues) = Schema.check(schemas.getValue(name), raw)
            if (issues.isNotEmpty()) {
                outcome = "invalid"
                return ToolResult.sdkError("Input validation error: Invalid arguments for tool $name: ${issues.joinToString("\n")}", invalidArguments = true)
            }
            // A suspended team is read-only: its agents can read, not write (docs/HOSTING.md §7).
            val team = app.teams.current()
            if (team != null && team.suspended && !dev.wildware.feed.server.http.Suspension.readOnlyTool(name)) {
                outcome = "refused"
                return ToolResult.failed("suspended", dev.wildware.feed.server.http.Suspension.message(team))
            }
            return try {
                // Every tool sees as this agent does (spec/15): a channel it has no access to doesn't exist for it.
                kotlinx.coroutines.withContext(dev.wildware.feed.server.db.Viewer.element(dev.wildware.feed.server.db.Viewer.agent(agent.id))) {
                    handler(ToolCall(app, agent, args!!))
                }
            } catch (e: DomainError) {
                outcome = "refused"
                ToolResult.failed(e.code, groupHint(app, e))
            } catch (e: Exception) {
                outcome = if (e is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
                System.err.println("mcp tool failed: $e")
                e.printStackTrace()
                if (outcome == "failed") {
                    dev.wildware.feed.server.Telemetry.event(
                        dev.wildware.feed.server.Telemetry.Level.ERROR, "mcp.tool_failed", "mcp.tool" to name, "error.type" to dev.wildware.feed.server.Telemetry.errorType(e),
                    )
                }
                ToolResult.failed("internal_error", "the dashboard failed to handle this call; see server logs")
            }
        } finally {
            // Only a registered name is a label: anything an agent typed would make a series of its own.
            dev.wildware.feed.server.Telemetry.toolCall(if (name in handlers) name else "unknown", outcome, System.nanoTime() - started)
        }
    }
}
