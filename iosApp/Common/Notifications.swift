#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import Intents
import UserNotifications

/// Notification buttons. Message and update alerts share fixed categories; a request's category
/// depends on its kind and button count, and the expanded alert swaps in that question's own titles.
enum Notifications {
    static let messageCategory = "MESSAGE"
    static let updateCategory = "UPDATE"
    static let requestCategory = "REQUEST"
    static let replyAction = "reply"
    /// Puts a thumbs up on the reply or update, as a tap on the card's reactions does.
    static let reactAction = "react-thumbsup"
    static let reactEmoji = "👍"

    static func registerCategories() {
        let requests = RequestActions.shared.categoryIds.map { id in
            UNNotificationCategory(
                identifier: id,
                actions: RequestActions.shared.categoryActions(categoryId: id).map(notificationAction),
                intentIdentifiers: [INSendMessageIntentIdentifier],
                options: []
            )
        }
        let base: Set<UNNotificationCategory> = [
            replyCategory(id: messageCategory),
            replyCategory(id: updateCategory),
            UNNotificationCategory(identifier: requestCategory, actions: [], intentIdentifiers: [INSendMessageIntentIdentifier]),
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
                title: "\(action.title)…",
                options: options,
                icon: UNNotificationActionIcon(systemImageName: "arrowshape.turn.up.left"),
                textInputButtonTitle: "Send",
                textInputPlaceholder: "Your answer"
            )
        }
        return UNNotificationAction(identifier: action.id, title: action.title, options: options, icon: icon(for: action))
    }

    /// Yes gets a tick and No a cross; options read on their own.
    private static func icon(for action: NotificationAction) -> UNNotificationActionIcon? {
        switch action.id {
        case RequestActions.shared.Yes: UNNotificationActionIcon(systemImageName: "checkmark")
        case RequestActions.shared.No: UNNotificationActionIcon(systemImageName: "xmark")
        default: nil
        }
    }

    private static func replyCategory(id: String) -> UNNotificationCategory {
        UNNotificationCategory(
            identifier: id,
            actions: [
                UNTextInputNotificationAction(
                    identifier: replyAction,
                    title: "Reply…",
                    options: [.authenticationRequired],
                    icon: UNNotificationActionIcon(systemImageName: "arrowshape.turn.up.left"),
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Reply to the agent"
                ),
                UNNotificationAction(identifier: reactAction, title: "\(reactEmoji)  Thumbs up", options: []),
            ],
            // Lets a reply from an agent arrive as a message from that agent, with its face on the banner.
            intentIdentifiers: [INSendMessageIntentIdentifier],
            options: []
        )
    }
}
