import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UserNotifications

/// What an alert's push carries, read once. See server/spec/08-jobs-push-presence.md §6.7.
struct AlertPayload {
    enum Kind { case request, message, update }

    let kind: Kind
    let title: String
    let body: String
    let markdown: String?
    /// The project's slug, or nil for alerts outside any project.
    let projectSlug: String?
    let requestId: String?
    let requestKind: String?
    let options: [String]?
    /// Where a reply goes: `["update": id]` or `["answers": messageId]`.
    let reply: [String: String]?
    let date: Date

    init(_ content: UNNotificationContent, date: Date = .now) {
        let info = content.userInfo
        requestId = info["requestId"] as? String
        requestKind = info["requestKind"] as? String
        options = info["options"] as? [String]
        reply = info["reply"] as? [String: String]
        markdown = info["markdown"] as? String
        title = content.title
        body = content.body
        projectSlug = content.threadIdentifier == "dashboard" || content.threadIdentifier.isEmpty ? nil : content.threadIdentifier
        self.date = date
        if requestId != nil {
            kind = .request
        } else if reply?["answers"] != nil || content.categoryIdentifier == Notifications.messageCategory {
            kind = .message
        } else {
            kind = .update
        }
    }

    /// A payload made by hand: previews, screenshots and tests.
    init(kind: Kind, title: String, body: String, markdown: String? = nil, projectSlug: String? = nil,
         requestId: String? = nil, requestKind: String? = nil, options: [String]? = nil,
         reply: [String: String]? = nil, date: Date = .now) {
        self.kind = kind
        self.title = title
        self.body = body
        self.markdown = markdown
        self.projectSlug = projectSlug
        self.requestId = requestId
        self.requestKind = requestKind
        self.options = options
        self.reply = reply
        self.date = date
    }

    /// The agent's name from the alert's own title: "Rover is waiting on you", "Rover replied to you",
    /// "Rover commented". Update titles are the update's own, so they name nobody.
    var agentNameFromTitle: String? {
        for suffix in [" is waiting on you", " replied to you", " commented", " posted an update"] where title.hasSuffix(suffix) {
            return String(title.dropLast(suffix.count))
        }
        return nil
    }

    /// The card a reaction lands on.
    var card: CardRef? {
        if let id = reply?["answers"] { return CardRef(kind: "message", id: id) }
        if let id = reply?["update"] { return CardRef(kind: "update", id: id) }
        return nil
    }
}

/// What the dashboard says about an alert right now, beyond what fits in the push.
struct AlertDetails {
    var agentName: String?
    var agentId: String?
    var projectName: String?

    /// The question, while it is still waiting. Nil once it has been answered or has timed out.
    var request: PendingRequest?
    /// Picture questions: each option's picture.
    var optionMedia: [String: MediaView] = [:]
    /// How many questions are waiting, this one included.
    var waiting = 0

    /// For a reply: the owner's message it answers.
    var quote: Quote?
    /// Pictures on the message or update.
    var media: [MediaView] = []
    /// When the push said a question, whether the dashboard still has it waiting.
    var requestIsGone = false

    struct Quote {
        let text: String
        let date: Date
    }
}

enum AlertLoader {
    /// Reads what the dashboard has on this alert. Every part is optional: whatever fails is left out.
    static func details(for payload: AlertPayload) async -> AlertDetails {
        var details = AlertDetails()
        details.agentName = payload.agentNameFromTitle
        guard let client = Dashboard.client() else { return details }
        defer { client.close() }

        switch payload.kind {
        case .request:
            guard let id = payload.requestId else { return details }
            async let summary = try? client.summary()
            async let waiting = try? client.waitingRequests()
            let (s, w) = await (summary, waiting)
            if let s {
                details.waiting = s.requests.count
                if let request = s.requests.first(where: { $0.id == id }) {
                    details.request = request
                    details.agentName = request.agentName
                    details.projectName = request.projectName
                } else {
                    details.requestIsGone = true
                }
            }
            if let view = w?.requests.first(where: { $0.id == id }) {
                details.agentId = view.agentId
                for option in payload.options ?? details.request?.options ?? [] {
                    if let picture = view.pictureFor(option: option), picture.isReady { details.optionMedia[option] = picture }
                }
                details.media = view.cardMedia.filter { $0.isReady && $0.kind == "image" }
            }

        case .message:
            guard let id = payload.reply?["answers"],
                  let snapshot = try? await client.messages(projectSlug: payload.projectSlug, updateId: nil, requestId: nil, replyTo: nil, taskId: nil),
                  let message = snapshot.messages.first(where: { $0.id == id })
            else { return details }
            details.agentId = message.agentId
            details.media = (snapshot.media[id] ?? []).filter { $0.isReady && $0.kind == "image" }
            if let answered = message.answers, let quoted = snapshot.messages.first(where: { $0.id == answered }), quoted.isOwner {
                // The card says "You" over the quote. On a team a teammate's line is theirs, so it is
                // quoted only when it is this person's ("founder" on a server from before teams).
                var mine = false
                do {
                    let me = try await client.me()
                    mine = quoted.personId == (me?.person.id ?? "founder")
                } catch {
                    mine = false
                }
                if mine {
                    details.quote = .init(text: quoted.body, date: Date(timeIntervalSince1970: Double(quoted.createdAt) / 1000))
                }
            }

        case .update:
            guard let id = payload.reply?["update"],
                  let snapshot = try? await client.snapshot(projectSlug: payload.projectSlug, limit: 20),
                  let update = snapshot.updates.items.first(where: { $0.id == id })
            else { return details }
            details.agentId = update.agentId
            details.agentName = snapshot.agentNames?[update.agentId] ?? details.agentName
            details.media = update.media.filter { $0.isReady && $0.kind == "image" }
        }
        return details
    }

    /// Fetches one picture to a temporary file, named so iOS knows its type. Nil when it can't.
    static func download(_ media: MediaView, variant: String = "thumb-640") async -> URL? {
        guard let credentials = CredentialStore.load() else { return nil }
        let chosen = media.variants.contains(variant) ? variant : "original"
        var request = URLRequest(url: credentials.server.appendingPathComponent(media.path(variant: chosen)))
        request.setValue("Bearer \(credentials.token)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 10
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200
        else { return nil }
        let ext = media.mime.contains("png") ? "png" : media.mime.contains("gif") ? "gif" : "jpg"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(media.id)-\(chosen).\(ext)")
        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }
}
