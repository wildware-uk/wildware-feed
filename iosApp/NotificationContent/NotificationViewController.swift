import SwiftUI
import UIKit
import UserNotifications
import UserNotificationsUI

/// The long-press view: the alert as the app's own card. It shows what the push carried at once,
/// then fills in from the dashboard (time left, the agent, option pictures, the message replied to).
final class NotificationViewController: UIViewController, UNNotificationContentExtension {
    private static let maxHeight: CGFloat = 520
    private var host: UIHostingController<AlertCard>?
    private var loading: Task<Void, Never>?

    func didReceive(_ notification: UNNotification) {
        let content = notification.request.content
        let info = content.userInfo

        // Shared categories number their option buttons; here there is room for this question's own titles.
        if let kind = info["requestKind"] as? String {
            let actions = Notifications.actions(forRequestKind: kind, options: info["options"] as? [String])
            if !actions.isEmpty { extensionContext?.notificationActions = actions }
        }

        let model = AlertModel(payload: AlertPayload(content, date: notification.date), pictures: Self.pictures(in: content))
        model.openApp = { [weak self] in self?.extensionContext?.performNotificationDefaultAction() }
        model.openLink = { [weak self] url in self?.openLink(url) }
        model.close = { [weak self] in
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [notification.request.identifier])
            self?.extensionContext?.dismissNotificationContentExtension()
        }
        model.resized = { [weak self] in
            DispatchQueue.main.async { self?.fitToContent() }
        }

        let card = AlertCard(model: model)
        if let host {
            host.rootView = card
        } else {
            embed(card)
        }
        fitToContent()

        loading?.cancel()
        loading = Task { @MainActor [weak self] in
            let details = await AlertLoader.details(for: model.payload)
            guard !Task.isCancelled else { return }
            withAnimation(Theme.motion(reduced: UIAccessibility.isReduceMotionEnabled)) { model.details = details }
            self?.fitToContent()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        fitToContent()
    }

    /// Measured at the alert's real width: an unbounded width lays the text out as one long line,
    /// which reports a height of a line or two and clips everything below.
    private func fitToContent() {
        guard let host, view.bounds.width > 0 else { return }
        let fitted = host.sizeThatFits(in: CGSize(width: view.bounds.width, height: CGFloat.greatestFiniteMagnitude))
        let size = CGSize(width: view.bounds.width, height: min(fitted.height, Self.maxHeight))
        if size != preferredContentSize { preferredContentSize = size }
    }

    private func embed(_ card: AlertCard) {
        let host = UIHostingController(rootView: card)
        host.view.backgroundColor = .clear
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addChild(host)
        view.addSubview(host.view)
        host.didMove(toParent: self)
        self.host = host
    }

    /// The pictures the service extension attached, by identifier. Attachments live outside this
    /// extension's sandbox, so each one is opened under its security scope.
    private static func pictures(in content: UNNotificationContent) -> [String: UIImage] {
        var pictures: [String: UIImage] = [:]
        for attachment in content.attachments {
            let url = attachment.url
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                pictures[attachment.identifier] = image
            }
        }
        return pictures
    }

    /// `extensionContext.open` does nothing in an alert, so the app opens the link once it is up.
    private func openLink(_ url: URL) {
        PendingLink.leave(url)
        extensionContext?.performNotificationDefaultAction()
    }
}
