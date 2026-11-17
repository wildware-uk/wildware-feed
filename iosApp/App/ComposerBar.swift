import AVFoundation
import PhotosUI
import Shared
import SwiftUI
import UIKit

/// The bar along the bottom of the dashboard: type, hold the mic to talk, add photos, send.
/// The chip above it says where the message goes: the project on screen, an agent's question you
/// chose to reply to, or the page's own text box you were using. Tap the chip to change it.
/// Spoken words land in the bar's field to check before sending, or in the page's text box when that is
/// where it goes. The bar steps aside while the page's own text box has the keyboard.
struct ComposerBar: View {
    /// iPhone shows the Inbox from the bar; iPad has it in the sidebar.
    var showsInbox = true
    /// iPhone keeps the app's menu here; iPad keeps it in the toolbar.
    var showsMenu = true

    @EnvironmentObject private var model: AppModel
    @ObservedObject private var store = InboxStore.shared
    @ObservedObject private var notes = VoiceNotes.shared
    @FocusState private var fieldFocused: Bool
    @State private var draft = ""
    @State private var picking: [PhotosPickerItem] = []
    @State private var photos: [PickedPhoto] = []
    @State private var isHolding = false
    @State private var willCancel = false
    @State private var working: String?
    @State private var notice: String?

    private static let maxPhotos = 4

    private var target: ComposerTarget? {
        Composer.shared.target(
            chosen: model.composerChoice,
            pageBox: model.pageBox,
            path: model.currentPath,
            lastUsed: CredentialStore.lastProject?.slug,
            projects: store.projects
        )
    }

    private var intoPage: Bool { target is ComposerTarget.PageTextBox }
    private var canSend: Bool {
        working == nil && Composer.shared.canSend(text: draft, photoCount: Int32(photos.count), target: target)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                targetChip
                if let status = working ?? notice {
                    Text(status).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                if showsMenu { AppMenu(compact: true) }
            }
            if !photos.isEmpty { photoStrip }
            HStack(alignment: .bottom, spacing: 8) {
                if showsInbox { inboxButton }
                TextField(intoPage ? "Words for the page's box" : "Message", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($fieldFocused)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                photoButton
                microphone
                sendButton
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 6)
        .background(.bar)
        .overlay(alignment: .top) {
            if let startedAt = notes.startedAt {
                RecordingPanel(
                    startedAt: startedAt,
                    goesTo: intoPage ? "Into the page's text box" : "Into the message box",
                    willCancel: willCancel
                )
                .alignmentGuide(.top) { $0[.bottom] + 8 }
            }
        }
        .onChange(of: model.composerFocusRequest) { fieldFocused = true }
        .onChange(of: model.currentPath) {
            // A project picked by hand belongs to the page it was picked on, as the old mic's picker did.
            if model.composerChoice is ComposerTarget.Project { model.composerChoice = nil }
        }
        .onChange(of: picking) { load(picking) }
        .task {
            if VoiceNotes.microphoneAllowed { await Transcriber.prepare() }
        }
    }

    // MARK: Where it goes

    private var targetChip: some View {
        HStack(spacing: 4) {
            Menu {
                if let box = model.pageBox {
                    Button(box.label, systemImage: "character.cursor.ibeam") { model.composerChoice = box }
                }
                Section("Post to a project") {
                    ForEach(store.projects, id: \.slug) { project in
                        Button(project.name) { model.composerChoice = ComposerTarget.Project(project: project) }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: icon(for: target))
                    Text(target?.label ?? "No project yet").lineLimit(1)
                    Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                }
                .font(.caption.weight(.medium))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color.accentColor.opacity(0.18), in: Capsule())
            }
            .accessibilityLabel("Sends \(target?.label ?? "nowhere yet"). Change where it goes.")
            if target is ComposerTarget.Reply {
                Button {
                    model.composerChoice = nil
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .accessibilityLabel("Stop replying")
            }
        }
    }

    private func icon(for target: ComposerTarget?) -> String {
        if target is ComposerTarget.Reply { return "arrowshape.turn.up.left.fill" }
        if target is ComposerTarget.PageTextBox { return "character.cursor.ibeam" }
        return "folder"
    }

    private var inboxButton: some View {
        Button { model.showInbox = true } label: {
            Image(systemName: "tray.full")
                .font(.title3)
                .frame(width: 36, height: 36)
                .overlay(alignment: .topTrailing) {
                    if store.count > 0 {
                        Text("\(store.count)")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(.red, in: Capsule())
                            .offset(x: 6, y: -4)
                    }
                }
        }
        .accessibilityLabel(store.count == 1 ? "Inbox, 1 question waiting" : "Inbox, \(store.count) questions waiting")
    }

    // MARK: Photos

    private var photoButton: some View {
        PhotosPicker(
            selection: $picking,
            maxSelectionCount: max(1, Self.maxPhotos - photos.count),
            matching: .images
        ) {
            Image(systemName: "photo").font(.title3).frame(width: 32, height: 36)
        }
        .disabled(!Composer.shared.acceptsPhotos(target: target) || photos.count >= Self.maxPhotos)
        .accessibilityLabel("Add photos")
    }

    private var photoStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(photos) { photo in
                    Image(uiImage: photo.image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 56, height: 56)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .topTrailing) {
                            Button {
                                photos.removeAll { $0.id == photo.id }
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.7))
                            }
                            .offset(x: 4, y: -4)
                            .accessibilityLabel("Remove photo")
                        }
                }
            }
            .padding(.top, 4)
        }
    }

    private func load(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        picking = []
        Task {
            for item in items where photos.count < Self.maxPhotos {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let photo = PickedPhoto(data: data, index: photos.count) else { continue }
                photos.append(photo)
            }
        }
    }

    // MARK: Sending

    private var sendButton: some View {
        Button(action: send) {
            Image(systemName: "arrow.up.circle.fill")
                .font(.system(size: 30))
                .frame(width: 36, height: 36)
        }
        .disabled(!canSend)
        .accessibilityLabel("Send")
    }

    private func send() {
        guard canSend, let target else { return }
        let text = draft
        let sentPhotos = photos
        draft = ""
        photos = []
        Task {
            do {
                if target is ComposerTarget.PageTextBox {
                    guard await model.pageTextBox.insert(text) else {
                        draft = text
                        show("The page's text box has gone. Pick where it goes.")
                        return
                    }
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    show("In the page's text box. Check it, then send it there.")
                    return
                }
                working = "Sending…"
                defer { working = nil }
                if !Sample.isOn {
                    guard let client = Dashboard.client() else { throw DashboardError.notLinked }
                    defer { client.close() }
                    _ = try await client.send(thread: target.thread ?? [:], text: text, attachments: sentPhotos.map(\.attachment))
                }
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                if let project = target as? ComposerTarget.Project {
                    CredentialStore.lastProject = project.project
                    show("Sent to \(project.project.name)")
                } else if let reply = target as? ComposerTarget.Reply {
                    model.composerChoice = nil
                    show("Sent to \(reply.agentName)")
                }
            } catch {
                // Put it all back so nothing typed is lost.
                draft = text
                photos = sentPhotos
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                model.lastError = "That didn't send: \(error.localizedDescription)"
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

    // MARK: Talking

    private var microphone: some View {
        ZStack {
            if notes.isRecording {
                Circle()
                    .stroke(Color.red.opacity(0.6), lineWidth: 3)
                    .scaleEffect(1.1 + 0.4 * CGFloat(notes.level))
                    .animation(.linear(duration: 0.1), value: notes.level)
            }
            Circle().fill(notes.isRecording ? Color.red : Color.accentColor)
            Image(systemName: "mic.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: 36, height: 36)
        .contentShape(Circle())
        .scaleEffect(notes.isRecording ? 1.2 : 1)
        .animation(.spring(duration: 0.2), value: notes.isRecording)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { drag in
                    if !isHolding {
                        isHolding = true
                        beginRecording()
                    }
                    willCancel = max(abs(drag.translation.width), abs(drag.translation.height)) > 80
                }
                .onEnded { _ in
                    if willCancel { notes.cancel() } else { finishRecording() }
                    isHolding = false
                    willCancel = false
                }
        )
        .accessibilityLabel("Hold to talk")
        .accessibilityHint("Your words go into the message box to check before sending")
    }

    private func beginRecording() {
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
                show("Ready. Hold the mic to talk.")
                await Transcriber.prepare()
            }
        default:
            model.lastError = "Agents can't use the microphone. Turn it on in Settings, under Agents."
        }
    }

    /// Writes down what was said. It never sends by itself: the words wait in the bar, or in the page's box.
    private func finishRecording() {
        guard notes.isRecording else { return }
        let toPage = intoPage
        Task {
            defer { working = nil }
            do {
                working = "Writing it down…"
                guard let text = try await notes.stop() else {
                    show("Hold the mic to talk")
                    return
                }
                if toPage, await model.pageTextBox.insert(text) {
                    show("Added to the page's text box. Check it, then send.")
                    return
                }
                draft = Composer.shared.appendDictation(existing: draft, dictated: text)
                show("Check it, then send")
            } catch VoiceNoteError.nothingHeard {
                show("Didn't catch any words.")
            } catch {
                model.lastError = "Couldn't write that down: \(error.localizedDescription)"
            }
        }
    }
}

/// A photo picked for the next message: a thumbnail to show, and the JPEG that goes up.
struct PickedPhoto: Identifiable {
    let id = UUID()
    let image: UIImage
    let attachment: Attachment

    /// The longest side the dashboard gets. Phone photos are far bigger than an agent needs to read one.
    private static let maxSide: CGFloat = 2048

    init?(data: Data, index: Int) {
        guard let original = UIImage(data: data) else { return nil }
        let image = Self.fitted(original)
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else { return nil }
        self.image = image
        attachment = IosDashboardKt.attachment(filename: "photo-\(index + 1).jpg", mimeType: "image/jpeg", data: jpeg)
    }

    private static func fitted(_ image: UIImage) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxSide else { return image }
        let scale = maxSide / longest
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

private struct RecordingPanel: View {
    let startedAt: Date
    let goesTo: String
    let willCancel: Bool

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                Circle().fill(.red).frame(width: 8, height: 8)
                Text(startedAt, style: .timer).monospacedDigit()
                Text(goesTo).lineLimit(1)
            }
            .font(.subheadline.weight(.semibold))
            Text(willCancel ? "Let go to cancel" : "Let go to finish, slide away to cancel")
                .font(.caption)
                .foregroundStyle(willCancel ? Color.orange : Color.white.opacity(0.8))
        }
        .foregroundStyle(.white)
        .padding(10)
        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: .infinity)
    }
}
