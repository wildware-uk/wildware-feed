package dev.wildware.agents.app.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import dev.wildware.agents.VoiceNote
import dev.wildware.agents.VoiceWord
import dev.wildware.agents.VoiceWords
import dev.wildware.agents.app.design.pressable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.DraftPrefix
import dev.wildware.agents.VoiceReviewSetting
import dev.wildware.agents.app.design.Icons
import kotlinx.coroutines.delay

/**
 * Records a voice note and writes down what was said, for the composer's hold-to-talk mic. The
 * iPhone hands one in from Swift; where there is none, the composer shows no mic. Every callback
 * comes back on the main thread.
 */
interface VoiceInput {
    /**
     * Starts recording. [onStarted] gets null once it is recording, or what stopped it (no microphone,
     * say). [onHeard] gets everything heard so far, again each time it grows, while the owner talks;
     * a phone that can't follow along never calls it.
     */
    fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit)

    /**
     * Stops and writes it down. [onDone] gets the words and those it wasn't sure of in them, or
     * null, no words and what to tell the owner.
     */
    fun finish(onDone: (text: String?, words: List<VoiceWord>, problem: String?) -> Unit)

    /** Stops and throws the recording away. */
    fun cancel()
}

val LocalVoiceInput = staticCompositionLocalOf<VoiceInput?> { null }

/** How far left the finger slides before letting go throws the note away. */
private val CancelDistance = 100.dp

/**
 * One composer's recording: what the mic button and the strip beside it draw. [onWords] takes the
 * finished words and says whether it sent them (true) or put them in the box to check (false).
 */
@Stable
class VoiceRecorder(private val input: VoiceInput, private val onWords: (String, List<VoiceWord>) -> Boolean) {
    enum class Phase { Idle, Starting, Recording, Writing }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** The finger has slid far enough left that letting go cancels. */
    var willCancel by mutableStateOf(false)

    /** A word for the owner under the box: "Check it, then send", or what went wrong; nothing once sent. */
    var note by mutableStateOf<String?>(null)

    /** The words heard so far, while recording and while they are written down; then the finished words replace them. */
    var heard by mutableStateOf("")
        private set

    /** This recording is still going: words heard after its end are left out. */
    private var listening = false

    val active: Boolean get() = phase != Phase.Idle

    /** Let go before recording had started: finish (or cancel) as soon as it has. */
    private var releasedEarly: Boolean? = null

    fun press() {
        if (phase != Phase.Idle) return
        phase = Phase.Starting
        willCancel = false
        note = null
        releasedEarly = null
        heard = ""
        listening = true
        input.start(onHeard = { words -> if (listening) heard = words }) { problem ->
            if (problem != null) {
                listening = false
                phase = Phase.Idle
                note = problem
                return@start
            }
            phase = Phase.Recording
            releasedEarly?.let { cancel -> if (cancel) cancel() else finish() }
        }
    }

    fun release() {
        when (phase) {
            Phase.Starting -> releasedEarly = willCancel
            Phase.Recording -> if (willCancel) cancel() else finish()
            else -> Unit
        }
    }

    private fun finish() {
        phase = Phase.Writing
        note = "Writing it down…"
        input.finish { text, words, problem ->
            listening = false
            heard = ""
            phase = Phase.Idle
            val sent = text != null && onWords(text, words)
            note = problem ?: if (sent) null else "Check it, then send"
        }
    }

    private fun cancel() {
        listening = false
        heard = ""
        input.cancel()
        phase = Phase.Idle
        note = null
        willCancel = false
    }
}

@Composable
fun rememberVoiceRecorder(input: VoiceInput?, onWords: (String, List<VoiceWord>) -> Boolean): VoiceRecorder? {
    val words by rememberUpdatedState(onWords)
    return remember(input) { input?.let { VoiceRecorder(it) { text, unsure -> words(text, unsure) } } }
}

/**
 * Hold to talk, slide left to cancel. A screen reader taps once to start and again to stop.
 * [sendsOnRelease]: letting go sends the note, rather than putting the words in the box.
 */
@Composable
fun MicButton(recorder: VoiceRecorder, sendsOnRelease: Boolean = false) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val cancelPx = with(LocalDensity.current) { CancelDistance.toPx() }
    val live = recorder.phase == VoiceRecorder.Phase.Recording || recorder.phase == VoiceRecorder.Phase.Starting
    Box(
        Modifier
            .size(44.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Hold to talk"
                stateDescription = when {
                    live -> "Recording"
                    sendsOnRelease -> "Your words are sent when you let go, with a few seconds to undo"
                    else -> "Your words go into the message to check before sending"
                }
                onClick(if (live) (if (sendsOnRelease) "Stop and send" else "Stop and write it down") else "Start recording") {
                    if (live) recorder.release() else recorder.press()
                    true
                }
            }
            .pointerInput(recorder) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    recorder.press()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        recorder.willCancel = change.position.x - down.position.x < -cancelPx
                        change.consume()
                        if (!change.pressed) break
                    }
                    recorder.release()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .scale(if (live) 1.2f else 1f)
                .clip(shape)
                .background(if (live) (if (recorder.willCancel) colors.textFaint else colors.error) else colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Mic, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

/** Takes the text box's place while recording: a beating dot, the time, and how to cancel. */
@Composable
fun RecordingStrip(recorder: VoiceRecorder, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recorder.phase) {
        if (recorder.phase != VoiceRecorder.Phase.Recording) return@LaunchedEffect
        seconds = 0
        while (true) {
            delay(1000)
            seconds++
        }
    }
    val beat = rememberInfiniteTransition()
    val dot by beat.animateFloat(
        initialValue = 1f,
        targetValue = if (AgentTheme.motion.reduced) 1f else 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    )
    Row(modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (recorder.phase == VoiceRecorder.Phase.Writing) {
            MetaText("Writing it down…", color = colors.textMuted)
            return@Row
        }
        Box(Modifier.size(8.dp).alpha(dot).clip(CircleShape).background(colors.error))
        Spacer(Modifier.width(8.dp))
        MetaText("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", color = colors.text)
        Spacer(Modifier.weight(1f))
        MetaText(
            if (recorder.willCancel) "Let go to cancel" else "‹ Slide to cancel",
            color = if (recorder.willCancel) colors.error else colors.textMuted,
        )
    }
}

/**
 * What has been heard so far, above the recording strip, as the owner speaks. The newest words
 * are the ones that matter, so a long note shows its end.
 */
@Composable
fun LiveWords(words: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shown = if (words.length > LiveWordsShown) "…" + words.takeLast(LiveWordsShown).trimStart() else words
    Text(
        shown,
        modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
        color = colors.text,
        style = AgentTheme.type.body,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

/** About four lines on a phone. */
private const val LiveWordsShown = 160

/** "Review voice notes before sending", where this device records them; null where it doesn't. */
val LocalVoiceReview = staticCompositionLocalOf<VoiceReviewSetting?> { null }

/** The small mic on a line that was said aloud and transcribed. Its words are read out by the line. */
@Composable
internal fun VoiceMark(modifier: Modifier = Modifier) {
    Icon(Icons.Mic, null, tint = AgentTheme.colors.textFaint, modifier = modifier.size(12.dp))
}

/**
 * Whether a line is inside its undo window: [until] is set and not yet reached. Turns false at the
 * deadline by itself, so the line can switch to its sending look (or on to sent) then.
 */
@Composable
internal fun rememberUndoOpen(until: Long?): Boolean {
    val now = LocalNow.current
    var open by remember(until) { mutableStateOf(until != null && now() < until) }
    LaunchedEffect(until, open) {
        if (until == null || !open) return@LaunchedEffect
        delay(until - now())
        open = false
    }
    return open
}

/**
 * A voice note waiting out its few seconds before it goes: a small pill whose fill drains to the
 * deadline, "Sending in 3s", and Undo. It is a choice the owner can still take back, not a
 * send stuck on the network, so the line it sits under is drawn at full strength. Undo takes the
 * note out of the outbox, so nothing is sent. [lineId] is the line's, `outbox:<id>`. Show it only
 * while [rememberUndoOpen] says the window is open. While [choice] has a word's choices open it
 * stops and says "Paused". Send now, beside it, ends the wait at once, closing any word's choices
 * with the words as they are. Either button works once: after one, neither does anything more.
 */
@Composable
internal fun UndoVoiceNote(lineId: String, until: Long?, modifier: Modifier = Modifier, choice: WordChoice? = null) {
    val ui = LocalFeedUi.current
    if (until == null || ui == null) return
    val colors = AgentTheme.colors
    val motion = AgentTheme.motion
    val now = LocalNow.current
    // Counted down by the waits themselves, so a test's fixed clock still sees it tick.
    var left by remember(until) { mutableLongStateOf((until - now()).coerceAtLeast(0)) }
    val drained = remember(until) { Animatable(shareLeft(left)) }
    // Held while the owner picks a word: the outbox holds it too, so nothing goes meanwhile.
    val paused = choice?.open != null
    LaunchedEffect(until, paused) {
        if (paused) return@LaunchedEffect
        while (left > 0) {
            val step = (left % 1_000).takeIf { it > 0 } ?: 1_000
            delay(step)
            left = (left - step).coerceAtLeast(0)
        }
    }
    LaunchedEffect(until, motion.reduced, paused) {
        // With reduced motion the fill steps down once a second instead of sliding.
        if (paused) return@LaunchedEffect
        if (motion.reduced) snapshotFlow { left }.collect { drained.snapTo(shareLeft(it)) }
        else drained.animateTo(0f, tween(left.toInt(), easing = LinearEasing))
    }
    val seconds = ((left + 999) / 1_000).coerceAtLeast(1)
    val drain = colors.accentSoft
    val shape = AgentTheme.shapes.pill
    val outboxId = lineId.removePrefix(DraftPrefix)
    // Set by the first tap on either button, so a second tap does nothing until the line moves on.
    var settled by remember(lineId) { mutableStateOf(false) }
    val undo = {
        if (!settled) {
            settled = true
            ui.emit(FeedEvent.DropUnsent(outboxId))
        }
    }
    val sendNow = {
        if (!settled) {
            settled = true
            choice?.open = null
            ui.emit(FeedEvent.SendUnsentNow(outboxId))
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // The whole pill is the Undo button: a full-size target around a small pill.
        Box(Modifier.clip(shape).pressable("Undo", shape, labelIsShown = true, onClick = undo), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier
                    .height(28.dp)
                    .clip(shape)
                    .background(colors.surfaceSunken)
                    // The time left, as a fill that shrinks to nothing at the deadline. Read while drawing, so it never recomposes.
                    .drawBehind { drawRect(drain, size = Size(size.width * drained.value, size.height)) }
                    .border(1.dp, colors.border, shape)
                    .padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Mic, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                MetaText(if (paused) "Paused" else "Sending in ${seconds}s", Modifier.padding(start = 6.dp), color = colors.textMuted)
                Text(
                    "Undo",
                    Modifier.padding(start = 10.dp),
                    color = colors.accent,
                    style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        // Send now: a pill of its own beside it, with its own full-size target.
        Box(
            Modifier.padding(start = 4.dp).clip(shape).pressable("Send now", shape, labelIsShown = true, onClick = sendNow),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Send now",
                Modifier
                    .height(28.dp)
                    .clip(shape)
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, shape)
                    .padding(horizontal = 12.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
                color = colors.accent,
                style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** How much of the pill is still to drain with [leftMillis] to go. */
private fun shareLeft(leftMillis: Long): Float = (leftMillis.toFloat() / VoiceNote.UndoMillis).coerceIn(0f, 1f)

/** Which unsure word of a waiting voice note has its choices open, by its place in the note's words; null when none. */
@Stable
internal class WordChoice {
    var open by mutableStateOf<Int?>(null)
}

/**
 * A voice note's words, with the ones the phone wasn't sure of underlined with dots, and a tap on
 * one offering the other guesses. While the note waits to go ([sent] false) the tap holds the
 * countdown ([FeedEvent.PauseUnsent]), and picking one, or keeping the word as it is, settles it and
 * starts a fresh countdown ([FeedEvent.ChooseWord]). Once it is up, picking another word edits the
 * message ([FeedEvent.ChooseSentWord]).
 */
@Composable
internal fun UnsureWords(lineId: String, text: String, words: List<VoiceWord>, choice: WordChoice, modifier: Modifier = Modifier, sent: Boolean = false) {
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    val density = LocalDensity.current
    val outboxId = lineId.removePrefix(DraftPrefix)
    val open = { index: Int ->
        if (choice.open == null) {
            choice.open = index
            if (!sent) ui?.emit(FeedEvent.PauseUnsent(outboxId))
        }
    }
    val settle = { index: Int, picked: String? ->
        choice.open = null
        ui?.emit(if (sent) FeedEvent.ChooseSentWord(lineId, index, picked) else FeedEvent.ChooseWord(outboxId, index, picked))
    }
    // In the menu too: a tap outside an open menu only closes it, so the pill's Send now can't be reached in one.
    val sendNow = {
        choice.open = null
        ui?.emit(FeedEvent.SendUnsentNow(outboxId))
    }
    val onWord by rememberUpdatedState(open)
    // Only words that still fit the text: an older line may have changed under them.
    val shown = remember(text, words) { words.withIndex().filter { (_, w) -> w.start in 0 until w.end && w.end <= text.length } }
    val annotated = remember(text, shown) {
        buildAnnotatedString {
            append(text)
            shown.forEach { (index, w) ->
                // Plain text, not a link's look: the dots below say it can be changed.
                addLink(LinkAnnotation.Clickable("word-$index", PlainWord) { onWord(index) }, w.start, w.end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val dots = colors.textMuted
    Box(modifier) {
        Text(
            annotated,
            Modifier.drawBehind {
                val laid = layout ?: return@drawBehind
                val stroke = 1.5.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
                shown.forEach { (_, w) ->
                    val line = laid.getLineForOffset(w.start)
                    val endLine = laid.getLineForOffset(w.end)
                    val x0 = laid.getHorizontalPosition(w.start, usePrimaryDirection = true)
                    val x1 = if (endLine == line) laid.getHorizontalPosition(w.end, usePrimaryDirection = true) else laid.getLineRight(line)
                    val y = laid.getLineBaseline(line) + 3.dp.toPx()
                    drawLine(dots, Offset(x0, y), Offset(x1, y), strokeWidth = stroke, pathEffect = dash)
                }
            },
            color = colors.text,
            style = AgentTheme.type.body,
            onTextLayout = { layout = it },
        )
        val index = choice.open
        val word = index?.let { i -> shown.firstOrNull { it.index == i }?.value }
        if (index != null && word != null) {
            val laid = layout
            val offset = with(density) {
                if (laid == null) DpOffset.Zero
                else {
                    val line = laid.getLineForOffset(word.start)
                    DpOffset(laid.getHorizontalPosition(word.start, true).toDp(), (laid.getLineBottom(line) - laid.size.height).toDp())
                }
            }
            val said = text.substring(word.start, word.end)
            DropdownMenu(
                expanded = true,
                onDismissRequest = { settle(index, null) },
                offset = offset,
                shape = AgentTheme.shapes.medium,
                containerColor = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.border),
                modifier = Modifier.semantics { paneTitle = "Other words for $said" },
            ) {
                word.alternatives.forEach { other ->
                    DropdownMenuItem(
                        text = { Text(other, style = AgentTheme.type.label) },
                        onClick = { settle(index, other) },
                        colors = MenuDefaults.itemColors(textColor = colors.text),
                    )
                }
                if (word.alternatives.isEmpty()) {
                    MetaText("No other guesses", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.textMuted)
                }
                DropdownMenuItem(
                    text = { Text("Keep “$said”", style = AgentTheme.type.label) },
                    leadingIcon = { Icon(Icons.Check, null, modifier = Modifier.size(16.dp)) },
                    onClick = { settle(index, null) },
                    colors = MenuDefaults.itemColors(textColor = colors.textMuted, leadingIconColor = colors.textMuted),
                )
                if (!sent) {
                    DropdownMenuItem(
                        text = { Text("Send now", style = AgentTheme.type.label) },
                        onClick = { sendNow() },
                        colors = MenuDefaults.itemColors(textColor = colors.accent),
                    )
                }
            }
        }
    }
}

private val PlainWord = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None))
