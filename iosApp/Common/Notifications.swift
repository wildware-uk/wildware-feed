import Shared
import UserNotifications

/// Notification buttons. Message and update alerts share fixed categories; each request
/// gets a category built from its own options when its notification arrives.
enum Notifications {
    static let messageCategory = "MESSAGE"
    static let updateCategory = "UPDATE"
    static let requestCategory = "REQUEST"
    static let replyAction = "reply"

    /// Old per-request categories are pruned past this, so the registered set stays small.
    private static let maxRequestCategories = 30

    static func registerBaseCategories() async {
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        let base: Set<UNNotificationCategory> = [
            replyCategory(id: messageCategory),
            replyCategory(id: updateCategory),
            UNNotificationCategory(identifier: requestCategory, actions: [], intentIdentifiers: []),
        ]
        let kept = existing.filter { $0.identifier.hasPrefix("\(requestCategory).") }
        center.setNotificationCategories(base.union(kept))
    }

    /// Registers the buttons for one request and returns the category id to put on its notification.
    static func category(forRequestKind kindName: String, options: [String]?) async -> String? {
        guard let kind = RequestKind.companion.fromWire(value: kindName) else { return nil }
        let actions = RequestActions.shared.actionsFor(kind: kind, options: options)
        guard !actions.isEmpty else { return nil }

        let id = RequestActions.shared.categoryId(kind: kind, options: options)
        let center = UNUserNotificationCenter.current()
        var categories = await center.notificationCategories()
        if categories.contains(where: { $0.identifier == id }) { return id }

        let category = UNNotificationCategory(
            identifier: id,
            actions: actions.map(notificationAction),
            intentIdentifiers: [],
            options: []
        )
        let requestCategories = categories.filter { $0.identifier.hasPrefix("\(requestCategory).") }
        if requestCategories.count >= maxRequestCategories {
            categories.subtract(requestCategories)
        }
        categories.insert(category)
        center.setNotificationCategories(categories)
        return id
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
