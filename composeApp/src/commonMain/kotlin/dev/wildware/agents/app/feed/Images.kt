package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.wildware.agents.app.design.AgentTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import okio.Path.Companion.toPath

/** Where the dashboard is and the key its media wants. Images elsewhere get no key. */
@Immutable
data class MediaAuth(val serverUrl: String, val authorization: String?)

val LocalMediaAuth = staticCompositionLocalOf<MediaAuth?> { null }

/**
 * Builds the app's image loader: fetched with the app's own network engine, kept in memory and in
 * a disk cache when [cacheDir] is given, so pictures show at once next time and offline.
 */
fun agentsImageLoader(context: PlatformContext, engine: () -> HttpClientEngine, cacheDir: String?): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory(httpClient = { HttpClient(engine()) })) }
        .apply {
            if (cacheDir != null) {
                diskCache { DiskCache.Builder().directory(cacheDir.toPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            }
        }
        .crossfade(true)
        .build()

/**
 * An attached picture. A real one loads from [Attachment.url] with the device key; previews and
 * screenshots, which have no url, paint the stand-in scene instead. The alt text belongs to the
 * caller, which knows what the picture is for.
 */
@Composable
fun AttachmentImage(attachment: Attachment, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    // Removed by the owner's media retention: say so where it was, and never ask for it.
    attachment.removedAfterDays?.let { days ->
        RemovedMediaTile(days, modifier)
        return
    }
    // Still being made, or never will be: say which where it will be, and ask for nothing.
    attachment.notReady?.let { why ->
        NotReadyMediaTile(why, modifier)
        return
    }
    val url = attachment.url
    if (url == null) {
        SceneArt(attachment.scene, modifier)
        return
    }
    val auth = LocalMediaAuth.current
    val context = LocalPlatformContext.current
    val request = remember(url, auth) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                val key = auth?.authorization
                if (key != null && url.startsWith(auth.serverUrl)) {
                    httpHeaders(NetworkHeaders.Builder().set("Authorization", key).build())
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = ColorPainter(AgentTheme.colors.surfaceSunken),
        error = ColorPainter(AgentTheme.colors.surfaceSunken),
    )
}
