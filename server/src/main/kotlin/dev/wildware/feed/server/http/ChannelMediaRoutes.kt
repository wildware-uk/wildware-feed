package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.ChannelMedia
import dev.wildware.feed.server.domain.channelMedia
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route

/**
 * A channel's Media tab ([ChannelMedia]): `GET /api/projects/{reference}/media?kind=&cursor=&limit=`,
 * newest first, 60 a page unless asked for fewer (100 at most). Reading needs Read on the channel;
 * one this person can't read is not_found.
 */
fun Route.channelMediaRoutes(app: App) {
    endpoint(
        "/api/projects/{reference}/media",
        HttpMethod.Get to {
            val params = call.request.queryParameters
            val limit = params["limit"]?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw invalid("limit must be a positive whole number")
            } ?: ChannelMedia.DEFAULT_LIMIT
            val page = app.feed.channelMedia.page(
                call.parameters["reference"].orEmpty(),
                ChannelMedia.Kind.of(params["kind"]),
                params["cursor"]?.trim()?.takeIf { it.isNotEmpty() },
                limit,
            )
            call.respondOwner(200, page)
        },
    )
}
