import AVFoundation
import Shared
import SwiftUI
import UIKit

/// Hold to talk: press the mic to record, let go to send, slide away to cancel.
/// The name beside it says which project the note goes to; tap it to pick another.
struct VoiceNoteButton: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var notes = VoiceNotes.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var projects: [ProjectRef] = []
    @State private var picked: String?
    @State private var isHolding = false
    @State private var willCancel = false
    @State private var working: String?
    @State private var notice: String?

    private static let cancelDistance: CGFloat = 80

    private var destination: ProjectRef? {
        VoiceNote.shared.destination(
            picked: picked, path: model.currentPath, lastUsed: CredentialStore.lastProject?.slug, projects: projects
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let startedAt = notes.startedAt {
                RecordingPanel(startedAt: startedAt, level: notes.level, destination: destination?.name, willCancel: willCancel)
            } else if let status = working ?? notice {
                Pill(text: status)
            }
            HStack(spacing: 8) {
                microphone
                projectPicker
            }
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await loadProjects()
            if VoiceNotes.microphoneAllowed { await Transcriber.prepare() }
        }
        .onChange(of: model.currentPath) { picked = nil }
    }

    private var microphone: some View {
        Image(systemName: "mic.fill")
            .font(.title2)
            .frame(width: 28, height: 28)
            .foregroundStyle(notes.isRecording ? Color.white : Color.white.opacity(0.55))
            .padding(6)
            .background(notes.isRecording ? Color.red : Color.black.opacity(0.25), in: Circle())
            .scaleEffect(notes.isRecording ? 1.3 : 1)
            .animation(.spring(duration: 0.2), value: notes.isRecording)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        if !isHolding {
                            isHolding = true
                            begin()
                        }
                        willCancel = max(abs(drag.translation.width), abs(drag.translation.height)) > Self.cancelDistance
                    }
                    .onEnded { _ in
                        if willCancel { notes.cancel() } else { send() }
                        isHolding = false
                        willCancel = false
                    }
            )
            .accessibilityLabel("Hold to record a voice note")
    }

    private var projectPicker: some View {
        Menu {
            Picker("Send voice notes to", selection: Binding(get: { destination?.slug }, set: { picked = $0 })) {
                ForEach(projects, id: \.slug) { Text($0.name).tag(Optional($0.slug)) }
            }
        } label: {
            Text(destination?.name ?? "No project")
                .font(.footnote.weight(.medium))
                .lineLimit(1)
                .foregroundStyle(.white.opacity(0.7))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.black.opacity(0.25), in: Capsule())
        }
        .accessibilityLabel("Voice notes go to \(destination?.name ?? "no project")")
    }

    private func begin() {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            do {
                try notes.start()
            } catch {
                model.lastError = "Couldn't record: \(error.localizedDescription)"
            }
        case .undetermined:
            Task {
                guard await AVAudioApplication.requestRecordPermission() else { return }
                show("Ready. Hold the mic to record.")
                await Transcriber.prepare()
            }
        default:
            model.lastError = "Agents can't use the microphone. Turn it on in Settings, under Agents."
        }
    }

    private func send() {
        guard notes.isRecording else { return }
        let project = destination
        Task {
            defer { working = nil }
            do {
                working = "Writing it down…"
                guard let text = try await notes.stop() else {
                    show("Hold the mic to record")
                    return
                }
                working = "Sending…"
                let sent = try await notes.post(text, to: project)
                show("Sent to \(sent.name)")
            } catch VoiceNoteError.nothingHeard {
                show("Didn't catch any words. Nothing was sent.")
            } catch {
                model.lastError = "Your voice note didn't send: \(error.localizedDescription)"
            }
        }
    }

    private func show(_ text: String) {
        notice = text
        Task {
            try? await Task.sleep(for: .seconds(3))
            if notice == text { notice = nil }
        }
    }

    private func loadProjects() async {
        guard let client = Dashboard.client() else { return }
        defer { client.close() }
        if let summary = try? await client.summary() { projects = summary.projects }
    }
}

private struct RecordingPanel: View {
    let startedAt: Date
    let level: Float
    let destination: String?
    let willCancel: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle().fill(.red).frame(width: 8, height: 8)
                Text(startedAt, style: .timer).monospacedDigit()
                Capsule()
                    .fill(.white.opacity(0.85))
                    .frame(width: 6 + 74 * CGFloat(level), height: 4)
                    .frame(width: 80, alignment: .leading)
                    .animation(.linear(duration: 0.1), value: level)
            }
            .font(.subheadline.weight(.semibold))
            Text(willCancel ? "Let go to cancel" : "Let go to send\(destination.map { " to \($0)" } ?? ""), slide away to cancel")
                .font(.caption)
                .foregroundStyle(willCancel ? Color.orange : Color.white.opacity(0.7))
        }
        .foregroundStyle(.white)
        .padding(10)
        .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct Pill: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.75), in: Capsule())
    }
}
