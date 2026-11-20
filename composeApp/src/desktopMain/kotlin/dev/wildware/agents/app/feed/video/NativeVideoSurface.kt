package dev.wildware.agents.app.feed.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * What a [NativeVideoSurface] shows its controls, updated a few times a second rather than every
 * frame, so the controls don't recompose 60 times a second.
 */
internal class VideoTracker {
    var status by mutableStateOf(VideoStatus())
        internal set

    internal fun offer(next: VideoStatus) {
        val now = status
        val moved = abs(next.positionSec - now.positionSec) >= 0.25
        if (moved || next.copy(positionSec = now.positionSec) != now) status = next
    }
}

/**
 * Draws [video]: once per screen refresh while it plays (or loads) it asks the engine for the
 * picture due, so frames land in step with the screen; paused, it checks a few times a second, enough
 * to show where a seek landed. A new frame only redraws; nothing recomposes.
 */
@Composable
internal fun NativeVideoSurface(video: NativeVideo, frames: VideoFrames, tracker: VideoTracker, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(video, frames) {
        while (isActive) {
            val status = runCatching { video.status() }.getOrElse { VideoStatus(loading = false, error = "Couldn't play this video") }
            tracker.offer(status)
            if (status.error != null) {
                delay(250)
                continue
            }
            if (status.playing || status.loading) {
                withFrameNanos { video.pullFrame(frames, size.width, size.height) }
            } else {
                video.pullFrame(frames, size.width, size.height)
                delay(50)
            }
        }
    }
    Canvas(modifier.fillMaxSize().onSizeChanged { size = it }) {
        val image = frames.image ?: return@Canvas
        val scale = minOf(this.size.width / image.width, this.size.height / image.height)
        val width = (image.width * scale).toInt()
        val height = (image.height * scale).toInt()
        drawImage(
            image,
            dstOffset = IntOffset(((this.size.width - width) / 2).toInt(), ((this.size.height - height) / 2).toInt()),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.Low,
        )
    }
}
