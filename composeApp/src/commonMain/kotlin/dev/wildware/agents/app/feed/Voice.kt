package dev.wildware.agents.app.feed

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

    /** Stops and writes it down. [onDone] gets the words, or null and what to tell the owner. */
    fun finish(onDone: (text: String?, problem: String?) -> Unit)

    /** Stops and throws the recording away. */
    fun cancel()
}

val LocalVoiceInput = staticCompositionLocalOf<VoiceInput?> { null }

/** How far left the finger slides before letting go throws the note away. */
private val CancelDistance = 100.dp

/** One composer's recording: what the mic button and the strip beside it draw. */
@Stable
class VoiceRecorder(private val input: VoiceInput, private val onWords: (String) -> Unit) {
    enum class Phase { Idle, Starting, Recording, Writing }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** The finger has slid far enough left that letting go cancels. */
    var willCancel by mutableStateOf(false)

    /** A word for the owner under the box: "Check it, then send", or what went wrong. */
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
        input.finish { text, problem ->
            listening = false
            heard = ""
            phase = Phase.Idle
            if (text != null) onWords(text)
            note = problem ?: "Check it, then send"
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
fun rememberVoiceRecorder(input: VoiceInput?, onWords: (String) -> Unit): VoiceRecorder? {
    val words by rememberUpdatedState(onWords)
    return remember(input) { input?.let { VoiceRecorder(it) { text -> words(text) } } }
}

/** Hold to talk, slide left to cancel. A screen reader taps once to start and again to stop. */
@Composable
fun MicButton(recorder: VoiceRecorder) {
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
                stateDescription = if (live) "Recording" else "Your words go into the message to check before sending"
                onClick(if (live) "Stop and write it down" else "Start recording") {
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
