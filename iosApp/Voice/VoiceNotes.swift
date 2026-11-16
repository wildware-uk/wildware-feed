import ActivityKit
import AVFoundation
import Foundation
import Shared

enum VoiceNoteError: LocalizedError {
    case microphoneOff, liveActivitiesOff, couldNotRecord, noProject

    var errorDescription: String? {
        switch self {
        case .microphoneOff: "Open Agents and allow the microphone once, then try again."
        case .liveActivitiesOff: "Turn on Live Activities for Agents in Settings to record without unlocking."
        case .couldNotRecord: "The microphone is busy. Try again in a moment."
        case .noProject: "There's no project to send it to yet."
        }
    }
}

/// Records voice notes, writes down what was said, and posts both to a project's feed.
/// The hold-to-talk button, the lock screen control, the Action button and Siri all share this one recorder.
@MainActor
final class VoiceNotes: ObservableObject {
    private typealias NoteState = VoiceNoteActivityAttributes.ContentState

    static let shared = VoiceNotes()

    @Published private(set) var startedAt: Date?
    @Published private(set) var level: Float = 0
    @Published private(set) var isSending = false

    private var recorder: AVAudioRecorder?
    private var metering: Task<Void, Never>?

    var isRecording: Bool { recorder != nil }

    static var microphoneAllowed: Bool { AVAudioApplication.shared.recordPermission == .granted }

    func start() throws {
        guard recorder == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .default)
        try session.setAllowHapticsAndSystemSoundsDuringRecording(true)
        try session.setActive(true)

        let file = URL.temporaryDirectory.appending(path: "voice-note-\(UUID().uuidString).m4a")
        let recorder = try AVAudioRecorder(url: file, settings: [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ])
        recorder.isMeteringEnabled = true
        guard recorder.record() else {
            try? session.setActive(false)
            throw VoiceNoteError.couldNotRecord
        }
        self.recorder = recorder
        startedAt = .now
        metering = Task { [weak self] in
            while !Task.isCancelled, let recorder = self?.recorder {
                recorder.updateMeters()
                self?.level = Self.loudness(recorder.averagePower(forChannel: 0))
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }

    func cancel() {
        guard let recording = stopRecording() else { return }
        try? FileManager.default.removeItem(at: recording.file)
        Task { await endActivity(nil) }
    }

    /// Stops recording, writes down what was said and posts both. A nil `project` means the last one used.
    /// Returns where it went, or nil when the press was too short to be a note.
    @discardableResult
    func finish(to project: ProjectRef? = nil) async throws -> ProjectRef? {
        guard let recording = stopRecording() else {
            // The app was restarted mid-note, so a Stop button is all that's left of it.
            await endActivity(nil)
            return nil
        }
        defer { try? FileManager.default.removeItem(at: recording.file) }
        guard VoiceNote.shared.isLongEnough(seconds: recording.seconds) else {
            await endActivity(nil)
            return nil
        }

        isSending = true
        defer { isSending = false }
        await updateActivity(NoteState(phase: .sending))
        do {
            let sent = try await send(recording.file, to: project)
            CredentialStore.lastProject = sent
            await endActivity(NoteState(phase: .sent, detail: sent.name))
            return sent
        } catch {
            await endActivity(NoteState(phase: .failed, detail: error.localizedDescription))
            throw error
        }
    }

    /// What the lock screen control, the Action button and Siri do: start a note, or send the one recording.
    func toggleFromIntent() async throws {
        if isRecording {
            try await finish()
            return
        }
        guard Dashboard.isLinked else { throw DashboardError.notLinked }
        // Nothing can ask for permission from the lock screen, so the app must have asked already.
        guard Self.microphoneAllowed else { throw VoiceNoteError.microphoneOff }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { throw VoiceNoteError.liveActivitiesOff }

        // iOS stops a recording started this way unless a Live Activity shows it from the start.
        _ = try Activity.request(
            attributes: VoiceNoteActivityAttributes(startedAt: .now, projectName: CredentialStore.lastProject?.name),
            content: ActivityContent(state: NoteState(phase: .recording), staleDate: nil)
        )
        do {
            try start()
        } catch {
            await endActivity(NoteState(phase: .failed, detail: error.localizedDescription))
            throw error
        }
    }

    private func stopRecording() -> (file: URL, seconds: TimeInterval)? {
        guard let recorder else { return nil }
        let seconds = recorder.currentTime
        recorder.stop()
        self.recorder = nil
        metering?.cancel()
        startedAt = nil
        level = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return (recorder.url, seconds)
    }

    private func send(_ file: URL, to project: ProjectRef?) async throws -> ProjectRef {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        var destination = project
        if destination == nil {
            let projects = try await client.summary().projects
            destination = VoiceNote.shared.destination(
                picked: nil, path: nil, lastUsed: CredentialStore.lastProject?.slug, projects: projects
            )
        }
        guard let destination else { throw VoiceNoteError.noProject }
        let transcript = await Transcriber.transcribe(file)
        let audio = try Data(contentsOf: file)
        _ = try await client.share(
            projectSlug: destination.slug,
            text: VoiceNote.shared.messageBody(transcript: transcript),
            attachments: [IosDashboardKt.voiceNoteAttachment(data: audio)]
        )
        return destination
    }

    private func updateActivity(_ state: NoteState) async {
        for activity in Activity<VoiceNoteActivityAttributes>.activities {
            await activity.update(ActivityContent(state: state, staleDate: nil))
        }
    }

    /// Ends the Live Activity. An outcome stays on the lock screen: a sent note for a moment, a failure until dismissed.
    private func endActivity(_ outcome: NoteState?) async {
        for activity in Activity<VoiceNoteActivityAttributes>.activities {
            guard let outcome else {
                await activity.end(nil, dismissalPolicy: .immediate)
                continue
            }
            let dismissal: ActivityUIDismissalPolicy = outcome.phase == .sent ? .after(.now + 30) : .default
            await activity.end(ActivityContent(state: outcome, staleDate: nil), dismissalPolicy: dismissal)
        }
    }

    /// The meter's decibels (-160 silent, 0 loudest) as 0...1, with ordinary speech near the middle.
    private static func loudness(_ decibels: Float) -> Float {
        min(max((decibels + 50) / 50, 0), 1)
    }
}
