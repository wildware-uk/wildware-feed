package dev.wildware.agents.app.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a tap on a grid tile does: a picture opens the viewer (looking through the pictures only),
 * a video plays, in the app on a phone and in the system's player on a desktop.
 */
@Composable
fun rememberTileOpener(attachments: List<Attachment>): (Int) -> Unit {
    val ui = LocalFeedUi.current
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    return remember(attachments, ui, handler, auth) {
        { i ->
            val tile = attachments[i]
            val video = tile.video
            when {
                video == null -> {
                    val pictures = attachments.filter { it.video == null }
                    ui?.viewer = Gallery(pictures, pictures.indexOf(tile))
                }
                playsVideoInApp -> ui?.playing = video
                handler != null -> scope.launch {
                    ui?.toast = "Opening the video…"
                    ui?.toast = handOver { handler.open(video, auth) }
                }
            }
        }
    }
}

/** Runs a hand-over to the system, turning a failure into a line to show. */
internal suspend fun handOver(block: suspend () -> String): String = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failed: Throwable) {
    "Couldn't open it: ${failed.message ?: "no connection"}"
}

/** The play button and length drawn over a video's poster. */
@Composable
internal fun VideoBadge(video: VideoSource, small: Boolean = false) {
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(if (small) 40.dp else 56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Play, null, tint = Color.White, modifier = Modifier.size(if (small) 18.dp else 24.dp))
        }
        video.duration?.let {
            Text(
                it,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(AgentTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White,
                style = AgentTheme.type.meta,
            )
        }
    }
}

/** A video tile's face: its poster when the server made one, else a dark frame. */
@Composable
internal fun VideoFace(tile: Attachment, modifier: Modifier, small: Boolean = false) {
    val video = tile.video ?: return
    Box(modifier.background(Color(0xFF14161A))) {
        if (tile.url != null) AttachmentImage(tile, Modifier.fillMaxSize())
        VideoBadge(video, small)
    }
}

/** What a screen reader hears for one tile. */
internal fun tileLabel(tile: Attachment, index: Int, total: Int): String {
    val video = tile.video ?: return "Picture ${index + 1} of $total: ${tile.altText}"
    return "Play video ${index + 1} of $total" + (video.duration?.let { ", $it" } ?: "")
}

/** The sounds on a card, one row each; starting one pauses any other. */
@Composable
fun Sounds(sounds: List<Sound>, modifier: Modifier = Modifier) {
    if (sounds.isEmpty()) return
    val auth = LocalMediaAuth.current
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sounds.forEachIndexed { i, sound ->
            val player = rememberSoundPlayer(sound, auth)
            SoundRow(sound, i, sounds.size, player)
        }
    }
}

/**
 * One sound: play and pause, a row of bars to see and tap where it has got to, and the time.
 * With no [player] (a desktop), the button hands the sound to the system's player instead.
 */
@Composable
fun SoundRow(sound: Sound, index: Int, total: Int, player: SoundPlayer?) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val ui = LocalFeedUi.current
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    val state = player?.state?.collectAsState()?.value ?: SoundState(durationMs = sound.durationMs ?: 0)
    val nowPlaying by NowPlaying.id.collectAsState()
    LaunchedEffect(nowPlaying) { if (nowPlaying != sound.id && state.playing) player?.pause() }
    DisposableEffect(player) { onDispose { player?.release() } }

    val length = state.durationMs.takeIf { it > 0 } ?: sound.durationMs ?: 0
    val lengthLabel = MediaLabels.duration(length)
    val name = "sound ${index + 1} of $total" + (lengthLabel?.let { ", $it" } ?: "")
    val progress = if (length > 0) (state.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f
    val toggle = {
        when {
            player == null -> {
                if (handler != null) scope.launch {
                    ui?.toast = "Opening the sound…"
                    ui?.toast = handOver { handler.open(sound, auth) }
                }
            }
            state.playing -> player.pause()
            else -> {
                NowPlaying.id.value = sound.id
                player.play()
            }
        }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .pressable(if (state.playing) "Pause $name" else "Play $name", CircleShape, onClick = toggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(if (state.playing) Icons.Pause else Icons.Play, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Bars(
            seed = sound.id,
            progress = progress,
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .semantics {
                    contentDescription = "Position in $name"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    if (player != null && length > 0) setProgress { p -> player.seek((p * length).toLong()); true }
                }
                .then(
                    if (player != null && length > 0) {
                        Modifier.pointerInput(player, length) {
                            detectTapGestures { at -> player.seek((at.x / size.width * length).toLong().coerceIn(0, length)) }
                        }
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        MetaText(
            when {
                state.failed -> "Can't play"
                player == null -> lengthLabel ?: ""
                else -> "${MediaLabels.duration(state.positionMs) ?: "0:00"} / ${lengthLabel ?: "–"}"
            },
            color = colors.textMuted,
        )
    }
}

/**
 * A row of bars, the look of a waveform: the server measures no real one, so the heights come from
 * the sound's id and stay the same each time. The played part is drawn in the accent.
 */
@Composable
private fun Bars(seed: String, progress: Float, modifier: Modifier) {
    val colors = AgentTheme.colors
    val heights = remember(seed) {
        var h = seed.fold(17) { acc, c -> acc * 31 + c.code }
        List(BarCount) {
            h = h * 1_103_515_245 + 12_345
            0.25f + (((h ushr 16) and 0x7fff) / 32767f) * 0.75f
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val gap = 2.dp.toPx()
        val bar = ((size.width - gap * (BarCount - 1)) / BarCount).coerceAtLeast(1f)
        heights.forEachIndexed { i, fraction ->
            val x = i * (bar + gap)
            val height = size.height * fraction
            val played = (x + bar / 2) / size.width <= progress
            drawRoundRect(
                if (played) colors.accent else colors.textFaint.copy(alpha = 0.5f),
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private const val BarCount = 48

/** The files on a card: a chip each, which downloads the file and says where it went. */
@Composable
fun Files(files: List<FileAttachment>, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { FileChip(it) }
    }
}

@Composable
fun FileChip(file: FileAttachment) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    var status by remember(file.id) { mutableStateOf<String?>(null) }
    var busy by remember(file.id) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .pressable("Download ${file.name}, ${file.size}", shape) {
                if (busy || handler == null) return@pressable
                busy = true
                status = "Downloading…"
                scope.launch {
                    status = handOver { handler.save(file, auth) }
                    busy = false
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.File, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText(
                status ?: file.size,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.textMuted,
            )
        }
        Icon(Icons.Download, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
    }
}

/** A video playing full screen in the app, over everything, with a close button. */
@Composable
fun VideoOverlay(ui: FeedUi, video: VideoSource, safeArea: SafeArea) {
    val auth = LocalMediaAuth.current
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "Video" }) {
        InAppVideo(video, auth, Modifier.fillMaxSize().padding(top = safeArea.top, bottom = safeArea.bottom))
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = safeArea.top + 8.dp, start = 8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .pressable("Close video", CircleShape) { ui.playing = null },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Close, null, tint = Color.White, modifier = Modifier.padding(10.dp).size(20.dp))
        }
    }
}

/** A short line at the bottom of the screen that goes away by itself. */
@Composable
fun Toast(ui: FeedUi, safeArea: SafeArea) {
    val text = ui.toast ?: return
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(4_000)
        if (ui.toast == text) ui.toast = null
    }
    Box(Modifier.fillMaxSize().padding(bottom = safeArea.bottom + 96.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            Modifier
                .padding(horizontal = 16.dp)
                .clip(AgentTheme.shapes.pill)
                .background(AgentTheme.colors.text)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = AgentTheme.colors.background,
            style = AgentTheme.type.label,
        )
    }
}
