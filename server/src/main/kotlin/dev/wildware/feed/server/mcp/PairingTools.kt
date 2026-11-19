package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Pairings
import dev.wildware.feed.server.http.PairingWords
import kotlinx.serialization.json.JsonPrimitive

/**
 * `start_pairing` and `await_pairing`: the pairing an agent with no token does over `/api/pair`,
 * for an agent that already holds one (perhaps shared with another session) and wants a new
 * agent of its own. Approval is the owner's every time; the caller's own token is unchanged.
 */
fun ToolRegistry.registerPairingTools(app: App) {
    register("start_pairing") { call ->
        val started = app.pairings.start(
            call.args.string("name"),
            call.args.stringOrNull("host"),
            call.args.stringOrNull("cwd"),
            address = null,
            askedBy = call.agent.id,
        )
        val data = PairingWords.started(app.config.publicBaseUrl, started, mcp = true)
        ToolResult.ok((data["instructions"] as JsonPrimitive).content, data)
    }

    register("await_pairing") { call ->
        val poll = app.pairings.await(call.args.string("pairing_id"), call.args.string("poll_secret"), Pairings.HOLD_MS)
        val data = PairingWords.poll(app.config.publicBaseUrl, poll, mcp = true)
        ToolResult.ok((data["instructions"] as JsonPrimitive).content, data)
    }
}
