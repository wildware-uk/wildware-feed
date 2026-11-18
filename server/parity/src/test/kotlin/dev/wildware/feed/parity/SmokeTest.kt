package dev.wildware.feed.parity

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest : ParityTest() {
    @Test
    fun `the owner can sign in and an agent can list its tools`() = parity {
        assertTrue(owner.cookie.startsWith("ad_session="))
        val tools = alpha.rpcResult("tools/list")["result"]["tools"].arr
        assertTrue(tools.isNotEmpty())
        assertEquals(303, call(HttpMethod.Get, "/").status.value)
    }
}
