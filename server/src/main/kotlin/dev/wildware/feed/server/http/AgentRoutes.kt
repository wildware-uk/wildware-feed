package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Agents
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent-facing routes. Phase 1 has the front door only: token checks, the per-token limit and the
 * MCP handshake (spec/05-mcp.md §1-2). Tool calls arrive in phase 3, the agent stream in phase 2.
 */
fun Route.agentRoutes(app: App) {
    endpoint("/mcp", HttpMethod.Post to { call.mcp(app) })
    endpoint(
        "/api/agent/stream",
        HttpMethod.Get to {
            val agent = call.agentFor(app, app.agentStreamLimiter, mcpStyle = false) ?: return@to
            val token = app.agents.parseBearer(call.request.headers["Authorization"]).first!!
            kotlinx.coroutines.withContext(dev.wildware.feed.server.db.Viewer.element(dev.wildware.feed.server.db.Viewer.agent(agent.id))) {
                call.agentStream(app, agent, token)
            }
        },
    )
}

private fun resource(path: String) = Mcp::class.java.getResource(path)!!.readText()

/** The rules, with how to find tools put in where `{{tools}}` stands: discovery's text by default, nothing with every tool listed. */
private val instructions by lazy { resource("/mcp/instructions.txt").replace("{{tools}}\n\n", resource("/mcp/instructions-discovery.txt").trimEnd() + "\n\n") }
private val instructionsAll by lazy { resource("/mcp/instructions.txt").replace("{{tools}}\n\n", "") }

object Mcp {
    val SUPPORTED = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07")

    /**
     * `/mcp?tools=all` lists every tool, as before tool discovery; plain `/mcp` lists only
     * list_tools, describe_tools and call_tool (see [dev.wildware.feed.server.mcp.Discovery]).
     */
    const val ALL_TOOLS_PARAM = "tools"
    const val ALL_TOOLS_VALUE = "all"
}

/**
 * Who the bearer is, or the refusal already sent. MCP refusals are JSON-RPC errors with a
 * WWW-Authenticate challenge; the agent stream's are plain `{error, message}` (spec/01 §7.4).
 */
private suspend fun ApplicationCall.agentFor(app: App, limiter: dev.wildware.feed.server.auth.SlidingLimiter, mcpStyle: Boolean): Agents.Agent? {
    suspend fun refuse(status: Int, code: String, message: String, retryAfter: Long? = null) {
        if (!mcpStyle) return respondJson(status, Wire.error(code, message))
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonNull)
            put("error", buildJsonObject {
                put("code", JsonPrimitive(-32000))
                put("message", JsonPrimitive(message))
                put("data", buildJsonObject { put("error", JsonPrimitive(code)) })
            })
        }
        val headers = buildList {
            if (status == 401) {
                // resource_metadata is how an MCP client finds where to sign in (RFC 9728 §5.1).
                val discovery = "resource_metadata=\"${OAuthUrls(app.teams.address()).resourceMetadata}\", scope=\"${dev.wildware.feed.server.domain.OAuth.SCOPE}\""
                add(
                    "WWW-Authenticate" to if (code == "missing_token") "Bearer $discovery"
                    else "Bearer error=\"invalid_token\", error_description=\"${quotable(message)}\", $discovery",
                )
            }
            if (retryAfter != null) add("Retry-After" to retryAfter.toString())
        }
        respondJson(status, body, Wire.JSON, *headers.toTypedArray())
    }

    val (token, refusal) = app.agents.parseBearer(request.headers["Authorization"])
    if (refusal != null) { refuse(401, refusal.code, refusal.message); return null }
    val (agent, denied) = app.agents.authenticate(token!!)
    if (denied != null) { refuse(401, denied.code, denied.message); return null }
    // Counted per agent, not per token: an OAuth client refreshing for new tokens gets no more.
    val verdict = limiter.take(agent!!.id)
    if (!verdict.allowed) {
        val s = verdict.retryAfterSeconds
        refuse(429, "rate_limited", "rate limit exceeded for this token: retry after ${s}s", s)
        return null
    }
    // With many teams, a team's agents together get [App.teamMcpLimiter]'s share too (docs/HOSTING.md §7).
    if (app.config.multiTeam && mcpStyle) {
        val team = app.teamMcpLimiter.take(dev.wildware.feed.server.db.Tenant.id)
        if (!team.allowed) {
            val s = team.retryAfterSeconds
            refuse(429, "rate_limited", "rate limit exceeded for this team: retry after ${s}s", s)
            return null
        }
    }
    return agent
}

private fun quotable(message: String) =
    message.map { if (it.code in 0x20..0x7e) it else ' ' }.joinToString("").replace("\"", "").replace("\\", "")

private fun transportError(code: Int, message: String) =
    """{"jsonrpc":"2.0","error":{"code":$code,"message":${Wire.encode(JsonPrimitive(message))}},"id":null}"""

private suspend fun ApplicationCall.mcp(app: App) {
    val agent = agentFor(app, app.mcpLimiter, mcpStyle = true) ?: return

    val accept = request.headers["Accept"].orEmpty()
    if (!accept.contains("application/json") || !accept.contains("text/event-stream")) {
        return respondRaw(406, transportError(-32000, "Not Acceptable: Client must accept both application/json and text/event-stream"), Wire.JSON)
    }
    val type = request.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
    if (type != "application/json") {
        return respondRaw(415, transportError(-32000, "Unsupported Media Type: Content-Type must be application/json"), Wire.JSON)
    }
    val parsed = Wire.parseStrict(bodyText(BodyLimits.MCP).orEmpty())
        ?: return respondRaw(400, transportError(-32700, "Parse error: Invalid JSON"), Wire.JSON)
    val messages = (parsed as? JsonArray)?.toList() ?: listOf(parsed)
    if (messages.isEmpty() || messages.any { it !is JsonObject || it["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0" }) {
        return respondRaw(400, transportError(-32700, "Parse error: Invalid JSON-RPC message"), Wire.JSON)
    }
    val isInitialize = { m: JsonElement -> (m as JsonObject)["method"]?.jsonPrimitive?.contentOrNull == "initialize" }
    if (messages.size > 1 && messages.any(isInitialize)) {
        return respondRaw(400, transportError(-32600, "Invalid Request: Only one initialization request is allowed"), Wire.JSON)
    }
    val version = request.headers["mcp-protocol-version"]
    if (version != null && version !in Mcp.SUPPORTED && messages.none(isInitialize)) {
        return respondRaw(
            400,
            transportError(-32000, "Bad Request: Unsupported protocol version: $version (supported versions: ${Mcp.SUPPORTED.joinToString(", ")})"),
            Wire.JSON,
        )
    }

    val requests = messages.map { it as JsonObject }.filter { "id" in it && "method" in it }
    if (requests.isEmpty()) return respondRaw(202, "", null)
    // Each call in a batch counts against the rate limits, as it would sent alone; the POST took one.
    if (requests.size > 1) {
        val more = requests.size - 1
        val limits = listOfNotNull(app.mcpLimiter to agent.id, (app.teamMcpLimiter to dev.wildware.feed.server.db.Tenant.id).takeIf { app.config.multiTeam })
        val taken = mutableListOf<Pair<dev.wildware.feed.server.auth.SlidingLimiter, String>>()
        for ((limiter, key) in limits) {
            val verdict = limiter.take(key, more)
            if (!verdict.allowed) {
                taken.forEach { (l, k) -> l.giveBack(k, more) }
                val s = verdict.retryAfterSeconds
                return respondRaw(429, transportError(-32000, "rate limit exceeded: this batch holds more calls than are left; retry after ${s}s"), Wire.JSON, "Retry-After" to s.toString())
            }
            taken += limiter to key
        }
    }
    val all = request.queryParameters[Mcp.ALL_TOOLS_PARAM] == Mcp.ALL_TOOLS_VALUE
    // Every tool sees as this agent does (spec/15): channels it has no access to don't exist for it.
    val answers = kotlinx.coroutines.withContext(dev.wildware.feed.server.db.Viewer.element(dev.wildware.feed.server.db.Viewer.agent(agent.id))) {
        requests.map { answer(app, agent, it, all) }
    }
    respondRaw(200, if (parsed is JsonArray) Wire.encode(JsonArray(answers)) else Wire.encode(answers.single()), Wire.JSON)
}

private suspend fun answer(app: App, agent: Agents.Agent, request: JsonObject, all: Boolean): JsonElement {
    val id = request["id"] ?: JsonNull
    fun result(value: JsonElement) = buildJsonObject {
        put("result", value)
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
    }
    fun error(code: Int, message: String) = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("error", buildJsonObject { put("code", JsonPrimitive(code)); put("message", JsonPrimitive(message)) })
    }
    val params = request["params"] as? JsonObject
    return when (request["method"]?.jsonPrimitive?.contentOrNull) {
        "initialize" -> {
            val asked = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
            result(buildJsonObject {
                put("protocolVersion", JsonPrimitive(if (asked in Mcp.SUPPORTED) asked else Mcp.SUPPORTED.first()))
                // No listChanged: these are plain POSTs with no stream back, so the server could never
                // send notifications/tools/list_changed. Plain /mcp's three tools never change anyway:
                // list_tools answers from the live server, so a new tool is usable without a restart.
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) }) })
                put("serverInfo", buildJsonObject { put("name", JsonPrimitive("agent-dashboard")); put("version", JsonPrimitive("0.1.0")) })
                put("instructions", JsonPrimitive(if (all) instructionsAll else instructions))
            })
        }
        "ping" -> result(JsonObject(emptyMap()))
        "tools/list" -> result(buildJsonObject { put("tools", if (all) app.tools.advertised else dev.wildware.feed.server.mcp.Discovery.tools) })
        // A tool's own name works in both modes, so sessions connected before discovery carry on.
        "tools/call" -> app.tools.paramsError(request["params"])?.let { error(-32603, it) }
            ?: result(dev.wildware.feed.server.mcp.Discovery.call(app, agent, params).json)
        else -> error(-32601, "Method not found")
    }
}
