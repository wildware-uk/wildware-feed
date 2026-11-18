package dev.wildware.feed.server.domain

import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Expected values are what Node prints for the same input. */
class JsTest {
    @Test
    fun `numbers print as JavaScript prints them`() {
        val cases = listOf(
            1e21 to "1e+21", 123456789012345680000.0 to "123456789012345680000", 1.5e-7 to "1.5e-7",
            0.000001 to "0.000001", 1e-7 to "1e-7", -0.0 to "0", 0.1 + 0.2 to "0.30000000000000004",
            5e-324 to "5e-324", 1.7976931348623157e308 to "1.7976931348623157e+308", 100.0 to "100",
            2.5 to "2.5", -12.75 to "-12.75", 1e23 to "1e+23", 1e-323 to "1e-323", 2.5e-323 to "2.5e-323", 123e-20 to "1.23e-18",
        )
        for ((d, want) in cases) assertEquals(want, Js.numberToString(d), "String($d)")
    }

    @Test
    fun `Number of a string follows JavaScript`() {
        val cases = listOf(
            "0x10" to 16.0, "1e1" to 10.0, " 7 " to 7.0, "" to 0.0, "2.5" to 2.5, "0b101" to 5.0, "0o17" to 15.0,
            "Infinity" to Double.POSITIVE_INFINITY, " 7 " to 7.0,
        )
        for ((s, want) in cases) assertEquals(want, Js.number(s), "Number(\"$s\")")
        assertTrue(Js.number("-0x10").isNaN())
        assertTrue(Js.number("abc").isNaN())
    }

    @Test
    fun `a stored JSON column comes back in JavaScript's key order and number spelling`() {
        val stored = """{"b":1,"2":2,"a":3,"1":4,"01":5,"4294967295":6,"4294967294":7,"x":1.50,"y":1E2,"z":12345678901234567890}"""
        assertEquals(
            """{"1":4,"2":2,"4294967294":7,"b":1,"a":3,"01":5,"4294967295":6,"x":1.5,"y":100,"z":12345678901234567000}""",
            Wire.encode(Js.parse(stored)),
        )
    }

    @Test
    fun `strings are escaped as JSON stringify escapes them`() {
        val s = "a\u0000\u001f\b\u000c\n\r\t\"\\\u007f /é"
        assertEquals("\"a\\u0000\\u001f\\b\\f\\n\\r\\t\\\"\\\\\u007f /é\"", Wire.encode(JsonPrimitive(s)))
    }

    @Test
    fun `encodeURIComponent leaves what JavaScript leaves`() {
        assertEquals("01AB%20%C3%BC%2F%3F*()!~'%22%C3%A9%F0%9F%98%80", Js.encodeURIComponent("01AB ü/?*()!~'\"é😀"))
    }
}
