package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Telemetry
import dev.wildware.feed.server.domain.Agents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool discovery: by default `tools/list` shows only [NAMES] (`list_tools`, `describe_tools`,
 * `call_tool`), so a session doesn't pay for every schema up front. They are a thin layer over
 * [ToolRegistry]: `call_tool` runs the very same [ToolRegistry.call] a direct `tools/call` does,
 * and `describe_tools` hands back the advertised entries themselves. `/mcp?tools=all` lists every
 * tool, as before, and a direct `tools/call` by a tool's own name works in both modes.
 */
object Discovery {
    const val LIST = "list_tools"
    const val DESCRIBE = "describe_tools"
    const val CALL = "call_tool"
    val NAMES = setOf(LIST, DESCRIBE, CALL)

    /** The three tools `tools/list` shows by default, as advertised. */
    val tools: JsonArray by lazy {
        Json.parseToJsonElement(Discovery::class.java.getResource("/mcp/discovery-tools.json")!!.readText()) as JsonArray
    }

    private val schemas: Map<String, JsonObject> by lazy {
        tools.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["inputSchema"]!!.jsonObject }
    }

    /** Areas in the order list_tools shows them. A tool missing here is listed under "other". */
    val AREAS: Map<String, List<String>> = linkedMapOf(
        "messages" to listOf("get_messages", "acknowledge", "post_message", "edit_message", "delete_message", "react"),
        "updates" to listOf("post_update", "edit_update", "delete_update", "update_block", "get_blocks"),
        "work" to listOf("set_status", "list_work", "assign_work", "list_tags", "tag_card", "set_card_field", "get_card_fields"),
        "requests" to listOf("request_input", "edit_request", "withdraw_request"),
        "docs" to listOf("list_docs", "read_doc", "search_docs", "create_doc", "edit_doc"),
        "people" to listOf("list_members", "set_profile"),
        "channels" to listOf("create_project", "list_projects", "set_project_theme", "subscribe", "unsubscribe", "list_subscriptions"),
        "sessions" to listOf("register_session", "heartbeat", "end_session", "start_pairing", "await_pairing"),
        "secrets" to listOf("list_secrets", "secret_url", "create_secret_upload", "request_secret", "request_secret_access"),
        "media" to listOf("create_upload", "attach_media"),
        "apps" to listOf("list_embed_types", "register_embed_type", "list_apps", "register_app", "set_app_data"),
        "feedback" to listOf("send_feedback", "list_feedback"),
    )

    private val areaOf: Map<String, String> = AREAS.flatMap { (area, names) -> names.map { it to area } }.toMap()

    fun area(name: String) = areaOf[name] ?: "other"

    /** A name as an agent may pass it: Claude Code's `mcp__<server>__post_message` is `post_message`. */
    private fun bare(name: String) = if (name.startsWith("mcp__") && name.indexOf("__", 5) > 0) name.substringAfterLast("__") else name

    private fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * `tools/call` for any name: a discovery tool is answered here, anything else goes to
     * [ToolRegistry.call] exactly as before.
     */
    suspend fun call(app: App, agent: Agents.Agent, params: JsonObject?): ToolResult {
        val name = str(params?.get("name")) ?: ""
        if (name !in NAMES) return app.tools.call(app, agent, params)
        val started = System.nanoTime()
        var outcome = "ok"
        try {
            val (args, issues) = Schema.check(schemas.getValue(name), params?.get("arguments"))
            if (issues.isNotEmpty()) {
                outcome = "invalid"
                return ToolResult.sdkError("Input validation error: Invalid arguments for tool $name: ${issues.joinToString("\n")}", invalidArguments = true)
            }
            return when (name) {
                LIST -> list(app, str(args!!["query"]))
                DESCRIBE -> describe(app, args!!["names"]!!.jsonArray.mapNotNull { str(it) })
                // call_tool's own time and outcome are the tool's it runs, counted by ToolRegistry.call.
                else -> run(app, agent, str(args!!["name"])!!, args["arguments"] as? JsonObject).also { outcome = "" }
            }
        } finally {
            if (outcome.isNotEmpty()) Telemetry.toolCall(name, outcome, System.nanoTime() - started)
        }
    }

    private fun entries(app: App): List<JsonObject> =
        app.tools.advertised.map { it.jsonObject }.filter { app.tools.has(it["name"]!!.jsonPrimitive.content) }

    private fun list(app: App, query: String?): ToolResult {
        val words = query.orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val all = entries(app)
        val matching = all.filter { tool ->
            val name = tool["name"]!!.jsonPrimitive.content
            val haystack = listOf(name, name.replace('_', ' '), area(name), str(tool["title"]).orEmpty(), str(tool["description"]).orEmpty())
                .joinToString("\n").lowercase()
            words.all { it in haystack }
        }
        val grouped = matching.groupBy { area(it["name"]!!.jsonPrimitive.content) }
        val order = AREAS.keys + "other"
        val areas = buildJsonArray {
            for (area in order) {
                val tools = grouped[area] ?: continue
                add(buildJsonObject {
                    put("area", area)
                    put("tools", buildJsonArray {
                        for (tool in tools) add(buildJsonObject {
                            put("name", tool["name"]!!)
                            put("summary", tool["title"] ?: JsonPrimitive(""))
                        })
                    })
                })
            }
        }
        val summary = when {
            matching.isEmpty() -> "No tool matches \"${query.orEmpty().trim()}\". Call list_tools with no query to see all ${all.size}."
            words.isEmpty() -> "${all.size} dashboard tools in ${grouped.size} areas. describe_tools({names}) gives their arguments; call_tool({name, arguments}) runs one."
            else -> "${matching.size} of ${all.size} tools match \"${query!!.trim()}\". describe_tools({names}) gives their arguments; call_tool({name, arguments}) runs one."
        }
        return ToolResult.ok(summary, buildJsonObject {
            put("count", matching.size)
            put("areas", areas)
        })
    }

    private fun describe(app: App, names: List<String>): ToolResult {
        val byName = (entries(app) + tools.map { it.jsonObject }).associateBy { it["name"]!!.jsonPrimitive.content }
        val found = mutableListOf<JsonObject>()
        val unknown = mutableListOf<String>()
        for (asked in names.distinct()) {
            val tool = byName[bare(asked)]
            if (tool == null) unknown += asked else if (tool !in found) found += tool
        }
        val summary = buildString {
            append(if (found.isEmpty()) "No such tools." else "${found.size} tool${if (found.size == 1) "" else "s"}: ${found.joinToString(", ") { it["name"]!!.jsonPrimitive.content }}. Run one with call_tool({name, arguments}).")
            if (unknown.isNotEmpty()) append(" Unknown: ${unknown.joinToString(", ")}. Call list_tools to see every name.")
        }
        return ToolResult.ok(summary, buildJsonObject {
            put("tools", JsonArray(found))
            if (unknown.isNotEmpty()) put("unknown", JsonArray(unknown.map(::JsonPrimitive)))
        })
    }

    private suspend fun run(app: App, agent: Agents.Agent, asked: String, arguments: JsonObject?): ToolResult {
        val name = bare(asked)
        if (name in NAMES) {
            Telemetry.toolCall(CALL, "invalid", 0)
            return ToolResult.sdkError("$name is not run through call_tool: call it directly", invalidArguments = true)
        }
        if (!app.tools.has(name)) {
            Telemetry.toolCall("unknown", "invalid", 0)
            return ToolResult.sdkError("Tool $asked not found. Call list_tools to see every tool name, or list_tools({query}) to search them.")
        }
        val result = app.tools.call(app, agent, buildJsonObject {
            put("name", name)
            // Left out, they are none: a tool that takes no arguments needs no `arguments: {}`.
            put("arguments", arguments ?: JsonObject(emptyMap()))
        })
        return if (result.invalidArguments) result.withHint("Call describe_tools({names: [\"$name\"]}) for its arguments.") else result
    }
}
