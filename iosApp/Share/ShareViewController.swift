import Shared
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share to Agents" from any app: pick a project, add a note, and it lands in that project's feed.
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
    @State private var attachments: [Attachment] = []
    @State private var status: Status = .loading

    enum Status: Equatable { case loading, ready, sending, failed(String) }

    var body: some View {
        NavigationStack {
            Form {
                if case let .failed(message) = status {
                    Text(message).foregroundStyle(.red)
                }
                Picker("Project", selection: $selected) {
                    ForEach(projects, id: \.slug) { Text($0.name).tag(Optional($0.slug)) }
                }
                Section("Note") {
                    TextField("What should the agent do with this?", text: $note, axis: .vertical)
                        .lineLimit(3...8)
                }
                if !attachments.isEmpty {
                    Section("Attached") {
                        ForEach(attachments, id: \.filename) { Text($0.filename) }
                    }
                }
            }
            .navigationTitle("Share to Agents")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: cancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(status == .sending ? "Sending…" : "Post", action: post)
                        .disabled(selected == nil || status == .sending || status == .loading)
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        guard let client = Dashboard.client() else {
            status = .failed(DashboardError.notLinked.localizedDescription)
            return
        }
        defer { client.close() }
        async let content = SharedContent.load(from: items)
        do {
            projects = try await client.summary().projects
            selected = projects.first?.slug
            let loaded = await content
            note = loaded.text
            attachments = loaded.attachments
            status = .ready
        } catch {
            status = .failed("Couldn't reach the dashboard: \(error.localizedDescription)")
        }
    }

    private func post() {
        guard let selected, let client = Dashboard.client() else { return }
        status = .sending
        Task {
            defer { client.close() }
            do {
                _ = try await client.share(projectSlug: selected, text: note, attachments: attachments)
                finish()
            } catch {
                status = .failed("Didn't send: \(error.localizedDescription)")
            }
        }
    }
}

/// Pulls text, links and images out of whatever the other app shared.
private enum SharedContent {
    struct Loaded {
        var text = ""
        var attachments: [Attachment] = []
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
    private static func imageAttachment(from provider: NSItemProvider, index: Int) async -> Attachment? {
        guard let item = try? await provider.loadItem(forTypeIdentifier: UTType.image.identifier) else { return nil }
        let image: UIImage?
        switch item {
        case let url as URL: image = (try? Data(contentsOf: url)).flatMap(UIImage.init(data:))
        case let data as Data: image = UIImage(data: data)
        case let picture as UIImage: image = picture
        default: image = nil
        }
        guard let data = image?.jpegData(compressionQuality: 0.85) else { return nil }
        return IosDashboardKt.attachment(filename: "shared-\(index + 1).jpg", mimeType: "image/jpeg", data: data)
    }
}
