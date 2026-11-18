import AVFoundation
import ComposeApp
import UIKit

/// The composer's hold-to-talk mic, on the same recorder and on-device transcriber as the lock
/// screen's voice notes. The words go into the message box to check; nothing is sent from here.
final class VoiceNoteInput: NSObject, VoiceInput {
    func start(onStarted: @escaping (String?) -> Void) {
        Task { @MainActor in
            switch AVAudioApplication.shared.recordPermission {
            case .granted:
                do {
                    try VoiceNotes.shared.start()
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onStarted(nil)
                } catch {
                    onStarted("Couldn't record: \(error.localizedDescription)")
                }
            case .undetermined:
                // The first press only asks; the owner holds again once it is allowed.
                let allowed = await AVAudioApplication.requestRecordPermission()
                onStarted(allowed ? "Ready. Hold the mic to talk." : Self.microphoneOff)
                if allowed { await Transcriber.prepare() }
            default:
                onStarted(Self.microphoneOff)
            }
        }
    }

    func finish(onDone: @escaping (String?, String?) -> Void) {
        Task { @MainActor in
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
        Task { @MainActor in VoiceNotes.shared.cancel() }
    }

    private static let microphoneOff = "Wildware Feed can't use the microphone. Turn it on in Settings, under Wildware Feed."
}
