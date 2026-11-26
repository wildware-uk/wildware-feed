package dev.wildware.agents.app.feed

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One place a video can play: a card's pictures, or the preview of a card on another card. The same
 * video is often on screen twice (on its card and in the thread open over it, or in a preview on a
 * question that links to it), so which copy plays is said by place, not by address.
 */
@Stable
class VideoSlot

/** The video playing in its card: its address, and the [slot] it was tapped in (null in a screenshot: any). */
@Immutable
class InlinePlay(val url: String, val slot: VideoSlot?)

/**
 * What a tap on a grid tile does: a picture opens the viewer (looking through the pictures only),
 * a video plays right there, in [slot] only. On a phone a video sharing its grid opens full screen:
 * its tile is too small to watch in, and too small for the iPhone player's own full-screen button.
 */
@Composable
internal fun rememberTileOpener(attachments: List<Attachment>, slot: VideoSlot): (Int) -> Unit {
    val ui = LocalFeedUi.current
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    val phone = LocalIsPhone.current
    return remember(attachments, slot, ui, handler, auth, phone) {
        { i ->
            val tile = attachments[i]
            val video = tile.video
            // The viewer looks through pictures that are there; placeholders have nothing to show.
            val pictures = attachments.filter { it.video == null && !it.isPlaceholder }
            when {
                tile.isPlaceholder -> if (pictures.isNotEmpty()) ui?.viewer = Gallery(pictures, 0)
                video == null -> ui?.viewer = Gallery(pictures, pictures.indexOf(tile))
                playsVideoInApp && phone && attachments.size > 1 -> ui?.playing = video
                playsVideoInApp -> ui?.inlineVideo = InlinePlay(video.url, slot)
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

/** A button on a short line, such as "Show in folder" after a save. It shows only with the line [forText]. */
@Immutable
class ToastAction(val forText: String, val label: String, val run: () -> Unit)

/**
 * Keeps the originals of pictures and videos. Saves run apart from any one screen, so closing the
 * viewer or scrolling on doesn't cut a download short; each says how it went in a short line.
 */
internal object MediaSaves {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** [reveal]: show the last one in its folder once saved, where the device can. */
    fun save(files: List<FileAttachment>, handler: MediaHandler?, auth: MediaAuth?, ui: FeedUi?, reveal: Boolean = false) {
        if (handler == null || files.isEmpty()) return
        ui?.toast = if (files.size == 1) "Saving ${files[0].name}…" else "Saving ${files.size} files…"
        scope.launch {
            var said = ""
            for (file in files) {
                said = try {
                    handler.saveMedia(file, auth)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    ui?.toast = "Couldn't save ${file.name}: ${failed.message ?: "no connection"}"
                    return@launch
                }
            }
            if (files.size > 1) said = "Saved ${files.size} files. $said"
            ui?.toast = said
            val last = files.last()
            if (reveal && handler.showsSavedFiles && handler.showSaved(last)) return@launch
            if (handler.showsSavedFiles) {
                ui?.toastAction = ToastAction(said, "Show in folder") { if (!handler.showSaved(last)) ui?.toast = "Couldn't open the folder" }
            }
        }
    }
}

/** Saves the originals given, from wherever the owner asked: a tile, the viewer or a menu. */
@Composable
internal fun rememberMediaSaver(): (List<FileAttachment>) -> Unit {
    val ui = LocalFeedUi.current
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    return remember(ui, handler, auth) { { files -> MediaSaves.save(files, handler, auth, ui) } }
}

/** "picture" or "video", for what Save says it keeps. */
internal fun FileAttachment.noun(): String = if (mime.startsWith("video/")) "video" else "picture"

/** What a menu's Save says for [files]: "Save video", "Save 3 pictures", "Save 2 pictures and videos". */
internal fun saveLabel(files: List<FileAttachment>): String {
    val nouns = files.map { it.noun() }.distinct()
    return when {
        files.size == 1 -> "Save ${nouns[0]}"
        nouns.size == 1 -> "Save ${files.size} ${nouns[0]}s"
        else -> "Save ${files.size} pictures and videos"
    }
}

/** A round Save button over a picture or video, keeping its original. */
@Composable
internal fun SaveMediaButton(file: FileAttachment, modifier: Modifier = Modifier, small: Boolean = false) {
    val save = rememberMediaSaver()
    Box(
        modifier
            .padding(if (small) 4.dp else 8.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pressable("Save ${file.noun()} ${file.name}", CircleShape) { save(listOf(file)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Download, null, tint = Color.White, modifier = Modifier.padding(if (small) 6.dp else 8.dp).size(16.dp))
    }
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
    if (tile.isPlaceholder) return "${tile.altText}, ${index + 1} of $total"
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
            val removed = sound.removedAfterDays
            val notReady = sound.notReady
            if (removed != null) {
                RemovedMediaRow(removed)
            } else if (notReady != null) {
                NotReadyMediaRow(notReady, sound.name)
            } else {
                val player = rememberSoundPlayer(sound, auth)
                SoundRow(sound, i, sounds.size, player)
            }
        }
    }
}

/**
 * One sound: play and pause, its name over a row of bars to see and tap where it has got to, and
 * the time. With no [player], the button hands the sound to the system's player instead.
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
    val name = "sound ${index + 1} of $total" + (sound.name?.let { ", $it" } ?: "") + (lengthLabel?.let { ", $it" } ?: "")
    // What the row shows over its bars: the name it was uploaded with, else its place in the list.
    val title = sound.name ?: if (total > 1) "Sound ${index + 1}" else "Sound"
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
        Column(Modifier.weight(1f)) {
            Text(
                title,
                Modifier.clearAndSetSemantics { },
                color = colors.text,
                style = AgentTheme.type.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Bars(
                seed = sound.id,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
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
        }
        Spacer(Modifier.width(10.dp))
        MetaText(
            when {
                state.failed -> state.problem ?: "Can't play"
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
        files.forEach { file ->
            val removed = file.removedAfterDays
            val notReady = file.notReady
            when {
                removed != null -> RemovedMediaRow(removed)
                notReady != null -> NotReadyMediaRow(notReady, file.name)
                else -> FileChip(file)
            }
        }
    }
}

/**
 * A file to download: its name, its size, and a download icon. Saved, it says where; on a desktop
 * it then offers "Show in folder", to find the file without hunting for it.
 */
@Composable
fun FileChip(file: FileAttachment) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    var status by remember(file.id) { mutableStateOf<String?>(null) }
    var busy by remember(file.id) { mutableStateOf(false) }
    var saved by remember(file.id) { mutableStateOf(false) }
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
                    var ok = false
                    status = handOver { handler.save(file, auth).also { ok = true } }
                    saved = ok
                    busy = false
                }
            }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
        if (saved && handler?.showsSavedFiles == true) {
            val label = "Show in folder"
            Text(
                label,
                Modifier
                    .clip(AgentTheme.shapes.pill)
                    .pressable("Show ${file.name} in its folder", AgentTheme.shapes.pill) {
                        if (!handler.showSaved(file)) status = "Couldn't open the folder"
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                color = colors.accent,
                style = AgentTheme.type.label,
            )
        } else {
            Icon(Icons.Download, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Whether [video] is the one playing, here in [slot]: read per tile, so only that tile redraws. */
@Composable
internal fun playingInline(video: VideoSource?, slot: VideoSlot?): Boolean {
    val ui = LocalFeedUi.current ?: return false
    if (video == null) return false
    val playing by remember(video.url, slot) {
        derivedStateOf { ui.inlineVideo?.let { it.url == video.url && (it.slot == null || it.slot === slot) } == true }
    }
    return playing
}

/**
 * A video playing in its card, in place of its poster, with the platform's controls and a way to fill
 * the window. Scrolled away, it stops; tapped again, it starts from the top.
 */
@Composable
internal fun InlineVideo(video: VideoSource, modifier: Modifier) {
    val ui = LocalFeedUi.current
    val auth = LocalMediaAuth.current
    // The tap that put it here. Gone from the screen, it stops that tap's play, never a later one's.
    val mine = remember { ui?.inlineVideo }
    DisposableEffect(video.url) { onDispose { if (ui != null && ui.inlineVideo === mine) ui.inlineVideo = null } }
    LaunchedEffect(Unit) {
        // A video starting pauses any sound, and a sound starting stops it: one thing plays aloud.
        NowPlaying.id.value = null
        NowPlaying.id.first { it != null }
        if (ui != null && ui.inlineVideo === mine) ui.inlineVideo = null
    }
    Box(modifier.background(Color.Black)) {
        InAppVideo(video, auth, Modifier.fillMaxSize())
        // Where the platform's player draws its own controls over the app (the iPhone's), it keeps the corner.
        if (!videoControlsHaveFullScreen) Row(Modifier.align(Alignment.TopEnd)) {
            video.original?.let { SaveMediaButton(it) }
            Box(
                Modifier
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pressable("Full screen", CircleShape) {
                        ui?.inlineVideo = null
                        ui?.playing = video
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Expand, null, tint = Color.White, modifier = Modifier.padding(8.dp).size(16.dp))
            }
        }
    }
}

/** A video playing full screen in the app, over everything, with a close button. */
@Composable
fun VideoOverlay(ui: FeedUi, video: VideoSource, safeArea: SafeArea) {
    val auth = LocalMediaAuth.current
    // Escape closes it on a computer, as Back does on a phone.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A video starting pauses any sound.
    LaunchedEffect(video) { NowPlaying.id.value = null }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Video" }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) { ui.playing = null; true } else false },
    ) {
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
        video.original?.let { SaveMediaButton(it, Modifier.align(Alignment.TopEnd).padding(top = safeArea.top)) }
    }
}

/** A short line at the bottom of the screen that goes away by itself. */
@Composable
fun Toast(ui: FeedUi, safeArea: SafeArea) {
    val text = ui.toast ?: return
    val action = ui.toastAction?.takeIf { it.forText == text }
    LaunchedEffect(text) {
        // A line with a button stays long enough to reach for it.
        kotlinx.coroutines.delay(if (action != null) 8_000 else 4_000)
        if (ui.toast == text) ui.toast = null
    }
    Box(Modifier.fillMaxSize().padding(bottom = safeArea.bottom + 96.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(AgentTheme.shapes.pill)
                .background(AgentTheme.colors.text)
                .padding(start = 16.dp, end = if (action != null) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier
                    .padding(vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = AgentTheme.colors.background,
                style = AgentTheme.type.label,
            )
            if (action != null) {
                Text(
                    action.label,
                    Modifier
                        .padding(start = 8.dp)
                        .clip(AgentTheme.shapes.pill)
                        .pressable(action.label, AgentTheme.shapes.pill) {
                            ui.toast = null
                            action.run()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    color = AgentTheme.colors.accent,
                    style = AgentTheme.type.label,
                )
            }
        }
    }
}
