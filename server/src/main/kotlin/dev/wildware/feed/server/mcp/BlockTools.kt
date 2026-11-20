package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.BlockReads
import dev.wildware.feed.server.domain.Cards
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.domain.notFound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Blocks (migration 057, docs/BLOCKS.md): change one in place, and read them as they are now. */
fun ToolRegistry.registerBlockTools(app: App) {
    register("update_block") { call ->
        val a = call.args
        val card = Cards.oneCard(update = a["update_id"].jsString, message = a["message_id"].jsString, request = a["request_id"].jsString)
        val blockId = a["block_id"].jsString!!
        val block = app.feed.blocks.change(
            card, blockId, call.agent.id,
            patch = a["patch"]?.takeIf { it !is JsonNull },
            block = a["block"]?.takeIf { it !is JsonNull },
            remove = a["remove"].jsBoolean == true,
        )
        if (block == null) {
            ToolResult.ok("Removed block \"$blockId\". It is gone from the card.", buildJsonObject { put("removed", true); put("block_id", blockId) })
        } else {
            // Only what changed hands back: the whole block (a long log) would fill the caller's context on every append.
            ToolResult.ok("Changed block \"$blockId\". Your owner sees it change in place.", buildJsonObject {
                put("block", buildJsonObject {
                    put("id", block["id"]!!)
                    put("type", block["type"]!!)
                    put("rev", block["rev"]!!)
                    put("updated_at", block["updated_at"]!!)
                })
            })
        }
    }

    register("get_blocks") { call ->
        val a = call.args
        val card = Cards.oneCard(update = a["update_id"].jsString, message = a["message_id"].jsString, request = a["request_id"].jsString)
        app.feed.cards.resolve(card)
        val all = BlockReads(app.db, app.clock).viewsOn(card)
        val wanted = a["block_id"].jsString
        val blocks = if (wanted == null) all else all.filter { it["id"].jsString == wanted }.ifEmpty { throw notFound("no block \"$wanted\" on that ${card.kind}") }
        val summary = if (blocks.size == 1) "1 block." else "${blocks.size} blocks."
        ToolResult.ok(summary, buildJsonObject { put("blocks", JsonArray(blocks)) })
    }
}
