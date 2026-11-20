package dev.wildware.feed.server

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloudflare's address ranges (https://www.cloudflare.com/ips/, as of November 2026), to tell a
 * request Cloudflare passed on from one sent straight to the server (Config.behindCloudflare).
 * CLOUDFLARE_IPS replaces the list should Cloudflare change it. A range alone proves little
 * (Cloudflare's own VPN leaves from these ranges too), so the server also wants the secret header
 * our Cloudflare rule adds ([Config.cloudflareOriginSecret]).
 */
object CloudflareIps {
    val DEFAULT = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    )

    /** The narrowest a configured range may be told to cover: wider than this trusts far too much. */
    private const val MIN_PREFIX_V4 = 8
    private const val MIN_PREFIX_V6 = 16

    private class Range(val net: ByteArray, val prefix: Int)

    /** A range as its address bytes and prefix length, or null when [cidr] isn't one, or is too wide. */
    fun parse(cidr: String): Pair<ByteArray, Int>? {
        val (ip, bits) = cidr.trim().split('/').takeIf { it.size == 2 } ?: return null
        val address = literal(ip) ?: return null
        val prefix = bits.toIntOrNull()?.takeIf { it in 0..address.size * 8 } ?: return null
        if (prefix < if (address.size == 4) MIN_PREFIX_V4 else MIN_PREFIX_V6) return null
        return address to prefix
    }

    /** Each list parsed once. */
    private val parsed = ConcurrentHashMap<List<String>, List<Range>>()

    /** Whether [address] (an IP literal) is in any of [ranges]. Anything that isn't an IP literal is in none. */
    fun contains(ranges: List<String>, address: String): Boolean {
        val ip = literal(address.trim().removePrefix("[").removeSuffix("]")) ?: return false
        val list = parsed.getOrPut(ranges) { ranges.mapNotNull { c -> parse(c)?.let { Range(it.first, it.second) } } }
        return list.any { r ->
            r.net.size == ip.size && (0 until r.prefix).all { bit ->
                val mask = 0x80 ushr (bit % 8)
                (r.net[bit / 8].toInt() and mask) == (ip[bit / 8].toInt() and mask)
            }
        }
    }

    /**
     * The bytes of a strictly written IPv4 (four decimal parts, 0 to 255) or IPv6 literal, never a
     * DNS lookup and never a shorthand like `1.2` or `2918576129`. An IPv4-mapped IPv6 address
     * (`::ffff:a.b.c.d`) is its IPv4 address. Null for anything else.
     */
    fun literal(text: String): ByteArray? {
        if (IPV4.matches(text)) {
            val parts = text.split('.').map { it.toInt() }
            if (parts.any { it > 255 } || text.split('.').any { it.length > 1 && it.startsWith("0") }) return null
            return parts.map { it.toByte() }.toByteArray()
        }
        if (':' !in text || !IPV6_CHARS.matches(text) || text.count { it == ':' } > 7 || "::" in text.replaceFirst("::", "")) return null
        val tail = text.substringAfterLast(':')
        if ('.' in tail && literal(tail) == null) return null
        return runCatching { InetAddress.getByName(text).address }.getOrNull()
    }

    /**
     * An address as a limiter's key: an IPv4 address as it is, an IPv6 one as its /64 (one home
     * line usually has a whole /64, so each of its addresses must not be a fresh budget). Null when
     * [text] isn't an IP literal.
     */
    fun limiterKey(text: String): String? {
        val bytes = literal(text.trim().removePrefix("[").removeSuffix("]")) ?: return null
        if (bytes.size == 4) return InetAddress.getByAddress(bytes).hostAddress
        val net = bytes.copyOf().also { for (i in 8 until 16) it[i] = 0 }
        return InetAddress.getByAddress(net).hostAddress + "/64"
    }

    /** Whether [bytes] are loopback, private or link-local: never a real client's address from Cloudflare. */
    fun isLocal(bytes: ByteArray): Boolean = InetAddress.getByAddress(bytes).let {
        it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress
    }

    private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")
}
