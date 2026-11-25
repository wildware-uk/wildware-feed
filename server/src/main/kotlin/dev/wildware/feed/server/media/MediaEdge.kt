package dev.wildware.feed.server.media

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed links to the media edge (MEDIA_EDGE_URL, `media-edge/` at the repo root). The server checks
 * access as always, then redirects to a link the edge Worker can check without asking anyone: the
 * object key, when it stops working, and the Content-Type and Content-Disposition to send, under an
 * HMAC-SHA256 of [secret]. The Worker's `src/sign.ts` is the other half; both are tested against
 * `media-edge/test/vectors.json`.
 */
class MediaEdge(baseUrl: String, private val secret: String) {
    private val base = baseUrl.trimEnd('/')

    /** A link for [key] that works until [exp] (unix seconds). */
    fun link(key: String, contentType: String, disposition: String, exp: Long): String {
        val sig = sign(secret, key, exp, contentType, disposition)
        val path = key.split('/').joinToString("/") { Serve.encodeURIComponent(it) }
        return "$base/$path?exp=$exp&ct=${Serve.encodeURIComponent(contentType)}&cd=${Serve.encodeURIComponent(disposition)}&sig=$sig"
    }

    override fun toString() = "MediaEdge($base)"

    companion object {
        /** Links are made to last until the next of these boundaries, so one file has one link for a while. */
        const val BUCKET_S = 6 * 3600L

        /** A link always has at least this long left when it is handed out. */
        const val MIN_LEFT_S = 3600L

        /** How much sooner than the link a browser stops reusing the redirect to it. */
        const val MARGIN_S = 600L

        /** When a link made at [nowMs] stops working: the first 6-hour boundary at least an hour away. */
        fun expiry(nowMs: Long): Long {
            val earliest = Math.floorDiv(nowMs, 1000L) + MIN_LEFT_S
            return Math.floorDiv(earliest + BUCKET_S - 1, BUCKET_S) * BUCKET_S
        }

        /** How long the redirect itself may be kept: until a little before its link stops working. */
        fun redirectMaxAge(nowMs: Long, exp: Long): Long = maxOf(0L, exp - Math.floorDiv(nowMs, 1000L) - MARGIN_S)

        /** The lines signed, joined by `\n`: `v1`, the key, exp, the type and the disposition. */
        fun payload(key: String, exp: Long, contentType: String, disposition: String) = listOf("v1", key, exp.toString(), contentType, disposition).joinToString("\n")

        fun sign(secret: String, key: String, exp: Long, contentType: String, disposition: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val digest = mac.doFinal(payload(key, exp, contentType, disposition).toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
