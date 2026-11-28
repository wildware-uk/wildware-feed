import Shared
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share to Wildware Feed" from any app: pick a project, add a note, and it lands in that project's feed.
/// It takes text, a link, and any files: photos, videos, PDFs, logs, zips.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let host = UIHostingController(rootView: ShareView(
            items: items,
            finish: { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) },
            cancel: { [weak self] in
                self?.extensionContext?.cancelRequest(withError: CocoaError(.userCancelled))
            }
        ))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }
}

private struct ShareView: View {
    let items: [NSExtensionItem]
    let finish: () -> Void
    let cancel: () -> Void

    @State private var projects: [ProjectRef] = []
    @State private var selected: String?
    @State private var note = ""
    @State private var attachments: [OutboxPhoto] = []
    /// Files that couldn't come, and why: empty, too big, or past what one share carries.
    @State private var leftOut: [String] = []
    /// Queued once already: its files' bytes are let go, so it can't be posted again.
    @State private var spent = false
    @State private var status: Status = .loading

    enum Status: Equatable { case loading, ready, sending, queued, failed(String) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    if case let .failed(message) = status {
                        Banner(text: message, symbol: "exclamationmark.triangle.fill", color: Theme.error)
                    }
                    ForEach(Array(leftOut.enumerated()), id: \.offset) { _, reason in
                        Banner(text: reason, symbol: "doc.badge.ellipsis", color: Theme.waiting)
                    }
                    if status == .queued {
                        Banner(
                            text: "No connection. It's saved, and Wildware Feed will send it when you're back online.",
                            symbol: "clock.arrow.circlepath",
                            color: Theme.waiting
                        )
                    }

                    VStack(alignment: .leading, spacing: Theme.Space.s) {
                        Text("Send to").overline().foregroundStyle(Theme.textMuted)
                        if status == .loading && projects.isEmpty {
                            ProgressView().tint(Theme.textMuted)
                        }
                        FlowLayout(spacing: Theme.Space.s) {
                            ForEach(projects, id: \.slug) { project in
                                ProjectChip(name: project.name, selected: selected == project.slug) {
                                    selected = project.slug
                                }
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: Theme.Space.s) {
                        Text("Note").overline().foregroundStyle(Theme.textMuted)
                        TextField("What should the agent do with this?", text: $note, axis: .vertical)
                            .lineLimit(4...10)
                            .font(Theme.Font.body)
                            .foregroundStyle(Theme.text)
                            .tint(Theme.accent)
                            .padding(Theme.Space.m)
                            .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous).strokeBorder(Theme.border))
                    }

                    if !attachments.isEmpty {
                        VStack(alignment: .leading, spacing: Theme.Space.s) {
                            Text(Self.countLabel(attachments)).overline().foregroundStyle(Theme.textMuted)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Theme.Space.s) {
                                    ForEach(Array(attachments.enumerated()), id: \.offset) { _, file in
                                        if file.mimeType.hasPrefix("image/") {
                                            Thumbnail(data: file.data)
                                        } else {
                                            FileTile(name: file.filename)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(Theme.Space.l + 2)
            }
            .background(Theme.surface)
            .toolbarBackground(Theme.surface, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    HStack(spacing: 7) {
                        AppMarkTile(size: 20)
                        Text("Wildware Feed").font(Theme.Font.sans(17, .semibold, relativeTo: .headline)).foregroundStyle(Theme.text)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: cancel).foregroundStyle(Theme.textMuted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(status == .sending ? "Sending…" : "Post", action: post)
                        .font(Theme.Font.sans(17, .semibold, relativeTo: .body))
                        .foregroundStyle(canPost ? Theme.accent : Theme.textFaint)
                        .disabled(!canPost)
                }
            }
        }
        .task { await load() }
    }

    private var canPost: Bool {
        selected != nil && !spent && status != .sending && status != .loading && status != .queued
    }

    private func load() async {
        guard Dashboard.isLinked else {
            status = .failed(DashboardError.notLinked.localizedDescription)
            return
        }
        async let content = SharedContent.load(from: items)
        // Offline, the projects the app last saw are enough to pick from: the post waits in the outbox.
        projects = CredentialStore.knownProjects
        if let client = Dashboard.client() {
            defer { client.close() }
            if let fresh = try? await client.summary().projects {
                projects = fresh
                CredentialStore.knownProjects = fresh
            }
        }
        selected = CredentialStore.lastProject.flatMap { last in projects.first { $0.slug == last.slug }?.slug } ?? projects.first?.slug
        let loaded = await content
        note = loaded.text
        attachments = loaded.attachments
        leftOut = loaded.leftOut
        status = projects.isEmpty ? .failed("Couldn't reach the dashboard to list your projects. Try again with signal.") : .ready
    }

    /// "1 photo", "3 photos", or "2 files" once anything but pictures is in.
    static func countLabel(_ files: [OutboxPhoto]) -> String {
        let photos = files.allSatisfy { $0.mimeType.hasPrefix("image/") }
        let noun = photos ? "photo" : "file"
        return files.count == 1 ? "1 \(noun)" : "\(files.count) \(noun)s"
    }

    /// What a share with no note says: "Shared from my phone: photo.jpg", or how many files.
    static func caption(for photos: [OutboxPhoto]) -> String? {
        switch photos.count {
        case 0: return nil
        case 1: return "Shared from my phone: \(photos[0].filename)"
        default: return "Shared \(photos.count) files from my phone"
        }
    }

    private func post() {
        guard let selected, let project = projects.first(where: { $0.slug == selected }) else { return }
        // Refused on an empty send, as the dashboard would: nothing came that can go.
        guard !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !attachments.isEmpty else {
            status = .failed("There is nothing to send: add a note.")
            return
        }
        status = .sending
        Task {
            // With no note, the post says where it came from: the app's own composer sends just the file names.
            let words = note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Self.caption(for: attachments) : note
            let queued = OutboxQueue.enqueueMessage(
                label: "To \(project.name)", thread: ["project": project.slug], text: words, photos: attachments
            )
            spent = true
            // On disk now. A share sheet has little memory and sending reads them back, so let go of
            // the files' bytes here; pictures keep theirs for the thumbnails.
            attachments = attachments.map { $0.mimeType.hasPrefix("image/") ? $0 : OutboxPhoto(filename: $0.filename, mimeType: $0.mimeType, data: Data()) }
            let outcome = await OutboxQueue.flush(only: queued)
            if outcome == .sent {
                finish()
            } else if outcome == .refused {
                OutboxQueue.delete(queued)
                status = .failed("The dashboard refused it, so nothing was sent.")
            } else {
                status = .queued
                try? await Task.sleep(for: .seconds(2))
                finish()
            }
        }
    }
}

private struct ProjectChip: View {
    let name: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)) }
                Text(name).lineLimit(1)
            }
            .font(Theme.Font.bodyStrong)
            .padding(.horizontal, Theme.Space.m)
            .frame(minHeight: 36)
            .foregroundStyle(selected ? Theme.onAccent : Theme.text)
            .background {
                if selected {
                    Capsule().fill(Theme.accent)
                } else {
                    Capsule().strokeBorder(Theme.borderStrong)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private struct Banner: View {
    let text: String
    let symbol: String
    let color: Color

    var body: some View {
        Label {
            Text(text).font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.text)
        } icon: {
            Image(systemName: symbol).foregroundStyle(color)
        }
        .padding(Theme.Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous))
    }
}

private struct FileTile: View {
    let name: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: "doc").font(.system(size: 16)).foregroundStyle(Theme.textMuted)
            Text(name).font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.text).lineLimit(2)
        }
        .padding(10)
        .frame(width: 120, height: 72, alignment: .topLeading)
        .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous).strokeBorder(Theme.border))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Attached file: \(name)")
    }
}

private struct Thumbnail: View {
    let data: Data

    var body: some View {
        Group {
            if let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Theme.sunken
            }
        }
        .frame(width: 72, height: 72)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous).strokeBorder(Theme.border))
        .accessibilityLabel("Attached photo")
    }
}

/// Pulls text, links, pictures and any other files out of whatever the other app shared.
private enum SharedContent {
    struct Loaded {
        var text = ""
        var attachments: [OutboxPhoto] = []
        var leftOut: [String] = []
    }

    static func load(from items: [NSExtensionItem]) async -> Loaded {
        var loaded = Loaded()
        var lines: [String] = []
        // A share sheet is killed past a little memory, and sending holds a file's bytes more than
        // once, so one share carries at most the size of one of the composer's biggest files.
        let carries = Int(Attachable.shared.maxBytes(mimeType: "application/octet-stream"))
        var room = carries
        func take(_ file: PickedFile) {
            if let refusal = file.refusal {
                loaded.leftOut.append(refusal)
            } else if file.data.count > room {
                loaded.leftOut.append("\(file.filename) was left out: one share carries up to \(carries / (1024 * 1024)) MB in all.")
            } else {
                room -= file.data.count
                loaded.attachments.append(OutboxPhoto(filename: file.filename, mimeType: file.mimeType, data: file.data))
            }
        }
        for item in items {
            if let text = item.attributedContentText?.string, !text.isEmpty { lines.append(text) }
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL,
                   !url.isFileURL {
                    lines.append(url.absoluteString)
                } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                          let image = await imageAttachment(from: provider, index: loaded.attachments.count) {
                    take(image)
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                          let text = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    lines.append(text)
                } else if let file = await fileAttachment(from: provider) {
                    // Anything else (a PDF, a video, a log, a zip) goes as the file it is.
                    take(file)
                }
            }
        }
        loaded.text = lines.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.joined(separator: "\n")
        return loaded
    }

    /// Photos arrive as files, raw data or a UIImage depending on the app; send them all as JPEG.
    private static func imageAttachment(from provider: NSItemProvider, index: Int) async -> PickedFile? {
        guard let item = try? await provider.loadItem(forTypeIdentifier: UTType.image.identifier) else { return nil }
        let image: UIImage?
        switch item {
        case let url as URL: image = (try? Data(contentsOf: url)).flatMap(UIImage.init(data:))
        case let data as Data: image = UIImage(data: data)
        case let picture as UIImage: image = picture
        default: image = nil
        }
        guard let data = image?.jpegData(compressionQuality: 0.85) else { return nil }
        let base = provider.suggestedName.map { ($0 as NSString).deletingPathExtension } ?? "shared-\(index + 1)"
        return PickedFile(filename: Attachable.shared.fileName(raw: "\(base).jpg", fallback: "shared-\(index + 1).jpg"), mimeType: "image/jpeg", data: data)
    }

    /// Any other file, read from the copy the other app hands over (it goes once the handler returns).
    private static func fileAttachment(from provider: NSItemProvider) async -> PickedFile? {
        // The richest type it offers that is a file's contents, not a link to one.
        let type = provider.registeredTypeIdentifiers.first { id in
            guard let type = UTType(id) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .url)
        }
        if let type {
            let file: PickedFile? = await withCheckedContinuation { continuation in
                _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                    continuation.resume(returning: url.flatMap {
                        PickedFile.read($0, name: PickedFile.name(suggested: provider.suggestedName, file: $0), typeIdentifier: type)
                    })
                }
            }
            if let file { return file }
        }
        // Some apps hand over only where the file is.
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
           let url = try? await provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier) as? URL {
            return PickedFile.read(url)
        }
        return nil
    }
}
