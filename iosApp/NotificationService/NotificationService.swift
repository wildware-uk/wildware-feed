import Intents
import Shared
import SwiftUI
import UIKit
import UserNotifications

/// Runs on the phone before an alert is shown. It gives each question its answer buttons, fetches the
/// pictures the long-press view shows, and makes the alert read as a message from the agent: its
/// coloured initial on the banner instead of the app icon.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    /// The alert as far as it has got, shown as is if iOS runs out of patience.
    private var bestAttempt: UNMutableNotificationContent?
    private var work: Task<Void, Never>?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        bestAttempt = content
        setButtons(on: content)

        work = Task {
            let finished = await enrich(content)
            deliver(finished)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        work?.cancel()
        if let bestAttempt { deliver(bestAttempt) }
    }

    private func deliver(_ content: UNNotificationContent) {
        guard let handler = contentHandler else { return }
        contentHandler = nil
        handler(content)
    }

    /// A question's buttons depend on its kind and how many options it has.
    private func setButtons(on content: UNMutableNotificationContent) {
        let info = content.userInfo
        guard let kindName = info["requestKind"] as? String,
              let kind = RequestKind.companion.fromWire(value: kindName)
        else { return }
        let options = info["options"] as? [String]
        if let category = RequestActions.shared.categoryId(kind: kind, options: options) {
            content.categoryIdentifier = category
        }
        // Where the buttons only say "Option 1", as on the Watch, the text says what each one is.
        if let legend = RequestActions.shared.optionsLegend(kind: kind, options: options) {
            content.body += "\n" + legend
        }
        content.relevanceScore = 1
    }

    private func enrich(_ content: UNMutableNotificationContent) async -> UNNotificationContent {
        let payload = AlertPayload(content)
        let details = await AlertLoader.details(for: payload)
        guard !Task.isCancelled else { return content }

        // Option pictures first, named by option so the long-press view can find them; then the card's own.
        var attachments: [UNNotificationAttachment] = []
        for (index, option) in (payload.options ?? []).enumerated() {
            guard let media = details.optionMedia[option], let file = await AlertLoader.download(media) else { continue }
            if let attachment = try? UNNotificationAttachment(identifier: "option-\(index)", url: file, options: hidden) {
                attachments.append(attachment)
            }
        }
        if let first = details.media.first, let file = await AlertLoader.download(first) {
            // The banner shows the first attachment as a thumbnail, so the card's picture goes in front.
            if let attachment = try? UNNotificationAttachment(identifier: "media", url: file, options: nil) {
                attachments.insert(attachment, at: 0)
            }
        }
        content.attachments = attachments
        bestAttempt = content
        guard !Task.isCancelled else { return content }

        return await asMessage(content, payload: payload, details: details)
    }

    /// Option pictures travel with the alert for the long-press view, but don't take the banner's thumbnail.
    private var hidden: [AnyHashable: Any] { [UNNotificationAttachmentOptionsThumbnailHiddenKey: true] }

    /// Recasts the alert as a message from the agent (a communication notification). Without the
    /// Communication Notifications capability iOS hands the content back unchanged, which is the
    /// ordinary banner with the app's robot.
    private func asMessage(_ content: UNMutableNotificationContent, payload: AlertPayload, details: AlertDetails) async -> UNNotificationContent {
        guard let name = details.agentName else { return content }
        let key = details.agentId ?? name
        let avatar = Avatar.png(name: name, key: key)
        let sender = INPerson(
            personHandle: INPersonHandle(value: key, type: .unknown),
            nameComponents: nil,
            displayName: name,
            image: avatar.map { INImage(imageData: $0) },
            contactIdentifier: nil,
            customIdentifier: key
        )
        let intent = INSendMessageIntent(
            recipients: nil,
            outgoingMessageType: .outgoingMessageText,
            content: content.body,
            speakableGroupName: nil,
            conversationIdentifier: payload.projectSlug ?? "dashboard",
            serviceName: nil,
            sender: sender,
            attachments: nil
        )
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        try? await interaction.donate()
        guard let updated = try? content.updating(from: intent) else { return content }
        return updated
    }
}

/// The agent's picture on a banner: its initial on its own colour, as on the app's cards.
enum Avatar {
    static func png(name: String, key: String) -> Data? {
        let size = CGSize(width: 120, height: 120)
        let colour = UIColor(Theme.agents[Theme.hue(key)]).resolvedColor(with: UITraitCollection(userInterfaceStyle: .dark))
        let initial = String(name.prefix(1)).uppercased() as NSString
        let font = UIFont(name: "GeistMono-Medium", size: 54) ?? .monospacedSystemFont(ofSize: 54, weight: .medium)
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(red: 0.04, green: 0.04, blue: 0.05, alpha: 1)]
        let image = UIGraphicsImageRenderer(size: size).image { _ in
            colour.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).fill()
            let text = initial.size(withAttributes: attributes)
            initial.draw(at: CGPoint(x: (size.width - text.width) / 2, y: (size.height - text.height) / 2), withAttributes: attributes)
        }
        return image.pngData()
    }
}
