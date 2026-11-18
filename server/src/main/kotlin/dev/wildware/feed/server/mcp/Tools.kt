package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DomainError
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
}

/** A tool's answer, in the TS `ok`/`failed` envelope (spec/05-mcp.md §3). */
class ToolResult private constructor(val json: JsonObject) {
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
        fun sdkError(message: String) = ToolResult(buildJsonObject {
            put("content", JsonArray(listOf(text("MCP error -32602: $message"))))
            put("isError", JsonPrimitive(true))
        })

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

    val schemas: Map<String, JsonObject> by lazy {
        val list = Json.parseToJsonElement(ToolRegistry::class.java.getResource("/mcp/tools-list.json")!!.readText()) as JsonArray
        list.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["inputSchema"]!!.jsonObject }
    }

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

    /** `tools/call`, as the SDK would answer it. */
    suspend fun call(app: App, agent: Agents.Agent, params: JsonObject?): ToolResult {
        val name = (params?.get("name") as? JsonPrimitive)?.content ?: ""
        val handler = handlers[name] ?: return ToolResult.sdkError("Tool $name not found")
        val raw = params?.get("arguments")
        val (args, issues) = Schema.check(schemas.getValue(name), raw)
        if (issues.isNotEmpty()) {
            return ToolResult.sdkError("Input validation error: Invalid arguments for tool $name: ${issues.joinToString("\n")}")
        }
        return try {
            handler(ToolCall(app, agent, args!!))
        } catch (e: DomainError) {
            ToolResult.failed(e.code, e.message)
        } catch (e: Exception) {
            System.err.println("mcp tool failed: $e")
            e.printStackTrace()
            ToolResult.failed("internal_error", "the dashboard failed to handle this call; see server logs")
        }
    }
}
