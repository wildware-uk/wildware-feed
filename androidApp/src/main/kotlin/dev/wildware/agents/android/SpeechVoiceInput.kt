package dev.wildware.agents.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.wildware.agents.app.feed.VoiceInput

/**
 * Hold-to-talk with Android's speech recogniser, on the phone itself where it can (Android 12+
 * with an on-device model), else the system's usual one. Nothing is recorded to a file: the
 * words come straight back for the owner to check.
 *
 * Android's recogniser stops by itself after a pause. While the mic is still held this starts it
 * again and keeps what was heard, so a long note with pauses in it comes back whole.
 *
 * It reports what it has heard as the owner speaks (partial results), so the words show while
 * they talk; the finished words replace them.
 */
class SpeechVoiceInput(private val context: Context, private val hooks: ActivityHooks) : VoiceInput {
    private var recognizer: SpeechRecognizer? = null
    private var heard = StringBuilder()
    private var holding = false
    private var listening = false
    private var onStarted: ((String?) -> Unit)? = null
    private var onHeard: ((String) -> Unit)? = null
    private var onDone: ((String?, String?) -> Unit)? = null

    override fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return onStarted("This phone has no speech recognition")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val ask = hooks.askPermission ?: return onStarted("Allow the microphone in Settings to talk")
            // The finger has usually let go by the time the dialog answers, so this press only asks.
            ask(Manifest.permission.RECORD_AUDIO) { }
            return onStarted("Allow the microphone, then hold to talk")
        }
        heard = StringBuilder()
        holding = true
        this.onStarted = onStarted
        this.onHeard = onHeard
        listen()
    }

    override fun finish(onDone: (text: String?, problem: String?) -> Unit) {
        holding = false
        this.onDone = onDone
        if (listening) recognizer?.stopListening() else deliver()
    }

    override fun cancel() {
        holding = false
        onDone = null
        onStarted = null
        onHeard = null
        recognizer?.cancel()
        release()
    }

    private fun listen() {
        val r = recognizer ?: create().also { recognizer = it }
        r.setRecognitionListener(listener)
        listening = true
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Asks for long pauses; many recognisers stop sooner anyway, which listen() covers.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L),
        )
    }

    private fun create(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun deliver(problem: String? = null) {
        val done = onDone ?: return
        onDone = null
        onHeard = null
        release()
        val text = heard.toString().trim()
        if (text.isNotEmpty()) done(text, null) else done(null, problem ?: "Didn't catch that. Hold and try again")
    }

    private fun release() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun keep(results: Bundle?) {
        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (words.isNotEmpty()) {
            if (heard.isNotEmpty()) heard.append(' ')
            heard.append(words)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onStarted?.invoke(null)
            onStarted = null
        }

        override fun onResults(results: Bundle?) {
            listening = false
            keep(results)
            onHeard?.invoke(heard.toString())
            if (holding) listen() else deliver()
        }

        override fun onError(error: Int) {
            listening = false
            val started = onStarted
            if (started != null) {
                onStarted = null
                holding = false
                release()
                return started(problemOf(error))
            }
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            when {
                holding && quiet -> listen()
                holding -> {
                    // Gave up while still held: keep what was heard; letting go hands it over.
                    holding = false
                    release()
                }
                else -> deliver(if (quiet) null else problemOf(error))
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            // What this stretch has heard so far, after what earlier stretches kept.
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (words.isEmpty()) return
            onHeard?.invoke(if (heard.isEmpty()) words else "$heard $words")
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun problemOf(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone in Settings to talk"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech needs the network on this phone. Try again with signal"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again in a moment"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't work"
        else -> "Didn't catch that. Hold and try again"
    }
}
