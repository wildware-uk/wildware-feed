import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UIKit
import WidgetKit

/// The native side of the app: whether this phone is signed in, and the way into the Compose
/// screen for notifications, widgets, search and Siri. Compose owns sign-in and the feed; this
/// switches the phone's own features on and off around it.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var isLinked = Dashboard.isLinked || Sample.isOn

    /// Opens cards in the Compose feed. Requests made before it is on screen wait for it.
    let navigator = AppNavigator()

    private var apnsToken: String?

    /// Shows a dashboard page, such as `/projects/garden-game?focus=abc`, as its card in the feed.
    func open(path: String) {
        navigator.openPath(path: path)
    }

    /// Compose just signed this phone in and saved its key: turn on what needs one.
    func signedIn() async {
        isLinked = true
        WatchLink.shared.sendCredentials()
        await Notifications.requestPermission()
        if let apnsToken { await sendApnsToken(apnsToken) }
        await LiveActivityController.shared.watchTokens()
        await LiveActivityController.shared.start()
        WidgetCenter.shared.reloadAllTimelines()
        AgentsShortcuts.updateAppShortcutParameters()
        await KnownProjects.refresh()
        await SpotlightIndexer.refresh()
        OutboxRunner.shared.kick(ignoreBackoff: true)
    }

    /// Signed out in the app, or the dashboard stopped taking the key: nothing should keep using it.
    func signedOut() {
        isLinked = false
        WatchLink.shared.sendCredentials()
        WidgetCenter.shared.reloadAllTimelines()
        Task { await LiveActivityController.shared.endAll() }
        Task { await SpotlightIndexer.clear() }
    }

    func receivedApnsToken(_ token: String) {
        apnsToken = token
        guard isLinked, !Sample.isOn else { return }
        Task { await sendApnsToken(token) }
    }

    private func sendApnsToken(_ token: String) async {
        guard let client = Dashboard.client() else { return }
        defer { client.close() }
        #if DEBUG
        let environment = ApnsEnvironment.sandbox
        #else
        let environment = ApnsEnvironment.production
        #endif
        // Alerts come back on the next launch's try; there is nowhere useful to show this failure.
        try? await client.setApnsToken(token: token, environment: environment)
    }
}
