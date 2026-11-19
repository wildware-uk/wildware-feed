package dev.wildware.feed.server.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A phone's alert choices are checked exactly as the TS server checks them. */
class AlertPrefsTest {
    private fun check(json: String) = AlertPrefs.check(Json.parseToJsonElement(json))?.toString()

    private fun refused(message: String, json: String) {
        assertEquals(message, assertFailsWith<DomainError> { check(json) }.message)
    }

    @Test
    fun `kinds levels and priorities are kept once each in the order given`() {
        assertEquals("""{"types":["request","update"]}""", check("""{"types":["request","update","request"]}"""))
        assertEquals("""{"levels":["error"],"priorities":["high","critical"]}""", check("""{"priorities":["high","critical"],"levels":["error"]}"""))
    }

    @Test
    fun `critical alerts are off unless turned on and may name agents and projects`() {
        assertEquals(
            """{"critical":true,"criticalAgents":["a1"],"criticalProjects":["fees"]}""",
            check("""{"criticalProjects":["fees"],"criticalAgents":["a1","a1"],"critical":true}"""),
        )
        assertEquals("""{"critical":false}""", check("""{"critical":false}"""))
    }

    @Test
    fun `anything it does not understand is refused`() {
        refused("types must be any of: request, update, message, comment", """{"types":["everything"]}""")
        refused("priorities must be any of: low, medium, high, critical", """{"priorities":["urgent"]}""")
        refused("types must be a list", """{"types":"request"}""")
        refused("types must be a list", """{"types":null}""")
        refused("critical must be true or false", """{"critical":"yes"}""")
        refused("critical must be true or false", """{"critical":null}""")
        refused("criticalAgents must be a list", """{"criticalAgents":"a1"}""")
        refused("criticalProjects must be a list of strings", """{"criticalProjects":[3]}""")
        refused("preferences must be an object, or null for the default", """["request"]""")
    }

    @Test
    fun `an empty object and null are both the default`() {
        assertNull(check("{}"))
        assertNull(check("null"))
    }
}
