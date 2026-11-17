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
    /// The dashboard page on screen, which decides where a voice note goes.
    @Published var currentPath: String?
    let pageTextBox = PageTextBox()
    @Published var lastError: String?

    /// The iPhone's Inbox sheet.
    @Published var showInbox = false
    /// Where the owner chose by hand for the composer bar to send: an Inbox Reply or a project from its chip.
    @Published var composerChoice: ComposerTarget?
    /// The page's text box the owner last used, while it is still on the page.
    @Published private(set) var pageBox: ComposerTarget.PageTextBox?
    /// The page's own text box has the keyboard, so the composer bar steps out of its way.
    @Published private(set) var pageIsTyping = false
    /// Changes when something asks the composer bar to take the keyboard, such as Reply in the Inbox.
    @Published private(set) var composerFocusRequest = 0

    private var apnsToken: String?
    private var isLinking = false

    init() {
        if Sample.isOn {
            isLinked = true
            server = server ?? URL(string: ProcessInfo.processInfo.environment["DASHBOARD_URL"] ?? "https://dashboard.example.com")
        }
        pageTextBox.onChange = { [weak self] state in
            guard let self else { return }
            if state.isTyping && !self.pageIsTyping {
                // The newest thing the owner did wins: starting to type on the page takes over from any earlier pick.
                self.composerChoice = nil
            }
            self.pageIsTyping = state.isTyping
            self.pageBox = state.hasBox ? ComposerTarget.PageTextBox(placeholder: state.placeholder) : nil
        }
    }

    /// Reply in the Inbox: the bar's next message goes into that question's thread, without answering it.
    func replyFromComposer(to request: PendingRequest) {
        composerChoice = ComposerTarget.Reply(requestId: request.id, agentName: request.agentName)
        showInbox = false
        composerFocusRequest += 1
    }

    func setServer(_ url: URL) {
        CredentialStore.server = url
        CredentialStore.clear()
        server = url
        isLinked = false
        WatchLink.shared.sendCredentials()
        Task { await SpotlightIndexer.clear() }
    }

    func forgetServer() {
        CredentialStore.clear()
        CredentialStore.server = nil
        server = nil
        isLinked = false
        WatchLink.shared.sendCredentials()
        Task { await LiveActivityController.shared.endAll() }
        Task { await SpotlightIndexer.clear() }
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
            WatchLink.shared.sendCredentials()
            await InboxStore.shared.refresh()
            await Notifications.requestPermission()
            if let apnsToken { await sendApnsToken(apnsToken) }
            await LiveActivityController.shared.start()
            WidgetCenter.shared.reloadAllTimelines()
            AgentsShortcuts.updateAppShortcutParameters()
            await SpotlightIndexer.refresh()
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
