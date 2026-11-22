package dev.wildware.agents.app.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.gesture.DismissDrag
import dev.wildware.agents.app.design.gesture.swipeDownToDismiss
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pictures full screen, one at a time, as a picture viewer on a computer works.
 *
 * Each opens fitted: the whole picture, never cropped. The mouse wheel (or a trackpad pinch, or a
 * pinch on a phone) zooms around the pointer, from 5% to 800%; double-click goes between fitted and
 * 100% at that point. Zoomed in, a drag or the arrow keys move it, never so far it is lost. Past
 * fitted it loads the picture at full size, so 100% is sharp.
 *
 * Ctrl or ⌘ with +, - and 0 zoom in, out and back to fitted; Ctrl/⌘+C copies the picture itself.
 * Left and right go through a card's pictures; Escape, or a click beside the picture, closes it.
 * A right-click (or a long press) offers Copy image, Save, Copy link, Open and the zoom steps.
 */
@Composable
fun ImageViewer(gallery: Gallery, safeArea: SafeArea) {
    val ui = LocalFeedUi.current ?: return
    val images = gallery.images
    if (images.isEmpty()) return
    val phone = LocalIsPhone.current
    val pager = rememberPagerState(initialPage = gallery.index.coerceIn(0, images.lastIndex)) { images.size }
    val zooms = remember(images) { images.map { ViewerZoom() } }
    val page = pager.currentPage.coerceIn(0, images.lastIndex)
    val zoom = zooms[page]
    val current = images[page]
    val drag = remember { DismissDrag() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val actions = rememberViewerActions()
    val motion = AgentTheme.motion
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A picture left behind goes back to fitted, so coming back to it starts whole.
    LaunchedEffect(page) { zooms.forEachIndexed { i, z -> if (i != page) z.fit() } }
    // Where a swipe let go, so the picture fades away from there rather than jumping back first.
    var letGoAt by remember { mutableFloatStateOf(0f) }
    val close = {
        letGoAt = drag.offsetPx
        menuAt = null
        ui.viewer = null
    }
    fun go(to: Int) {
        if (to !in images.indices) return
        scope.launch { if (motion.reduced) pager.scrollToPage(to) else pager.animateScrollToPage(to) }
    }
    // The arrows move a zoomed picture until it reaches its edge, then go on to the next one.
    fun arrow(dx: Float, dy: Float, next: Int?) {
        if (zoom.canPan) {
            val before = zoom.offset
            zoom.panBy(Offset(dx, dy))
            if (zoom.offset != before || next == null) return
        }
        next?.let { go(page + it) }
    }
    val keyStep = with(LocalDensity.current) { 64.dp.toPx() }
    val keys: (KeyEvent) -> Boolean = keys@{ e ->
        if (e.type != KeyEventType.KeyDown) return@keys false
        val command = e.isCtrlPressed || e.isMetaPressed
        when {
            e.key == Key.Escape -> close()
            command && e.key == Key.C -> actions.copyImage(current)
            command && e.key in ZoomInKeys -> zoom.stepIn()
            command && e.key in ZoomOutKeys -> zoom.stepOut()
            command && e.key in FitKeys -> zoom.fit()
            command -> return@keys false
            e.key in ZoomInKeys -> zoom.stepIn()
            e.key in ZoomOutKeys -> zoom.stepOut()
            e.key in FitKeys -> zoom.fit()
            e.key == Key.One || e.key == Key.NumPad1 -> zoom.zoomTo(1f)
            e.key == Key.DirectionRight -> arrow(-keyStep, 0f, 1)
            e.key == Key.DirectionLeft -> arrow(keyStep, 0f, -1)
            e.key == Key.DirectionDown -> arrow(0f, -keyStep, null)
            e.key == Key.DirectionUp -> arrow(0f, keyStep, null)
            e.key == Key.PageDown -> go(page + 1)
            e.key == Key.PageUp -> go(page - 1)
            e.key == Key.MoveHome -> go(0)
            e.key == Key.MoveEnd -> go(images.lastIndex)
            else -> return@keys false
        }
        true
    }
    val dim = if (phone) 1f else 0.92f
    Box(
        Modifier
            .fillMaxSize()
            // Fades as it is pulled down. Read while drawing, so the drag never recomposes the viewer.
            .drawBehind { drawRect(Color.Black.copy(alpha = dim * (1f - maxOf(drag.offsetPx, letGoAt) / 900f).coerceIn(0.3f, 1f))) }
            .semantics {
                paneTitle = "Pictures"
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Next picture") { go(page + 1); true }.takeIf { page < images.lastIndex },
                    CustomAccessibilityAction("Previous picture") { go(page - 1); true }.takeIf { page > 0 },
                    CustomAccessibilityAction("Fit to window") { zoom.fit(); true },
                    CustomAccessibilityAction("Actual size") { zoom.zoomTo(1f); true },
                    CustomAccessibilityAction("Copy image") { actions.copyImage(current); true }.takeIf { actions.copiesImages && (current.fullUrl ?: current.url) != null },
                )
            }
            .focusRequester(focus)
            .onKeyEvent(keys)
            .focusable()
            .swipeDownToDismiss(drag, enabled = { phone && !zoom.isZoomedIn }, onDismiss = close),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                // On a computer the picture fits between the bar and the hint, so neither covers any of it.
                .then(if (phone) Modifier else Modifier.padding(top = safeArea.top + BarHeight, bottom = HintHeight))
                .offset { IntOffset(0, maxOf(drag.offsetPx, letGoAt).roundToInt()) },
            // A zoomed picture keeps its drags for panning; swiping on to the next waits until it is back to size.
            // A mouse never drags the pages: the arrows and keys go through them.
            userScrollEnabled = phone && !zoom.isZoomedIn,
            beyondViewportPageCount = 0,
        ) { at ->
            ViewerPage(images[at], zooms[at], phone, close = close, openMenu = { menuAt = it })
        }
        ViewerBar(
            images = images,
            page = page,
            zoom = zoom,
            phone = phone,
            actions = actions,
            close = close,
            modifier = Modifier.fillMaxWidth().padding(top = safeArea.top),
        )
        if (images.size > 1) {
            if (page > 0) {
                RoundButton(Icons.Back, "Previous picture", Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { go(page - 1) }
            }
            if (page < images.lastIndex) {
                RoundButton(Icons.ChevronRight, "Next picture", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { go(page + 1) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp).safePadding(safeArea, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (current.altText != current.title) {
                Text(current.altText, color = Color.White.copy(alpha = 0.85f), style = AgentTheme.type.body)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                when {
                    !phone -> "Scroll to zoom · drag to move · double-click for 100% · right-click for more"
                    images.size > 1 -> "Swipe for the next · pinch to zoom · swipe down to close"
                    else -> "Pinch to zoom · swipe down to close"
                },
                Modifier.clearAndSetSemantics { },
                color = Color.White.copy(alpha = 0.5f),
                style = AgentTheme.type.meta,
            )
        }
        menuAt?.let { at -> ViewerMenu(at, current, zoom, actions) { menuAt = null } }
    }
}

private val BarHeight = 48.dp
private val HintHeight = 40.dp

private val ZoomInKeys = setOf(Key.Equals, Key.Plus, Key.NumPadAdd)
private val ZoomOutKeys = setOf(Key.Minus, Key.NumPadSubtract)
private val FitKeys = setOf(Key.Zero, Key.NumPad0)

/** How much one notch of the wheel zooms. */
private const val WheelZoom = 0.18f

/** The longest side of the copy the feed shows ("thumb-1600"); drawn bigger than this, the viewer loads the original. */
private const val ThumbSide = 1600f

/** The most picture the viewer decodes at once (about 96 MB): bigger originals are drawn a little smaller. */
private const val MaxFullPixels = 24_000_000f

/** Where the picture is drawn in its page, for tests: the bounds a node reports are cut to the window. */
internal val PictureRect = SemanticsPropertyKey<androidx.compose.ui.geometry.Rect>("PictureRect")
internal var SemanticsPropertyReceiver.pictureRect by PictureRect

/** The picture's own size: as the dashboard measured it, else as it loaded, else from its shape. */
internal fun naturalSize(image: Attachment, loaded: Size?): Size {
    val w = image.width
    val h = image.height
    if (w != null && h != null && w > 0 && h > 0) return Size(w.toFloat(), h.toFloat())
    if (loaded != null && loaded.width > 0f && loaded.height > 0f && loaded.width.isFinite() && loaded.height.isFinite()) return loaded
    return Size(ThumbSide, ThumbSide / image.aspect)
}

/** One picture, sized and moved by its [zoom], taking the wheel, drags, pinches, clicks and right-clicks. */
@Composable
private fun ViewerPage(image: Attachment, zoom: ViewerZoom, phone: Boolean, close: () -> Unit, openMenu: (Offset) -> Unit) {
    var loaded by remember(image) { mutableStateOf<Size?>(null) }
    val natural = naturalSize(image, loaded)
    // Once wanted the original stays, so zooming back out never swaps it for the blurrier copy.
    var wantFull by remember(image) { mutableStateOf(false) }
    val full = image.fullUrl?.takeIf { it != image.url }
    if (full != null && !wantFull && zoom.scale * max(natural.width, natural.height) > ThumbSide * 1.02f) wantFull = true
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .layout { measurable, constraints ->
                zoom.setSizes(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), natural)
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .pointerHoverIcon(if (zoom.canPan) panCursor else PointerIcon.Default)
            .semantics { pictureRect = zoom.imageRect }
            .viewerGestures(zoom, phone, close, openMenu),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                // Laid out at its fitted size and scaled while drawing, so zooming never lays out again.
                .layout { measurable, _ ->
                    val fit = zoom.fitScale
                    val w = (natural.width * fit).roundToInt().coerceAtLeast(1)
                    val h = (natural.height * fit).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val s = zoom.scale / zoom.fitScale
                    scaleX = s
                    scaleY = s
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                .semantics { contentDescription = image.altText },
        ) {
            AttachmentImage(image, Modifier.fillMaxSize(), ContentScale.FillBounds, onLoaded = { loaded = it })
            if (full != null && wantFull) FullSizeImage(full, natural, Modifier.fillMaxSize())
        }
    }
}

/** The original, over the smaller copy once it has loaded: decoded by the image loader off the main thread. */
@Composable
private fun FullSizeImage(url: String, natural: Size, modifier: Modifier) {
    val auth = LocalMediaAuth.current
    val context = LocalPlatformContext.current
    val request = remember(url, auth, natural) {
        val pixels = natural.width * natural.height
        val shrink = if (pixels > MaxFullPixels) sqrt(MaxFullPixels / pixels) else 1f
        ImageRequest.Builder(context)
            .data(url)
            .size((natural.width * shrink).roundToInt().coerceAtLeast(1), (natural.height * shrink).roundToInt().coerceAtLeast(1))
            .crossfade(false)
            .apply {
                val key = auth?.authorization
                if (key != null && url.startsWith(auth.serverUrl)) httpHeaders(NetworkHeaders.Builder().set("Authorization", key).build())
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * The viewer's pointer: the wheel zooms around the pointer, a right-click opens the menu, a drag
 * pans a zoomed picture, two fingers pinch, a double-click toggles fitted and 100%, a long press
 * opens the menu, and a click beside the picture (not the end of a drag) closes the viewer.
 * A fitted picture's one-finger drags are left alone for the pages and swipe-down on a phone.
 */
private fun Modifier.viewerGestures(zoom: ViewerZoom, phone: Boolean, close: () -> Unit, openMenu: (Offset) -> Unit): Modifier = this
    .pointerInput(zoom) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when {
                    event.type == PointerEventType.Scroll -> {
                        val dy = change.scrollDelta.y.coerceIn(-3f, 3f)
                        if (dy != 0f) {
                            zoom.zoomBy(exp(-dy * WheelZoom), change.position)
                            event.changes.forEach { it.consume() }
                        }
                    }
                    event.type == PointerEventType.Press && event.buttons.isSecondaryPressed -> {
                        event.changes.forEach { it.consume() }
                        openMenu(change.position)
                    }
                }
            }
        }
    }
    .pointerInput(zoom, phone) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
            val tap = follow(first, zoom, openMenu) ?: return@awaitEachGesture
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            if (second == null) {
                if (!zoom.imageRect.contains(tap)) close()
                return@awaitEachGesture
            }
            val again = follow(second, zoom, openMenu) ?: return@awaitEachGesture
            zoom.toggle(again)
        }
    }

/**
 * Follows one press to its end, panning and pinching as it goes. Where it let go when it was a
 * plain tap; null when it moved, pinched, was held for the menu or was taken by something else.
 */
private suspend fun AwaitPointerEventScope.follow(down: PointerInputChange, zoom: ViewerZoom, openMenu: (Offset) -> Unit): Offset? {
    var moved = false
    var held = false
    var travelled = Offset.Zero
    val touch = down.type == PointerType.Touch
    var last = down.uptimeMillis
    while (true) {
        val waitFor = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - last
        val event = if (touch && !moved && !held) {
            withTimeoutOrNull(waitFor.coerceAtLeast(1)) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        }
        if (event == null) {
            held = true
            openMenu(down.position)
            continue
        }
        event.changes.firstOrNull()?.let { last = it.uptimeMillis }
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            val up = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            return if (moved || held || up.isConsumed) null else up.position
        }
        if (pressed.size >= 2) {
            moved = true
            val factor = event.calculateZoom()
            val pan = event.calculatePan()
            if (factor != 1f) zoom.zoomBy(factor, event.calculateCentroid())
            if (pan != Offset.Zero) zoom.panBy(pan)
            event.changes.forEach { it.consume() }
            continue
        }
        val change = pressed.first()
        val delta = change.positionChange()
        travelled += delta
        if (!moved && travelled.getDistance() > viewConfiguration.touchSlop) moved = true
        if (moved && zoom.canPan && !change.isConsumed) {
            zoom.panBy(delta)
            change.consume()
        }
    }
}

/** What the viewer does with a picture: copy it or its link, save it, open it elsewhere, show it in its folder. */
@Immutable
internal class ViewerActions(
    private val handler: MediaHandler?,
    private val auth: MediaAuth?,
    private val ui: FeedUi?,
    private val copyText: (String) -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) {
    val copiesImages: Boolean get() = handler?.copiesImages == true
    val openLabel: String? get() = handler?.openPictureLabel
    val showsInFolder: Boolean get() = handler?.showsSavedFiles == true

    private fun Attachment.best(): String? = fullUrl ?: url

    /** The link to the picture itself, not the copy the feed shows or the download. */
    fun link(image: Attachment): String? = image.fullUrl ?: image.original?.url?.removeSuffix("?download=1") ?: image.url

    fun copyImage(image: Attachment) {
        val h = handler ?: return
        if (!h.copiesImages) return
        val url = image.best() ?: return
        ui?.toast = "Copying the picture…"
        launch { ui?.toast = said("Couldn't copy it") { h.copyImage(url, auth) } }
    }

    fun copyLink(image: Attachment) {
        val link = link(image) ?: return
        copyText(link)
        ui?.toast = "Link copied"
    }

    fun save(image: Attachment) {
        image.saveable?.let { MediaSaves.save(listOf(it), handler, auth, ui) }
    }

    fun open(image: Attachment) {
        val h = handler ?: return
        val url = image.best() ?: return
        launch { ui?.toast = said("Couldn't open it") { h.openPicture(url, image.original?.mime ?: "image/png", auth) } }
    }

    /** Saves it to Downloads, unless it is there already, and shows it in its folder. */
    fun showInFolder(image: Attachment) {
        val file = image.saveable ?: return
        val h = handler ?: return
        if (!h.showSaved(file)) MediaSaves.save(listOf(file), h, auth, ui, reveal = true)
    }

    private suspend fun said(failed: String, block: suspend () -> String): String = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Throwable) {
        "$failed: ${e.message ?: "no connection"}"
    }
}

@Composable
private fun rememberViewerActions(): ViewerActions {
    val ui = LocalFeedUi.current
    val handler = LocalMediaHandler.current
    val auth = LocalMediaAuth.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return remember(ui, handler, auth, clipboard, scope) {
        ViewerActions(handler, auth, ui, copyText = { clipboard.setText(AnnotatedString(it)) }, launch = { block -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { block() } })
    }
}

/** Along the top: close, where it is in the card and what it is, the zoom, and what to do with it. */
@Composable
private fun ViewerBar(
    images: List<Attachment>,
    page: Int,
    zoom: ViewerZoom,
    phone: Boolean,
    actions: ViewerActions,
    close: () -> Unit,
    modifier: Modifier,
) {
    val current = images[page]
    val file = current.saveable
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .height(BarHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.Close, "Close pictures", Modifier.size(40.dp), tint = Color.White, onClick = close)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(current.title, color = Color.White, style = AgentTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (images.size > 1) {
                    Text(
                        "${page + 1} / ${images.size}",
                        Modifier.semantics {
                            contentDescription = "Picture ${page + 1} of ${images.size}"
                            liveRegion = LiveRegionMode.Polite
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = AgentTheme.type.meta,
                    )
                }
                val details = pictureDetails(current)
                if (details.isNotEmpty()) {
                    Text(
                        (if (images.size > 1) " · " else "") + details,
                        color = Color.White.copy(alpha = 0.6f),
                        style = AgentTheme.type.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val percent = zoom.percent
        IconAction(Icons.ZoomOut, "Zoom out", Modifier.size(40.dp), tint = Color.White) { zoom.stepOut() }
        if (!phone) {
            Text(
                "$percent%",
                Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Zoom $percent%"; liveRegion = LiveRegionMode.Polite },
                color = Color.White,
                style = AgentTheme.type.label,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        IconAction(Icons.ZoomIn, "Zoom in", Modifier.size(40.dp), tint = Color.White) { zoom.stepIn() }
        if (!phone) {
            BarText("Fit", "Fit to window") { zoom.fit() }
            BarText("1:1", "Actual size") { zoom.zoomTo(1f) }
            Spacer(Modifier.width(8.dp))
        }
        if (actions.copiesImages && (current.fullUrl ?: current.url) != null) {
            IconAction(Icons.Copy, "Copy image", Modifier.size(40.dp), tint = Color.White) { actions.copyImage(current) }
        }
        if (!phone) {
            if (actions.link(current) != null) {
                IconAction(Icons.Link, "Copy link", Modifier.size(40.dp), tint = Color.White) { actions.copyLink(current) }
            }
        }
        file?.let { IconAction(Icons.Download, "Save picture ${it.name}", Modifier.size(40.dp), tint = Color.White) { actions.save(current) } }
        if (!phone) {
            actions.openLabel?.takeIf { (current.fullUrl ?: current.url) != null }?.let { label ->
                IconAction(Icons.External, label, Modifier.size(40.dp), tint = Color.White) { actions.open(current) }
            }
            if (actions.showsInFolder && file != null) {
                IconAction(Icons.Folder, "Show in folder", Modifier.size(40.dp), tint = Color.White) { actions.showInFolder(current) }
            }
        }
    }
}

/** "image.png · 1.2 MB · 4000 × 3000": what is known of it. */
internal fun pictureDetails(image: Attachment): String = listOfNotNull(
    image.original?.name?.takeIf { it != image.title },
    image.original?.size,
    if (image.width != null && image.height != null) "${image.width} × ${image.height}" else null,
).joinToString(" · ")

@Composable
private fun BarText(text: String, label: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(AgentTheme.shapes.pill)
            .pressable(label, AgentTheme.shapes.pill, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = Color.White,
        style = AgentTheme.type.label,
    )
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pressable(label, CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** The right-click menu, where the click was. */
@Composable
private fun ViewerMenu(at: Offset, image: Attachment, zoom: ViewerZoom, actions: ViewerActions, dismiss: () -> Unit) {
    val colors = AgentTheme.colors
    val rows = buildList {
        if (actions.copiesImages && (image.fullUrl ?: image.url) != null) add(Triple("Copy image", Icons.Copy) { actions.copyImage(image) })
        image.saveable?.let { add(Triple("Save picture", Icons.Download) { actions.save(image) }) }
        if (actions.link(image) != null) add(Triple("Copy link", Icons.Link) { actions.copyLink(image) })
        actions.openLabel?.takeIf { (image.fullUrl ?: image.url) != null }?.let { add(Triple(it, Icons.External) { actions.open(image) }) }
        if (actions.showsInFolder && image.saveable != null) add(Triple("Show in folder", Icons.Folder) { actions.showInFolder(image) })
        add(Triple("Fit to window", Icons.ZoomOut) { zoom.fit() })
        add(Triple("Actual size (100%)", Icons.ZoomIn) { zoom.zoomTo(1f, at) })
    }
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(0.dp)) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = dismiss,
            offset = DpOffset.Zero,
            shape = AgentTheme.shapes.medium,
            containerColor = colors.surfaceRaised,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.semantics { paneTitle = "Picture actions" },
        ) {
            rows.forEach { (label, icon, run) ->
                DropdownMenuItem(
                    text = { Text(label, style = AgentTheme.type.label) },
                    leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        dismiss()
                        run()
                    },
                    colors = MenuDefaults.itemColors(textColor = colors.text, leadingIconColor = colors.textMuted),
                )
            }
        }
    }
}
