import AVFoundation
import ComposeApp
import Speech
import UIKit

/// The composer's hold-to-talk mic, on the same recorder and on-device transcriber as the lock
/// screen's voice notes. The words show as they are spoken (`LiveTranscriber`), then the finished
/// words go into the message box to check; nothing is sent from here.
final class VoiceNoteInput: NSObject, VoiceInput {
    func start(onHeard: @escaping (String) -> Void, onStarted: @escaping (String?) -> Void) {
        Task { @MainActor in
            switch AVAudioApplication.shared.recordPermission {
            case .granted:
                // Speech is asked for once, on its own press, as the microphone was: a dialog in
                // the middle of a held note would cut it short.
                if SFSpeechRecognizer.authorizationStatus() == .notDetermined {
                    await Transcriber.askForSpeech()
                    onStarted("Ready. Hold the mic to talk.")
                    return
                }
                do {
                    try VoiceNotes.shared.start()
                    LiveTranscriber.shared.start(onHeard: onHeard)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onStarted(nil)
                } catch {
                    onStarted("Couldn't record: \(error.localizedDescription)")
                }
            case .undetermined:
                // The first press only asks; the owner holds again once it is allowed.
                let allowed = await AVAudioApplication.requestRecordPermission()
                if allowed {
                    await Transcriber.askForSpeech()
                    await Transcriber.prepare()
                }
                onStarted(allowed ? "Ready. Hold the mic to talk." : Self.microphoneOff)
            default:
                onStarted(Self.microphoneOff)
            }
        }
    }

    func finish(onDone: @escaping (String?, String?) -> Void) {
        Task { @MainActor in
            LiveTranscriber.shared.stop()
            do {
                guard let text = try await VoiceNotes.shared.stop() else {
                    onDone(nil, "Hold the mic to talk")
                    return
                }
                onDone(text, nil)
            } catch VoiceNoteError.nothingHeard {
                onDone(nil, "Didn't catch any words.")
            } catch {
                onDone(nil, "Couldn't write that down: \(error.localizedDescription)")
            }
        }
    }

    func cancel() {
        Task { @MainActor in
            LiveTranscriber.shared.stop()
            VoiceNotes.shared.cancel()
        }
    }

    private static let microphoneOff = "Wildware Feed can't use the microphone. Turn it on in Settings, under Wildware Feed."
}
