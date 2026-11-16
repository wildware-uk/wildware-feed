import Shared
import UserNotifications

/// Runs on the phone before an alert is shown, to give each question its own answer buttons.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.content = content

        let info = request.content.userInfo
        guard let kind = info["requestKind"] as? String else { return contentHandler(content) }

        Task {
            if let category = await Notifications.category(forRequestKind: kind, options: info["options"] as? [String]) {
                content.categoryIdentifier = category
            }
            content.relevanceScore = 1
            contentHandler(content)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler, let content { contentHandler(content) }
    }
}
