import Shared
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share to Wildware Feed" from any app: pick a project, add a note, and it lands in that project's feed.
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
    @State private var status: Status = .loading

    enum Status: Equatable { case loading, ready, sending, queued, failed(String) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    if case let .failed(message) = status {
                        Banner(text: message, symbol: "exclamationmark.triangle.fill", color: Theme.error)
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
                            Text(attachments.count == 1 ? "1 photo" : "\(attachments.count) photos").overline().foregroundStyle(Theme.textMuted)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: Theme.Space.s) {
                                    ForEach(attachments, id: \.filename) { photo in
                                        Thumbnail(data: photo.data)
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
        selected != nil && status != .sending && status != .loading && status != .queued
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
        status = projects.isEmpty ? .failed("Couldn't reach the dashboard to list your projects. Try again with signal.") : .ready
    }

    private func post() {
        guard let selected, let project = projects.first(where: { $0.slug == selected }) else { return }
        status = .sending
        Task {
            let queued = OutboxQueue.enqueueMessage(
                label: "To \(project.name)", thread: ["project": project.slug], text: note, photos: attachments
            )
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

/// Pulls text, links and images out of whatever the other app shared.
private enum SharedContent {
    struct Loaded {
        var text = ""
        var attachments: [OutboxPhoto] = []
    }

    static func load(from items: [NSExtensionItem]) async -> Loaded {
        var loaded = Loaded()
        var lines: [String] = []
        for item in items {
            if let text = item.attributedContentText?.string, !text.isEmpty { lines.append(text) }
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
                   let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL,
                   !url.isFileURL {
                    lines.append(url.absoluteString)
                } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
                          let image = await imageAttachment(from: provider, index: loaded.attachments.count) {
                    loaded.attachments.append(image)
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                          let text = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                    lines.append(text)
                }
            }
        }
        loaded.text = lines.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.joined(separator: "\n")
        return loaded
    }

    /// Photos arrive as files, raw data or a UIImage depending on the app; send them all as JPEG.
    private static func imageAttachment(from provider: NSItemProvider, index: Int) async -> OutboxPhoto? {
        guard let item = try? await provider.loadItem(forTypeIdentifier: UTType.image.identifier) else { return nil }
        let image: UIImage?
        switch item {
        case let url as URL: image = (try? Data(contentsOf: url)).flatMap(UIImage.init(data:))
        case let data as Data: image = UIImage(data: data)
        case let picture as UIImage: image = picture
        default: image = nil
        }
        guard let data = image?.jpegData(compressionQuality: 0.85) else { return nil }
        return OutboxPhoto(filename: "shared-\(index + 1).jpg", mimeType: "image/jpeg", data: data)
    }
}
