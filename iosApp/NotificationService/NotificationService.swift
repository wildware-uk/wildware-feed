import Shared
import UserNotifications

/// Runs on the phone before an alert is shown, to give each question its answer buttons.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        let info = request.content.userInfo
        guard let kindName = info["requestKind"] as? String,
              let kind = RequestKind.companion.fromWire(value: kindName)
        else { return contentHandler(content) }

        let options = info["options"] as? [String]
        if let category = RequestActions.shared.categoryId(kind: kind, options: options) {
            content.categoryIdentifier = category
        }
        // Where the buttons only say "Option 1", as on the Watch, the text says what each one is.
        if let legend = RequestActions.shared.optionsLegend(kind: kind, options: options) {
            content.body += "\n" + legend
        }
        content.relevanceScore = 1
        contentHandler(content)
    }
}
