import SwiftUI
import UIKit
import UserNotifications
import UserNotificationsUI

/// The expanded alert: the agent's own markdown, styled, in place of the plain banner text.
final class NotificationViewController: UIViewController, UNNotificationContentExtension {
    private static let maxHeight: CGFloat = 500
    private var host: UIHostingController<AlertView>?

    func didReceive(_ notification: UNNotification) {
        let content = notification.request.content
        let info = content.userInfo

        // Shared categories number their option buttons; here there is room for this question's own titles.
        if let kind = info["requestKind"] as? String {
            let actions = Notifications.actions(forRequestKind: kind, options: info["options"] as? [String])
            if !actions.isEmpty { extensionContext?.notificationActions = actions }
        }

        let alert = AlertView(
            title: content.title,
            markdown: info["markdown"] as? String,
            plainText: content.body,
            openLink: { [weak self] url in self?.openLink(url) },
            openApp: { [weak self] in self?.extensionContext?.performNotificationDefaultAction() }
        )
        if let host {
            host.rootView = alert
        } else {
            embed(alert)
        }
        fitToContent()
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

    private func embed(_ alert: AlertView) {
        let host = UIHostingController(rootView: alert)
        host.view.backgroundColor = .clear
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addChild(host)
        view.addSubview(host.view)
        host.didMove(toParent: self)
        self.host = host
    }

    /// Agents often link dashboard pages by path alone, which only the app can open.
    private func openLink(_ url: URL) {
        let target = url.scheme == nil ? DeepLink.open(path: url.absoluteString) : url
        extensionContext?.open(target, completionHandler: nil)
    }
}

struct AlertView: View {
    let title: String
    let markdown: String?
    let plainText: String
    let openLink: (URL) -> Void
    let openApp: () -> Void

    var body: some View {
        // No ScrollView: its ideal height is not its content's, so the alert would size to nothing.
        // Anything past the height cap is cut off; a tap opens the full message in the app.
        VStack(alignment: .leading, spacing: 8) {
            if !title.isEmpty {
                Text(title).font(.headline)
            }
            if let markdown {
                MarkdownView(blocks: MarkdownBlock.parse(markdown))
            } else {
                Text(plainText)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .contentShape(Rectangle())
        .onTapGesture(perform: openApp)
        .environment(\.openURL, OpenURLAction { url in
            openLink(url)
            return .handled
        })
    }
}
