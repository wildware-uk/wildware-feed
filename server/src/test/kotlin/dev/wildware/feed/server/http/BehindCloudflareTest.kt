package dev.wildware.feed.server.http

import dev.wildware.feed.server.CloudflareIps
import dev.wildware.feed.server.Config
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Some requests come through Cloudflare, some straight to the router (docs/GO-LIVE.md). The
 * client's address is Cloudflare's CF-Connecting-IP only when the router saw Cloudflare connect;
 * a request sent straight to the server can't name its own address that way.
 */
class BehindCloudflareTest {
    private val base = mapOf(
        "ADMIN_PASSWORD_HASH" to "\$argon2id\$x", "SESSION_SECRET" to "s".repeat(32), "TOKEN_SECRET" to "t".repeat(32),
        "ADDRESS_HEADER" to "X-Forwarded-For",
    )

    private fun address(config: Config, vararg headers: Pair<String, String>): String {
        var seen = ""
        testApplication {
            application { routing { get("/where") { call.respondText(call.clientAddress(config)) } } }
            seen = client.get("/where") { headers.forEach { (k, v) -> header(k, v) } }.bodyAsText()
        }
        return seen
    }

    @Test
    fun `Cloudflare's own ranges are known, and nothing else is`() {
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "172.70.1.2"))
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "2606:4700:10::6816:1"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "8.8.8.8"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "not an ip"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "example.com"), "never a DNS lookup")
        assertTrue(CloudflareIps.contains(listOf("10.0.0.0/8"), "10.200.3.4"))
    }

    @Test
    fun `behind Cloudflare, the client is Cloudflare's word only when Cloudflare connected`() {
        val config = Config.load(base + ("BEHIND_CLOUDFLARE" to "on"))
        // Through Cloudflare: the router appended Cloudflare's edge.
        assertEquals("203.0.113.9", address(config, "X-Forwarded-For" to "203.0.113.9, 172.70.1.2", "CF-Connecting-IP" to "203.0.113.9"))
        // Straight to the router, claiming to be anyone: the peer it is.
        assertEquals("198.51.100.7", address(config, "X-Forwarded-For" to "1.2.3.4, 198.51.100.7", "CF-Connecting-IP" to "1.2.3.4"))
        // Off: as before, the router's peer.
        val off = Config.load(base)
        assertEquals("172.70.1.2", address(off, "X-Forwarded-For" to "203.0.113.9, 172.70.1.2", "CF-Connecting-IP" to "203.0.113.9"))
    }

    @Test
    fun `the settings are checked`() {
        assertFailsWith<Config.Invalid> { Config.load(base + ("BEHIND_CLOUDFLARE" to "yes")) }
        assertFailsWith<Config.Invalid> { Config.load(base - "ADDRESS_HEADER" + ("BEHIND_CLOUDFLARE" to "on")) }
        assertFailsWith<Config.Invalid> { Config.load(base + ("CLOUDFLARE_IPS" to "10.0.0.0/8, nope")) }
        assertEquals(listOf("10.0.0.0/8"), Config.load(base + ("CLOUDFLARE_IPS" to "10.0.0.0/8")).cloudflareRanges)
        val teams = base + mapOf("DATABASE_URL" to "postgres://u:p@localhost/db", "TEAM_DOMAIN" to "feed.test", "PUBLIC_BASE_URL" to "https://agents.example.com")
        assertEquals("admin.feed.test", Config.load(teams + ("ADMIN_HOST" to "Admin.Feed.Test")).adminHost)
        assertFailsWith<Config.Invalid>("a name a team could take") { Config.load(teams + ("ADMIN_HOST" to "acme.feed.test")) }
        assertFailsWith<Config.Invalid>("needs many teams") { Config.load(base + ("ADMIN_HOST" to "admin.feed.test")) }
    }
}
