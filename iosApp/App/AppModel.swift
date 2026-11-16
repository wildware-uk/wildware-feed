import Foundation
import Shared
import UIKit
import WebKit
import WidgetKit

/// The app's state: which dashboard it points at, whether this phone is linked, and what page to show.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var server: URL? = CredentialStore.server
    @Published private(set) var isLinked = Dashboard.isLinked
    @Published var navigation: WebNavigation?
    @Published var lastError: String?

    private var apnsToken: String?
    private var isLinking = false

    func setServer(_ url: URL) {
        CredentialStore.server = url
        CredentialStore.clear()
        server = url
        isLinked = false
    }

    func forgetServer() {
        CredentialStore.clear()
        CredentialStore.server = nil
        server = nil
        isLinked = false
        Task { await LiveActivityController.shared.endAll() }
    }

    func open(path: String) {
        navigation = WebNavigation(path: path)
    }

    /// Called after every page load. Once the owner has signed in on the web page, the
    /// login cookie is swapped for this phone's own key, so native features work without the web view.
    func pageLoaded(in webView: WKWebView) {
        guard !isLinked, !isLinking, let server else { return }
        isLinking = true
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
            Task { @MainActor in
                defer { self.isLinking = false }
                guard let session = cookies.first(where: { $0.name == "ad_session" && server.host()?.hasSuffix($0.domain.trimmingCharacters(in: ["."])) == true })
                else { return }
                await self.link(server: server, sessionCookie: "\(session.name)=\(session.value)")
            }
        }
    }

    private func link(server: URL, sessionCookie: String) async {
        let client = IosDashboardKt.dashboardClient(server: server.absoluteString, deviceToken: nil)
        defer { client.close() }
        do {
            let registration = try await client.registerDevice(name: UIDevice.current.name, sessionCookie: sessionCookie)
            CredentialStore.save(deviceId: registration.device.id, token: registration.token)
            isLinked = true
            lastError = nil
            await Notifications.requestPermission()
            if let apnsToken { await sendApnsToken(apnsToken) }
            await LiveActivityController.shared.start()
            WidgetCenter.shared.reloadAllTimelines()
            AgentsShortcuts.updateAppShortcutParameters()
        } catch {
            lastError = "Could not link this phone: \(error.localizedDescription)"
        }
    }

    func receivedApnsToken(_ token: String) {
        apnsToken = token
        guard isLinked else { return }
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
        do {
            try await client.setApnsToken(token: token, environment: environment)
        } catch {
            lastError = "Could not turn on alerts: \(error.localizedDescription)"
        }
    }
}
