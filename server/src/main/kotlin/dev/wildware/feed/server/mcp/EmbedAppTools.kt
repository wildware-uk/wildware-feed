package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Apps
import dev.wildware.feed.server.domain.EmbedTypes
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.jsString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Embed looks and apps (spec/05-mcp.md §7.8-7.12). Arguments arrive already checked against the
 * advertised schema. The two list tools are not guarded in the TS server, but nothing in them throws
 * a domain error, so the shared dispatcher's catch changes nothing.
 */
fun ToolRegistry.registerEmbedAppTools(app: App) {
    register("list_embed_types") { _ ->
        val types = app.feed.embedTypes.list()
        val summary = if (types.size == 1) "1 embed look." else "${types.size} embed looks."
        ToolResult.ok(summary, buildJsonObject {
            put("types", JsonArray(types.map { type ->
                buildJsonObject {
                    put("slug", type.slug)
                    put("name", type.name)
                    put("built_in", type.builtIn)
                    put("blocks", strings(EmbedTypes.blockTypes(type.spec)))
                    put("fields", strings(EmbedTypes.placeholders(type.spec)))
                }
            }))
            put("count", types.size)
        })
    }

    register("register_embed_type") { call ->
        val type = app.feed.embedTypes.register(call.agent.id, call.arg("slug")!!, call.arg("name")!!, call.args["spec"])
        ToolResult.ok(
            "Registered the \"${type.slug}\" look. Pass embeds: [{ type: \"${type.slug}\", data: { ... } }] to post_update or post_message to show one.",
            buildJsonObject {
                put("slug", type.slug)
                put("name", type.name)
                put("built_in", type.builtIn)
                put("blocks", strings(EmbedTypes.blockTypes(type.spec)))
            },
        )
    }

    register("list_apps") { call ->
        val apps = app.feed.apps.list(call.agent.id)
        val summary = if (apps.isEmpty()) "No apps are registered here yet. register_app ships one." else "${apps.size} app(s) registered."
        ToolResult.ok(summary, buildJsonObject {
            put("apps", JsonArray(apps.map { a ->
                buildJsonObject {
                    put("uri", a.uri)
                    put("name", a.name)
                    put("description", a.description)
                    put("url", a.url)
                    put("bytes", a.bytes)
                    put("csp", a.csp)
                    put("mine", a.mine)
                    put("updated_at", a.updatedAt)
                }
            }))
            put("count", apps.size)
        })
    }

    register("register_app") { call ->
        val registered = app.feed.apps.register(
            Apps.Register(
                agentId = call.agent.id,
                uri = call.arg("uri")!!,
                name = call.arg("name")!!,
                description = call.arg("description"),
                html = call.arg("html"),
                url = call.arg("url"),
                csp = call.args["csp"],
            ),
        )
        val framing = registered.url?.let { ", framing $it" } ?: ""
        ToolResult.ok(
            "Registered \"${registered.uri}\"$framing. Pass apps: [{ app: \"${registered.uri}\", data: { ... } }] to post_update or post_message to show it.",
            buildJsonObject {
                put("uri", registered.uri)
                put("name", registered.name)
                put("url", registered.url)
                put("bytes", registered.bytes)
                put("csp", registered.csp)
            },
        )
    }

    register("set_app_data") { call ->
        val view = app.feed.apps.setData(call.arg("view_id")!!, call.args["data"], call.args["title"]?.let(::Given))
        ToolResult.ok("Updated the \"${view.uri}\" app on that card.", buildJsonObject {
            put("view_id", view.id)
            put("uri", view.uri)
            put("title", view.title)
            put("updated_at", view.updatedAt)
        })
    }
}

private fun ToolCall.arg(key: String): String? = args[key].jsString

private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
