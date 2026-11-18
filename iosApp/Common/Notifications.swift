#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UserNotifications

/// Notification buttons. Message and update alerts share fixed categories; a request's category
/// depends on its kind and button count, and the expanded alert swaps in that question's own titles.
enum Notifications {
    static let messageCategory = "MESSAGE"
    static let updateCategory = "UPDATE"
    static let requestCategory = "REQUEST"
    static let replyAction = "reply"

    static func registerCategories() {
        let requests = RequestActions.shared.categoryIds.map { id in
            UNNotificationCategory(
                identifier: id,
                actions: RequestActions.shared.categoryActions(categoryId: id).map(notificationAction),
                intentIdentifiers: [],
                options: []
            )
        }
        let base: Set<UNNotificationCategory> = [
            replyCategory(id: messageCategory),
            replyCategory(id: updateCategory),
            UNNotificationCategory(identifier: requestCategory, actions: [], intentIdentifiers: []),
        ]
        UNUserNotificationCenter.current().setNotificationCategories(base.union(requests))
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
