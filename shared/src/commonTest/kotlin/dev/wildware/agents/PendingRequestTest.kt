package dev.wildware.agents

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(extra: String, kind: String = "confirm") = json.decodeFromString<PendingRequest>(
        """{"id":"r1","agentName":"Builder","question":"Ship it?","kind":"$kind","expiresAt":"2026-11-17T00:00:00Z"$extra}"""
    )

    @Test
    fun `a request without detail or a default still reads`() {
        val request = request("")

        assertNull(request.detail)
        assertNull(request.defaultAnswer)
        assertNull(request.defaultLabel)
        assertNull(request(""","detail":null,"default":null""").defaultLabel)
    }

    @Test
    fun `a yes-or-no default reads as the button it picks`() {
        val request = request(""","detail":"Tests pass.","default":"true"""")

        assertEquals("Tests pass.", request.detail)
        assertEquals("Yes", request.defaultLabel)
        assertEquals("No", request(""","default":"false"""").defaultLabel)
    }

    @Test
    fun `an option or typed default reads as itself`() {
        assertEquals("Later", request(""","options":["Now","Later"],"default":"Later"""", kind = "choice").defaultLabel)
        assertEquals("fix: typo", request(""","default":"fix: typo"""", kind = "text").defaultLabel)
    }
}
