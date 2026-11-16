import AVFoundation
import Shared
import SwiftUI
import UIKit

/// Hold to talk: press the mic to record, let go to send, slide away to cancel.
/// The name above it says which project the note goes to; tap it to pick another.
struct VoiceNoteButton: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var notes = VoiceNotes.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var projects: [ProjectRef] = []
    @State private var picked: String?
    @State private var isHolding = false
    @State private var willCancel = false
    @State private var isKeyboardUp = false
    @State private var working: String?
    @State private var notice: String?

    private static let cancelDistance: CGFloat = 80
    private static let size: CGFloat = 64

    private var destination: ProjectRef? {
        VoiceNote.shared.destination(
            picked: picked, path: model.currentPath, lastUsed: CredentialStore.lastProject?.slug, projects: projects
        )
    }

    var body: some View {
        VStack(spacing: 8) {
            if let startedAt = notes.startedAt {
                RecordingPanel(
                    startedAt: startedAt,
                    goesTo: destination.map { "To \($0.name)" },
                    willCancel: willCancel
                )
            } else if let status = working ?? notice {
                Pill(text: status)
            } else {
                projectPicker
            }
            microphone
        }
        // The page's text box sits just above the keyboard, so the mic steps aside while typing.
        .opacity(isKeyboardUp && !notes.isRecording ? 0 : 1)
        .allowsHitTesting(!isKeyboardUp || notes.isRecording)
        .animation(.easeOut(duration: 0.15), value: isKeyboardUp)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardUp = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardUp = false
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await loadProjects()
            if VoiceNotes.microphoneAllowed { await Transcriber.prepare() }
        }
        .onChange(of: model.currentPath) { picked = nil }
    }

    private var microphone: some View {
        ZStack {
            if notes.isRecording {
                Circle()
                    .stroke(Color.red.opacity(0.6), lineWidth: 4)
                    .scaleEffect(1.08 + 0.4 * CGFloat(notes.level))
                    .animation(.linear(duration: 0.1), value: notes.level)
            }
            Circle()
                .fill(notes.isRecording ? Color.red : Color.white)
                .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
            Image(systemName: "mic.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(notes.isRecording ? Color.white : Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x19 / 255))
        }
        .frame(width: Self.size, height: Self.size)
        .contentShape(Circle())
        .scaleEffect(notes.isRecording ? 1.1 : 1)
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
                .font(.caption.weight(.medium))
                .lineLimit(1)
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(.black.opacity(0.6), in: Capsule())
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
    let goesTo: String?
    let willCancel: Bool

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Circle().fill(.red).frame(width: 8, height: 8)
                Text(startedAt, style: .timer).monospacedDigit()
                if let goesTo { Text(goesTo).lineLimit(1) }
            }
            .font(.subheadline.weight(.semibold))
            Text(willCancel ? "Let go to cancel" : "Let go to finish, slide away to cancel")
                .font(.caption)
                .foregroundStyle(willCancel ? Color.orange : Color.white.opacity(0.8))
        }
        .foregroundStyle(.white)
        .padding(10)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
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
