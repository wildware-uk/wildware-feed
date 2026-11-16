import Shared
import UserNotifications

/// Notification buttons. Message and update alerts share fixed categories; a request's category
/// depends on its kind and button count, and takes its button titles when its alert arrives.
enum Notifications {
    static let messageCategory = "MESSAGE"
    static let updateCategory = "UPDATE"
    static let requestCategory = "REQUEST"
    static let replyAction = "reply"

    static func registerBaseCategories() async {
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        let base: Set<UNNotificationCategory> = [
            replyCategory(id: messageCategory),
            replyCategory(id: updateCategory),
            UNNotificationCategory(identifier: requestCategory, actions: [], intentIdentifiers: []),
        ]
        let kept = existing.filter { RequestActions.shared.categoryIds.contains($0.identifier) }
        center.setNotificationCategories(base.union(kept))
    }

    /// Registers the buttons for one request and returns the category id to put on its notification.
    /// The newest question's titles replace the last ones in its category; the expanded alert
    /// shows each question's own titles (see `actions`).
    static func category(forRequestKind kindName: String, options: [String]?) async -> String? {
        guard let kind = RequestKind.companion.fromWire(value: kindName),
              let id = RequestActions.shared.categoryId(kind: kind, options: options)
        else { return nil }

        let center = UNUserNotificationCenter.current()
        var categories = await center.notificationCategories().filter { $0.identifier != id }
        categories.insert(UNNotificationCategory(
            identifier: id,
            actions: actions(forRequestKind: kindName, options: options),
            intentIdentifiers: [],
            options: []
        ))
        center.setNotificationCategories(categories)
        return id
    }

    static func actions(forRequestKind kindName: String, options: [String]?) -> [UNNotificationAction] {
        guard let kind = RequestKind.companion.fromWire(value: kindName) else { return [] }
        return RequestActions.shared.actionsFor(kind: kind, options: options).map(notificationAction)
    }

    private static func notificationAction(_ action: NotificationAction) -> UNNotificationAction {
        // Answering asks for Face ID or the passcode first, so nobody can approve from a locked phone they picked up.
        var options: UNNotificationActionOptions = [.authenticationRequired]
        if action.isDestructive { options.insert(.destructive) }
        if action.isTextInput {
            return UNTextInputNotificationAction(
                identifier: action.id,
                title: action.title,
                options: options,
                textInputButtonTitle: "Send",
                textInputPlaceholder: "Your answer"
            )
        }
        return UNNotificationAction(identifier: action.id, title: action.title, options: options)
    }

    private static func replyCategory(id: String) -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: id,
            actions: [
                UNTextInputNotificationAction(
                    identifier: replyAction,
                    title: "Reply",
                    options: [.authenticationRequired],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Reply to the agent"
                ),
            ],
            intentIdentifiers: [],
            options: []
        )
    }
}
