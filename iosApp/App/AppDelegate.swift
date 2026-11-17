import Shared
import UIKit
import UserNotifications
import WidgetKit

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        WatchLink.shared.start()
        Task {
            Notifications.registerCategories()
            if Dashboard.isLinked {
                await MainActor.run { application.registerForRemoteNotifications() }
                await LiveActivityController.shared.watchTokens()
            }
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in AppModel.shared.receivedApnsToken(hex) }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        WidgetCenter.shared.reloadAllTimelines()
        // Most alerts are a question arriving or being settled, so the Inbox reads the dashboard again.
        Task { await InboxStore.shared.refresh() }
        return [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier

        if action == UNNotificationDefaultActionIdentifier {
            if let link = PendingLink.take() {
                await follow(link)
            } else if let url = info["url"] as? String, let path = DeepLink.dashboardPath(fromAbsolute: url) {
                await MainActor.run { AppModel.shared.open(path: path) }
            }
            return
        }
        guard action != UNNotificationDismissActionIdentifier, let client = Dashboard.client() else { return }
        defer { client.close() }

        let typed = (response as? UNTextInputNotificationResponse)?.userText
        do {
            if let requestId = info["requestId"] as? String, let kind = info["requestKind"] as? String {
                let answered = try await client.answerFromAction(
                    requestId: requestId,
                    kind: kind,
                    options: info["options"] as? [String],
                    actionId: action,
                    typedText: typed
                )
                if !answered.boolValue { await tell("That button no longer matches the question. Open the app to answer.") }
            } else if action == Notifications.replyAction, let thread = info["reply"] as? [String: String], let typed {
                _ = try await client.reply(thread: thread, body: typed)
            }
            WidgetCenter.shared.reloadAllTimelines()
            await InboxStore.shared.refresh()
            await LiveActivityController.shared.refresh(startIfNeeded: false)
        } catch {
            await tell("Your answer didn't send: \(error.localizedDescription)")
        }
    }

    /// Dashboard links (or bare paths) open in the app's own page; anything else goes to Safari.
    @MainActor
    private func follow(_ link: URL) async {
        let isDashboard = link.scheme == nil || link.host() == CredentialStore.server?.host()
        if isDashboard, let path = DeepLink.dashboardPath(fromAbsolute: link.absoluteString) {
            AppModel.shared.open(path: path)
        } else {
            _ = await UIApplication.shared.open(link)
        }
    }

    /// The answer was tapped on the lock screen, so say what went wrong there too.
    private func tell(_ message: String) async {
        let content = UNMutableNotificationContent()
        content.title = "Agents"
        content.body = message
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
