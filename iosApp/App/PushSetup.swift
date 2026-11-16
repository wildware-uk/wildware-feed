import UIKit
import UserNotifications

extension Notifications {
    /// Asks once for alerts, including time-sensitive ones that get through Focus, then fetches the push token.
    static func requestPermission() async {
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge, .timeSensitive])) ?? false
        guard granted else { return }
        registerCategories()
        await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
    }
}
