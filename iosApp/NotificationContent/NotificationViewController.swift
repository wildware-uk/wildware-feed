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
            openApp: { [weak self] in self?.extensionContext?.performNotificationDefaultAction() },
            resize: { [weak self] height in
                guard let self else { return }
                preferredContentSize = CGSize(width: view.bounds.width, height: min(height, Self.maxHeight))
            }
        )
        if let host {
            host.rootView = alert
        } else {
            embed(alert)
        }
    }

    private func embed(_ alert: AlertView) {
        let host = UIHostingController(rootView: alert)
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        addChild(host)
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
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
    let resize: (CGFloat) -> Void

    var body: some View {
        ScrollView {
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
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { resize($0) }
        }
        .scrollBounceBehavior(.basedOnSize)
        .contentShape(Rectangle())
        .onTapGesture(perform: openApp)
        .environment(\.openURL, OpenURLAction { url in
            openLink(url)
            return .handled
        })
    }
}
