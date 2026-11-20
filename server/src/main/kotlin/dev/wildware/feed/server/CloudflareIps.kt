package dev.wildware.feed.server

import java.net.InetAddress

/**
 * Cloudflare's address ranges (https://www.cloudflare.com/ips/, as of November 2026), to tell a
 * request that came through Cloudflare from one sent straight to the server (Config.behindCloudflare).
 * CLOUDFLARE_IPS replaces the list should Cloudflare change it.
 */
object CloudflareIps {
    val DEFAULT = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32",
    )

    /** A range as its address bytes and prefix length, or null when [cidr] isn't one. */
    fun parse(cidr: String): Pair<ByteArray, Int>? {
        val (ip, bits) = cidr.split('/').takeIf { it.size == 2 } ?: return null
        val address = literal(ip) ?: return null
        val prefix = bits.toIntOrNull()?.takeIf { it in 0..address.size * 8 } ?: return null
        return address to prefix
    }

    /** Whether [address] (an IP literal) is in any of [ranges]. Anything that isn't an IP literal is in none. */
    fun contains(ranges: List<String>, address: String): Boolean {
        val ip = literal(address.trim().removePrefix("[").removeSuffix("]")) ?: return false
        return ranges.any { cidr ->
            val (net, prefix) = parse(cidr) ?: return@any false
            if (net.size != ip.size) return@any false
            (0 until prefix).all { bit ->
                val mask = 0x80 ushr (bit % 8)
                (net[bit / 8].toInt() and mask) == (ip[bit / 8].toInt() and mask)
            }
        }
    }

    /** The bytes of an IPv4 or IPv6 literal, never a DNS lookup. */
    private fun literal(text: String): ByteArray? {
        val looksLikeIp = Regex("^[0-9.]+$").matches(text) || (':' in text && Regex("^[0-9a-fA-F:.]+$").matches(text))
        if (!looksLikeIp) return null
        return runCatching { InetAddress.getByName(text).address }.getOrNull()
    }
}
