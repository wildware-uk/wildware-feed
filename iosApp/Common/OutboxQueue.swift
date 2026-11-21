import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UserNotifications

enum OutboxError: LocalizedError {
    case refused

    var errorDescription: String? {
        switch self {
        case .refused: "The dashboard refused it. Open Wildware Feed to see why."
        }
    }
}

/// How one queued item's send went, for whoever queued it.
enum OutboxSend {
    case sent
    /// No signal or the server struggling: it waits in the outbox and goes later.
    case waiting
    /// The dashboard said no for good. The owner has been told; it stays listed.
    case refused
}

/// A photo or file to queue with a message, before it is written into the outbox's folder.
struct OutboxPhoto {
    let filename: String
    let mimeType: String
    let data: Data
}

/// The outbox on disk: one JSON list plus a folder of attachments, in the app group so the app, the share
/// sheet, the widgets and Siri all queue into the same place. The rules for what goes next live in Kotlin
/// (`Outbox`); this file only stores, sends and announces.
///
/// The app and its extensions are separate processes, so every change is a read-change-write done inside
/// a file coordinator, written atomically, and items being sent are claimed for a while so no two
/// processes send the same thing.
enum OutboxQueue {
    /// Other processes post this after changing the queue, so the app can redraw and send.
    static let changedNotification = "dev.wildware.agents.outbox.changed"

    static var folder: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: CredentialStore.appGroup)
            ?? URL.applicationSupportDirectory
        let folder = base.appending(path: "Outbox", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private static var queueFile: URL { folder.appending(path: "queue.json") }

    // MARK: Queueing

    /// Queues a post or reply. [thread] is `{"project": slug}`, `{"request": id}`, or an alert's reply thread.
    /// [voice]: the words were said aloud and transcribed, so the dashboard marks it as a voice note.
    @discardableResult
    static func enqueueMessage(label: String, thread: [String: String], text: String?, photos: [OutboxPhoto] = [], voice: Bool = false) -> String {
        let id = UUID().uuidString
        let files = photos.enumerated().compactMap { index, photo -> OutboxFile? in
            let name = "\(id)-\(index)"
            do {
                try photo.data.write(to: folder.appending(path: name), options: .atomic)
            } catch {
                return nil
            }
            return OutboxFile(name: name, filename: photo.filename, mimeType: photo.mimeType)
        }
        let item = Outbox.shared.message(id: id, nowMillis: nowMillis, label: label, thread: thread, text: text, files: files)
        add(voice ? Outbox.shared.spoken(item: item) : item)
        return id
    }

    @discardableResult
    static func enqueueTicket(project: ProjectRef, text: String) -> String {
        let id = UUID().uuidString
        add(Outbox.shared.ticket(id: id, nowMillis: nowMillis, project: project, text: text))
        return id
    }

    @discardableResult
    static func enqueueAnswer(agentName: String, requestId: String, kind: String, options: [String]?, actionId: String, typedText: String?) -> String {
        let id = UUID().uuidString
        let answer = OutboxAnswer(requestId: requestId, kind: kind, options: options, actionId: actionId, typedText: typedText)
        add(Outbox.shared.answer(id: id, nowMillis: nowMillis, agentName: agentName, answer: answer))
        return id
    }

    private static func add(_ item: OutboxItem) {
        update { $0 + [item] }
    }

    // MARK: Reading and changing

    static func items() -> [OutboxItem] {
        var items: [OutboxItem] = []
        var failure: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: queueFile, options: [], error: &failure) { url in
            items = load(url)
        }
        if failure != nil { items = load(queueFile) }
        return items
    }

    static func retryNow(_ id: String) { update { Outbox.shared.retryNow(items: $0, id: id) } }

    static func delete(_ id: String) { update { Outbox.shared.remove(items: $0, id: id) } }

    /// Reads, changes and writes the queue while holding it, then deletes files nothing uses any more.
    @discardableResult
    static func update(_ change: @escaping ([OutboxItem]) -> [OutboxItem]) -> [OutboxItem] {
        var result: [OutboxItem] = []
        var wrote = false
        let write = { (url: URL) in
            let before = load(url)
            let after = change(before)
            // Nothing changed: write nothing, so no process wakes for it.
            let old = try? Data(contentsOf: url)
            if let data = Outbox.shared.encode(items: after).data(using: .utf8), data != old {
                try? data.write(to: url, options: .atomic)
                wrote = true
            }
            for name in Outbox.shared.orphanedFiles(before: before, after: after) {
                try? FileManager.default.removeItem(at: folder.appending(path: name))
            }
            result = after
        }
        var failure: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: queueFile, options: .forMerging, error: &failure, byAccessor: write)
        if failure != nil { write(queueFile) }
        if wrote { announce() }
        return result
    }

    private static func load(_ url: URL) -> [OutboxItem] {
        guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else { return [] }
        return Outbox.shared.decode(text: text)
    }

    private static func announce() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(changedNotification as CFString), nil, nil, true
        )
    }

    static var nowMillis: Int64 { Int64(Date.now.timeIntervalSince1970 * 1000) }

    // MARK: Sending

    /// Sends what is due, oldest first, each item at most once per call. With [only], sends just that item:
    /// and anything older in its thread first: the share sheet and Siri send their own and leave the rest to the app.
    /// Returns how [only] went, or nil when it wasn't tried (another process has it, or something older failed).
    @discardableResult
    static func flush(ignoreBackoff: Bool = false, only: String? = nil) async -> OutboxSend? {
        guard let client = Dashboard.client() else { return nil }
        defer { client.close() }
        var tried: [String] = []
        var outcomeOfOnly: OutboxSend?
        while !Task.isCancelled {
            // Pick and claim in one held write, so no other process picks the same item meanwhile.
            var picked: OutboxItem?
            update { items in
                let now = nowMillis
                // Sending one item means sending what is older in its own thread first, so order holds.
                let thread = only.flatMap { id in items.first { $0.id == id }?.threadKey }
                let candidates = thread.map { key in items.filter { $0.threadKey == key } } ?? (only == nil ? items : [])
                guard let next = Outbox.shared.next(
                    items: candidates, nowMillis: now, ignoreBackoff: ignoreBackoff || only != nil, skip: tried
                ) else { return items }
                picked = next
                return Outbox.shared.claim(items: items, id: next.id, nowMillis: now)
            }
            guard let item = picked else { break }
            tried.append(item.id)

            let attachments = item.files.compactMap { file -> Attachment? in
                guard let data = try? Data(contentsOf: folder.appending(path: file.name)) else { return nil }
                return IosDashboardKt.attachment(filename: file.filename, mimeType: file.mimeType, data: data)
            }
            let result: DeliveryResult
            do {
                result = try await client.deliver(item: item, attachments: attachments)
            } catch {
                // Cancelled: release the claim so the next pass can send it.
                update { Outbox.shared.afterAttempt(items: $0, id: item.id, result: Outbox.shared.interrupted(), nowMillis: nowMillis) }
                break
            }
            update { Outbox.shared.afterAttempt(items: $0, id: item.id, result: result, nowMillis: nowMillis) }
            if result.isRefused { await tellGaveUp(item, reason: result.message) }
            if item.id == only {
                outcomeOfOnly = result.isSent ? .sent : result.isRefused ? .refused : .waiting
                break
            }
        }
        return outcomeOfOnly
    }

    /// Something the owner sent was refused for good. They need to hear it, wherever they are.
    private static func tellGaveUp(_ item: OutboxItem, reason: String?) async {
        let content = UNMutableNotificationContent()
        content.title = "Couldn't send: \(item.label)"
        content.body = [reason, "Open Wildware Feed to try again or delete it."].compactMap { $0 }.joined(separator: " ")
        let request = UNNotificationRequest(identifier: "outbox-\(item.id)", content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
