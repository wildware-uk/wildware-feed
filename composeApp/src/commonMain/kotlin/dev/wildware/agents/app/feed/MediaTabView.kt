package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.removedMediaLabel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * The Media tab: every picture, video and file on a channel's cards, newest first. Pictures and
 * videos sit in a grid, files (and sounds) in rows across it, each where its date puts it. A picture
 * opens the viewer, a video plays, a file downloads, as they do on a card; each also goes to the
 * message it came with. The next page is read as the list nears its end.
 */

/** The Media tab in the main pane, for the sidebar's channel. */
@Composable
fun MediaPane(ui: FeedUi, modifier: Modifier = Modifier, phone: Boolean = false, top: @Composable () -> Unit = {}) {
    val channel = ui.state.current.slug
    // The dashboard reads the media while it is on screen, and stops when it goes.
    LaunchedEffect(channel) { ui.emit(FeedEvent.Media(MediaAction.Show(channel))) }
    DisposableEffect(Unit) { onDispose { ui.emit(FeedEvent.Media(MediaAction.Show(null))) } }
    Column(modifier.semantics { paneTitle = "Media" }) {
        top()
        MediaHeader(ui, compact = phone)
        if (channel == null) {
            MediaEmpty("Media belongs to a channel. Pick one in the sidebar to see its pictures and files.")
            return@Column
        }
        val media = ui.state.media
        MediaFilters(media.filter, phone) { ui.emit(FeedEvent.Media(MediaAction.Filter(it))) }
        when {
            media.items.isEmpty() && media.problem != null -> MediaProblem(ui, media.problem, Modifier.fillMaxSize())
            media.items.isEmpty() && media.loaded && !media.loading -> MediaEmpty(emptyWords(media.filter))
            media.items.isEmpty() -> MediaEmpty("Loading…")
            else -> MediaGridList(ui, media, phone, Modifier.fillMaxSize())
        }
    }
}

/** What an empty tab says, for the chip chosen. */
internal fun emptyWords(filter: MediaFilter): String = when (filter) {
    MediaFilter.All -> "No media in this channel yet."
    MediaFilter.Images -> "No images in this channel yet."
    MediaFilter.Videos -> "No videos in this channel yet."
    MediaFilter.Files -> "No files in this channel yet."
}

@Composable
private fun MediaHeader(ui: FeedUi, compact: Boolean) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 8.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText("MEDIA  ·  CHANNEL", style = type.overline)
            Text(
                ui.state.current.name,
                Modifier.semantics { heading() },
                color = colors.text,
                style = if (compact) type.title else type.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ViewToggle(ui, ui.state.current.slug != null, Modifier.padding(start = 12.dp))
    }
}

/** All, Images, Videos, Files: one is chosen. */
@Composable
private fun MediaFilters(chosen: MediaFilter, compact: Boolean, onPick: (MediaFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 16.dp else 20.dp)
            .padding(bottom = 12.dp)
            .semantics { paneTitle = "Show" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (filter in MediaFilter.entries) {
            Chip(filter.label, selected = filter == chosen) { if (filter != chosen) onPick(filter) }
        }
    }
}

@Composable
private fun MediaEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = AgentTheme.colors.textMuted, style = AgentTheme.type.body)
    }
}

@Composable
private fun MediaProblem(ui: FeedUi, problem: String, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(problem, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = AgentTheme.colors.textMuted, style = AgentTheme.type.body)
        Spacer(Modifier.size(12.dp))
        AppButton("Try again", ButtonTone.Secondary) { ui.emit(FeedEvent.Media(MediaAction.Retry)) }
    }
}

/** The pages read so far: tiles in a grid, files as rows the grid's width, and the next page asked for near the end. */
@Composable
private fun MediaGridList(ui: FeedUi, media: MediaTabState, phone: Boolean, modifier: Modifier) {
    val grid = rememberLazyGridState()
    val items = media.items
    // The viewer looks through the pictures shown here, in order; videos play instead.
    val pictures = remember(items) { items.mapNotNull { it.tile?.takeIf { t -> t.video == null && !t.isPlaceholder } } }
    val latest by rememberUpdatedState(media)
    LaunchedEffect(grid) {
        snapshotFlow {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - NEAR_END
        }.distinctUntilChanged().collect { near ->
            if (near && latest.hasMore && !latest.loadingMore && latest.problem == null) ui.emit(FeedEvent.Media(MediaAction.More))
        }
    }
    // A page that came in while the end was already in view asks for the next one too.
    LaunchedEffect(items.size, media.hasMore) {
        val info = grid.layoutInfo
        val near = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - NEAR_END
        if (near && media.hasMore && !media.loadingMore && media.problem == null) ui.emit(FeedEvent.Media(MediaAction.More))
    }
    val gutter = if (phone) 16.dp else 20.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (phone) 104.dp else 160.dp),
        modifier = modifier,
        state = grid,
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (item in items) {
            val tile = item.tile
            if (tile != null) {
                item(key = item.id, contentType = "tile") { MediaTile(ui, item, tile, pictures) }
            } else {
                item(key = item.id, contentType = "file", span = { GridItemSpan(maxLineSpan) }) { MediaFileRow(ui, item) }
            }
        }
        if (media.loadingMore || (media.problem != null && media.hasMore)) {
            item(key = "footer", contentType = "footer", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    if (media.problem != null) {
                        AppButton("Couldn't load more. Try again", ButtonTone.Quiet) { ui.emit(FeedEvent.Media(MediaAction.More)) }
                    } else {
                        MetaText("Loading more…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }
}

/** How close to the end the next page is read: about two rows of tiles on a wide window. */
private const val NEAR_END = 12

/** What a screen reader hears for a tile, and what its press says. */
internal fun mediaTileLabel(item: MediaTabItem): String {
    val tile = item.tile ?: return item.file?.name ?: "File"
    val what = when {
        tile.isPlaceholder -> tile.altText
        tile.video != null -> "Play video" + (tile.video.duration?.let { ", $it" } ?: "")
        else -> "Picture: ${tile.altText}"
    }
    return "$what, sent by ${item.sentBy} ${item.ago}"
}

/** A picture or video, square in the grid. A press opens it; the corner button goes to its message. */
@Composable
private fun MediaTile(ui: FeedUi, item: MediaTabItem, tile: Attachment, pictures: List<Attachment>) {
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    val shape = AgentTheme.shapes.medium
    val thumb = item.thumb ?: tile
    val video = tile.video?.takeUnless { tile.isPlaceholder }
    val press = if (tile.isPlaceholder) Modifier else Modifier.pressable(mediaTileLabel(item), shape, role = if (video != null) Role.Button else Role.Image) {
        when {
            video == null -> ui.viewer = Gallery(pictures, pictures.indexOf(tile).coerceAtLeast(0))
            playsVideoInApp -> ui.playing = video
            handler != null -> scope.launch {
                ui.toast = "Opening the video…"
                ui.toast = handOver { handler.open(video, auth) }
            }
        }
    }
    Box(Modifier.aspectRatio(1f).clip(shape).background(AgentTheme.colors.surfaceSunken).then(press)) {
        if (video != null) VideoFace(thumb, Modifier.fillMaxSize(), small = true) else AttachmentImage(thumb, Modifier.fillMaxSize())
        GoToMessage(ui, item, Modifier.align(Alignment.BottomStart), overPicture = true)
    }
}

/** A file or sound: its name, size, who sent it and when. A press downloads it. */
@Composable
private fun MediaFileRow(ui: FeedUi, item: MediaTabItem) {
    val file = item.file ?: return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val scope = rememberCoroutineScope()
    var status by remember(file.id) { mutableStateOf<String?>(null) }
    var busy by remember(file.id) { mutableStateOf(false) }
    val removed = file.removedAfterDays
    val notReady = file.notReady
    val ready = removed == null && notReady == null && file.url.isNotEmpty()
    val details = "${file.size}  ·  ${item.sentBy}  ·  ${item.ago}"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .then(
                if (!ready) Modifier else Modifier.pressable("Download ${file.name}, ${file.size}, sent by ${item.sentBy} ${item.ago}", shape) {
                    if (busy || handler == null) return@pressable
                    busy = true
                    status = "Downloading…"
                    scope.launch {
                        status = handOver { handler.save(file, auth) }
                        busy = false
                    }
                },
            )
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.File, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = colors.text, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText(
                when {
                    removed != null -> removedMediaLabel(removed)
                    notReady != null -> notReady.words
                    else -> status ?: details
                },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.textMuted,
            )
        }
        if (ready) Icon(Icons.Download, null, tint = colors.textMuted, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
        GoToMessage(ui, item, Modifier, overPicture = false)
    }
}

/** Goes to the card a file came with, open with its thread. */
@Composable
private fun GoToMessage(ui: FeedUi, item: MediaTabItem, modifier: Modifier, overPicture: Boolean) {
    val label = when (item.cardKind) {
        "update" -> "Go to the update"
        "request" -> "Go to the question"
        else -> "Go to the message"
    }
    if (overPicture) {
        Box(
            modifier
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .pressable(label, CircleShape) { ui.openMediaCard(item.cardId) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Reply, null, tint = Color.White, modifier = Modifier.padding(6.dp).size(14.dp))
        }
    } else {
        IconAction(Icons.Reply, label, modifier, iconSize = 16.dp) { ui.openMediaCard(item.cardId) }
    }
}
