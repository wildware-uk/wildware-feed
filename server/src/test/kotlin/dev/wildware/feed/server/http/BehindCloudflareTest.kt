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

    private val secret = "o".repeat(40)

    private fun address(config: Config, vararg headers: Pair<String, String>): String {
        var seen = ""
        testApplication {
            application { routing { get("/where") { call.respondText(call.clientAddress(config)) } } }
            seen = client.get("/where") { headers.forEach { (k, v) -> header(k, v) } }.bodyAsText()
        }
        return seen
    }

    private val on by lazy { Config.load(base + mapOf("BEHIND_CLOUDFLARE" to "on", "CLOUDFLARE_ORIGIN_SECRET" to secret)) }

    /** A request as our Cloudflare passes it on: Cloudflare's edge connected, with our secret. */
    private fun viaCloudflare(client: String, edge: String = "172.70.1.2", vararg more: Pair<String, String>) =
        arrayOf("X-Forwarded-For" to "$client, $edge", "CF-Connecting-IP" to client, ORIGIN_AUTH to secret, *more)

    @Test
    fun `Cloudflare's own ranges are known, and nothing else is`() {
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "172.70.1.2"))
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "2606:4700:10::6816:1"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "8.8.8.8"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "not an ip"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "example.com"), "never a DNS lookup")
        assertTrue(CloudflareIps.contains(listOf("10.0.0.0/8"), "10.200.3.4"))
        // The edges of a range.
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "173.245.63.255"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "173.245.64.0"))
        // IPv4 in IPv6 clothing is IPv4; shorthand and leading zeros are no address at all.
        assertTrue(CloudflareIps.contains(CloudflareIps.DEFAULT, "::ffff:172.70.1.2"))
        assertFalse(CloudflareIps.contains(listOf("10.0.0.0/8"), "10.1"))
        assertFalse(CloudflareIps.contains(listOf("10.0.0.0/8"), "167772161"))
        assertFalse(CloudflareIps.contains(listOf("10.0.0.0/8"), "010.0.0.1"))
        assertFalse(CloudflareIps.contains(listOf("10.0.0.0/8"), "999.1.1.1"))
        assertFalse(CloudflareIps.contains(CloudflareIps.DEFAULT, "fe80::1%eth0"))
        // Too wide to be anyone's edge.
        assertEquals(null, CloudflareIps.parse("0.0.0.0/0"))
        assertEquals(null, CloudflareIps.parse("::/0"))
        assertEquals(null, CloudflareIps.parse("2000::/3"))
        // One home's IPv6 line is one key.
        assertEquals(CloudflareIps.limiterKey("2001:db8:1:2:aaaa::1"), CloudflareIps.limiterKey("2001:db8:1:2:bbbb::9"))
        assertEquals("2001:db8:1:2:0:0:0:0/64", CloudflareIps.limiterKey("2001:db8:1:2::77"))
    }

    @Test
    fun `behind Cloudflare, the client is Cloudflare's word only when our Cloudflare passed it on`() {
        // Through our Cloudflare: the router appended Cloudflare's edge, and our rule added the secret.
        assertEquals("203.0.113.9", address(on, *viaCloudflare("203.0.113.9")))
        assertEquals("2001:db8:1:2:0:0:0:0/64", address(on, *viaCloudflare("2001:db8:1:2::5", edge = "2606:4700:10::1")))
        // Straight to the router, claiming to be anyone: the peer it is.
        assertEquals("198.51.100.7", address(on, "X-Forwarded-For" to "1.2.3.4, 198.51.100.7", "CF-Connecting-IP" to "1.2.3.4", ORIGIN_AUTH to secret))
        // From a Cloudflare address (its VPN, say) but not through our rule: no secret, or a wrong one.
        assertEquals("172.70.1.2", address(on, "X-Forwarded-For" to "1.2.3.4, 172.70.1.2", "CF-Connecting-IP" to "1.2.3.4"))
        assertEquals("172.70.1.2", address(on, "X-Forwarded-For" to "1.2.3.4, 172.70.1.2", "CF-Connecting-IP" to "1.2.3.4", ORIGIN_AUTH to "x".repeat(40)))
        // A claimed address that is no address, a local one, or two of them: the peer.
        assertEquals("172.70.1.2", address(on, *viaCloudflare("not-an-ip")))
        assertEquals("172.70.1.2", address(on, *viaCloudflare("127.0.0.1")))
        assertEquals("172.70.1.2", address(on, *viaCloudflare("10.0.0.1")))
        assertEquals("172.70.1.2", address(on, *viaCloudflare("203.0.113.9", more = arrayOf("CF-Connecting-IP" to "203.0.113.10"))))
        // Two X-Forwarded-For headers are one list, and the router's entry is still the last.
        assertEquals("198.51.100.7", address(on, "X-Forwarded-For" to "1.2.3.4", "X-Forwarded-For" to "5.6.7.8, 198.51.100.7", "CF-Connecting-IP" to "1.2.3.4", ORIGIN_AUTH to secret))
        // Off: as before, the router's peer.
        val off = Config.load(base)
        assertEquals("172.70.1.2", address(off, *viaCloudflare("203.0.113.9")))
    }

    @Test
    fun `the settings are checked`() {
        assertFailsWith<Config.Invalid> { Config.load(base + ("BEHIND_CLOUDFLARE" to "yes")) }
        assertFailsWith<Config.Invalid> { Config.load(base - "ADDRESS_HEADER" + mapOf("BEHIND_CLOUDFLARE" to "on", "CLOUDFLARE_ORIGIN_SECRET" to secret)) }
        assertFailsWith<Config.Invalid>("the secret is required") { Config.load(base + ("BEHIND_CLOUDFLARE" to "on")) }
        assertFailsWith<Config.Invalid>("and long") { Config.load(base + mapOf("BEHIND_CLOUDFLARE" to "on", "CLOUDFLARE_ORIGIN_SECRET" to "short")) }
        assertFailsWith<Config.Invalid> { Config.load(base + ("CLOUDFLARE_IPS" to "10.0.0.0/8, nope")) }
        assertFailsWith<Config.Invalid>("everyone is too wide") { Config.load(base + ("CLOUDFLARE_IPS" to "0.0.0.0/0")) }
        assertTrue("o".repeat(40) !in on.toString(), "the secret is never printed")
        assertEquals(listOf("10.0.0.0/8"), Config.load(base + ("CLOUDFLARE_IPS" to "10.0.0.0/8")).cloudflareRanges)
        val teams = base + mapOf("DATABASE_URL" to "postgres://u:p@localhost/db", "TEAM_DOMAIN" to "feed.test", "PUBLIC_BASE_URL" to "https://agents.example.com")
        assertEquals("admin.feed.test", Config.load(teams + ("ADMIN_HOST" to "Admin.Feed.Test")).adminHost)
        assertFailsWith<Config.Invalid>("a name a team could take") { Config.load(teams + ("ADMIN_HOST" to "acme.feed.test")) }
        assertFailsWith<Config.Invalid>("needs many teams") { Config.load(base + ("ADMIN_HOST" to "admin.feed.test")) }
        assertFailsWith<Config.Invalid>("not the home team's address") { Config.load(teams + ("ADMIN_HOST" to "agents.example.com")) }
        assertEquals("admin.feed.test", Config.load(teams + ("ADMIN_HOST" to "admin.feed.test.")).adminHost)
    }
}
