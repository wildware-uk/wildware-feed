#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UIKit
import UserNotifications
import WidgetKit

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        #if COMPOSE_APP
        // First, so a crash anywhere after this is reported (in a build that reports crashes).
        IosCrashReportingKt.startCrashReporting(dataDir: AppData.folder.path(percentEncoded: false))
        #endif
        UNUserNotificationCenter.current().delegate = self
        WatchLink.shared.start()
        OutboxRunner.registerBackgroundTasks()
        Task { @MainActor in OutboxRunner.shared.start() }
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

    /// A quiet push from the dashboard naming alerts the owner has dealt with, here or on another
    /// device: read, answered, allowed, denied, withdrawn or deleted. They come off the lock screen.
    /// iOS delivers these when it chooses, and never to an app swiped away in the app switcher, so
    /// opening the app catches up as well (the Compose session asks on every return).
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]
    ) async -> UIBackgroundFetchResult {
        guard let tags = userInfo["clear"] as? [String] else { return .noData }
        let client = Dashboard.client()
        defer { client?.close() }
        try? await IosSystemNotices.shared.clearDelivered(tags: tags, client: client)
        // An answered question may have been the last: end the Live Activity rather than leave it up.
        await LiveActivityController.shared.refresh(startIfNeeded: false)
        return .newData
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        // The feed hears about it over its own live connection; the widgets need telling.
        WidgetCenter.shared.reloadAllTimelines()
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
        guard action != UNNotificationDismissActionIdentifier, Dashboard.isLinked else { return }

        if action == Notifications.reactAction {
            await react(to: AlertPayload(response.notification.request.content))
            return
        }

        // Both go through the outbox, so an answer or reply tapped with no signal still arrives later.
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        let queued: String
        if let requestId = info["requestId"] as? String, let kind = info["requestKind"] as? String {
            queued = OutboxQueue.enqueueAnswer(
                agentName: info["agentName"] as? String ?? response.notification.request.content.title,
                requestId: requestId,
                kind: kind,
                options: info["options"] as? [String],
                actionId: action,
                typedText: typed
            )
        } else if action == Notifications.replyAction, let thread = info["reply"] as? [String: String], let typed {
            queued = OutboxQueue.enqueueMessage(
                label: "Reply: \(response.notification.request.content.title)", thread: thread, text: typed
            )
        } else {
            return
        }
        // A refusal is announced by the outbox itself; only a send that has to wait needs saying here.
        if await OutboxQueue.flush(only: queued) == .waiting {
            await tell("No connection right now. It will send when you're back online.")
        }
        await MainActor.run { OutboxRunner.shared.reload() }
        WidgetCenter.shared.reloadAllTimelines()
        await LiveActivityController.shared.refresh(startIfNeeded: false)
    }

    /// Dashboard links (or bare paths) open their card in the feed; anything else goes to Safari.
    @MainActor
    private func follow(_ link: URL) async {
        let isDashboard = link.scheme == nil || link.host() == CredentialStore.server?.host()
        if isDashboard, let path = DeepLink.dashboardPath(fromAbsolute: link.absoluteString) {
            AppModel.shared.open(path: path)
        } else {
            _ = await UIApplication.shared.open(link)
        }
    }

    /// A thumbs up from the alert's menu. Sent straight away: a reaction isn't worth queueing for later.
    private func react(to payload: AlertPayload) async {
        guard let card = payload.card, let client = Dashboard.client() else { return }
        defer { client.close() }
        do {
            _ = try await client.react(card: card, emoji: Notifications.reactEmoji, on: KotlinBoolean(bool: true))
        } catch {
            await tell("Your \(Notifications.reactEmoji) didn't send: \(error.localizedDescription)")
        }
    }

    /// The answer was tapped on the lock screen, so say what went wrong there too.
    private func tell(_ message: String) async {
        let content = UNMutableNotificationContent()
        content.title = "Wildware Feed"
        content.body = message
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        try? await UNUserNotificationCenter.current().add(request)
    }
}
