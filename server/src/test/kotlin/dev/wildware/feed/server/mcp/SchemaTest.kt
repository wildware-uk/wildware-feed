package dev.wildware.feed.server.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Argument checks word for word as the TS server's SDK (zod 4) reports them. */
class SchemaTest {
    private val registry = ToolRegistry()

    private fun issues(tool: String, args: String): List<String> =
        Schema.check(registry.schemas.getValue(tool), Json.parseToJsonElement(args)).second

    @Test
    fun `array items are pathed with brackets, record keys with dots`() {
        assertEquals(listOf("Invalid input: expected string, received number at media_ids[1]"),
            issues("post_update", """{"project":"p","body":"b","media_ids":["ok",1]}"""))
        assertEquals(listOf("Invalid input: expected string, received undefined at apps[0].app"),
            issues("post_update", """{"project":"p","body":"b","apps":[{}]}"""))
        assertEquals(listOf("Invalid input: expected string, received number at option_media.0"),
            issues("request_input", """{"kind":"choice","question":"q","option_media":{"0":1}}"""))
        assertEquals("a[0].b.c[2]", Schema.dotPath(listOf("a", 0, "b", "c", 2)))
    }

    @Test
    fun `a record says record`() {
        assertEquals(listOf("Invalid input: expected record, received undefined at embeds[0].data"),
            issues("post_update", """{"project":"p","body":"b","embeds":[{"type":"x"}]}"""))
        assertEquals(listOf("Invalid input: expected record, received array at option_media"),
            issues("request_input", """{"kind":"choice","question":"q","option_media":["x"]}"""))
    }

    @Test
    fun `an enum says invalid option whatever it got, missing and null included`() {
        val statuses = "Invalid option: expected one of \"todo\"|\"in_progress\"|\"blocked\"|\"done\"|\"cancelled\" at status"
        assertEquals(listOf(statuses), issues("set_status", "{}"))
        assertEquals(listOf(statuses), issues("set_status", """{"status":3}"""))
        assertEquals(listOf("Invalid option: expected one of \"thinking\"|\"read\"|\"done\" at state"), issues("acknowledge", "{}"))
        assertEquals(listOf("Invalid option: expected one of \"low\"|\"medium\"|\"high\" at priority"),
            issues("post_update", """{"project":"p","body":"b","priority":null}"""))
        assertEquals(emptyList(), issues("set_status", """{"status":null}"""))
    }

    @Test
    fun `int reports a fraction alone, and an unsafe integer as int before the other bounds`() {
        assertEquals(listOf("Invalid input: expected int, received number at limit"), issues("list_work", """{"limit":-1.5}"""))
        assertEquals(listOf("Too big: expected int to be <=9007199254740991 at limit", "Too big: expected number to be <=200 at limit"),
            issues("list_work", """{"limit":100000000000000000000}"""))
        assertEquals(listOf("Too big: expected int to be <=9007199254740991 at min"),
            issues("request_input", """{"kind":"text","question":"q","min":9007199254740992}"""))
        assertEquals(listOf("Too small: expected int to be >=-9007199254740991 at max"),
            issues("request_input", """{"kind":"text","question":"q","max":-9007199254740992}"""))
        assertEquals(listOf("Too small: expected number to be >0 at limit"), issues("list_work", """{"limit":0}"""))
    }

    @Test
    fun `tools-call params are checked before the tool, as zod's issue list`() {
        fun params(text: String?) = registry.paramsError(text?.let { Json.parseToJsonElement(it) })
        assertNull(params("""{"name":"x","arguments":{}}"""))
        assertNull(params("""{"name":"x"}"""))
        assertEquals(
            "[\n  {\n    \"expected\": \"object\",\n    \"code\": \"invalid_type\",\n    \"path\": [\n      \"params\"\n    ],\n" +
                "    \"message\": \"Invalid input: expected object, received undefined\"\n  }\n]",
            params(null),
        )
        val both = Json.parseToJsonElement(params("""{"name":5,"arguments":null}""")!!)
        assertEquals(
            listOf("Invalid input: expected string, received number", "Invalid input: expected record, received null"),
            (both as kotlinx.serialization.json.JsonArray).map { (it.jsonObject["message"] as kotlinx.serialization.json.JsonPrimitive).content },
        )
        assertEquals("record", ((both[1] as JsonObject)["expected"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
