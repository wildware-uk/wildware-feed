package dev.wildware.agents

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(extra: String) = json.decodeFromString<PendingRequest>(
        """{"id":"r1","agentName":"Builder","question":"Ship it?","kind":"confirm","expiresAt":"2026-11-17T00:00:00Z"$extra}"""
    )

    @Test
    fun `a request without detail or a default still reads`() {
        val request = request("")

        assertNull(request.detail)
        assertNull(request.defaultAnswer)
        assertNull(request.defaultLabel)
    }

    @Test
    fun `detail and a yes-or-no default are read`() {
        val request = request(""","detail":"Tests pass.","default":true""")

        assertEquals("Tests pass.", request.detail)
        assertEquals("Yes", request.defaultLabel)
        assertEquals("No", request(""","default":false""").defaultLabel)
    }

    @Test
    fun `an option or typed default reads as itself`() {
        assertEquals("Later", request(""","default":"Later"""").defaultLabel)
    }

    @Test
    fun `a default that is a list or a form has no single label`() {
        assertNull(request(""","default":["a","b"]""").defaultLabel)
        assertNull(request(""","default":{"name":"x"}""").defaultLabel)
    }
}
