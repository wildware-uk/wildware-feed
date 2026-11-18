import AVFoundation
import Speech

/// Follows a voice note as it is spoken, so the words show in the message box while the owner talks.
/// It is only for showing: the words the note keeps still come from the finished recording, which
/// hears it whole. Anything that stops it (speech not allowed, a busy microphone) just means no live
/// words; the note records and is written down as before.
@MainActor
final class LiveTranscriber {
    static let shared = LiveTranscriber()

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?

    /// Starts listening alongside the recorder. `onHeard` gets everything heard so far, on the main thread.
    func start(onHeard: @escaping (String) -> Void) {
        stop()
        guard SFSpeechRecognizer.authorizationStatus() == .authorized,
              let recognizer = SFSpeechRecognizer(), recognizer.isAvailable
        else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        // On the phone itself where the language allows it, as the finished note is.
        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        request.addsPunctuation = true
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return }
        Self.feed(input, into: request, format: format)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            return
        }
        self.recognizer = recognizer
        self.request = request
        task = recognizer.recognitionTask(with: request, resultHandler: Self.handler(onHeard))
    }

    /// Stops listening. The recorder's own file is untouched.
    func stop() {
        guard request != nil else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        recognizer = nil
    }

    // These closures run on the audio and speech threads, so they are made outside the main actor.

    nonisolated private static func feed(_ input: AVAudioInputNode, into request: SFSpeechAudioBufferRecognitionRequest, format: AVAudioFormat) {
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }
    }

    nonisolated private static func handler(_ onHeard: @escaping (String) -> Void) -> (SFSpeechRecognitionResult?, Error?) -> Void {
        { result, _ in
            guard let text = result?.bestTranscription.formattedString, !text.isEmpty else { return }
            DispatchQueue.main.async { onHeard(text) }
        }
    }
}
