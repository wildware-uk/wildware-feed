import CoreSpotlight
import Shared
import SwiftUI

@main
struct AgentsApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onOpenURL { url in
                    if let path = DeepLink.path(from: url) { model.open(path: path) }
                }
                // Launch, as well as each return to the app below, so search knows the latest.
                .task { if model.isLinked { await SpotlightIndexer.refresh() } }
                .onContinueUserActivity(CSSearchableItemActionType) { activity in
                    if let path = SpotlightIndexer.path(from: activity) { model.open(path: path) }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            guard model.isLinked else { return }
            if phase == .active { OutboxRunner.shared.kick(ignoreBackoff: true) }
            if phase == .background { OutboxRunner.shared.scheduleBackground() }
            Task { await LiveActivityController.shared.refresh(startIfNeeded: phase == .background) }
            if phase == .active { Task { await SpotlightIndexer.refresh() } }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if let server = model.server {
                // Picked once per device, never by window size, so resizing on iPad never rebuilds the web view.
                if UIDevice.current.userInterfaceIdiom == .pad {
                    PadLayout(server: server)
                } else {
                    PhoneLayout(server: server)
                }
            } else {
                SetupView()
            }
        }
        // Questions arrive by push too, but a missed push must not leave the Inbox stale for long.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                if model.isLinked { await InboxStore.shared.refresh() }
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }
}

/// iPhone: the dashboard with the composer bar under it, and the Inbox as a sheet the bar opens.
private struct PhoneLayout: View {
    let server: URL
    @EnvironmentObject private var model: AppModel

    var body: some View {
        DashboardScreen(server: server, showsInbox: true, showsMenu: true)
            .overlay(alignment: .bottomLeading) {
                // Before linking there is no bar, but the menu must still reach "Change dashboard".
                if !model.isLinked { AppMenu().padding(12) }
            }
            .sheet(isPresented: $model.showInbox) {
                NavigationStack {
                    InboxView(leave: { model.showInbox = false })
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) { Button("Done") { model.showInbox = false } }
                        }
                }
                .environmentObject(model)
            }
    }
}

/// iPad: the Inbox in the sidebar, the dashboard beside it.
private struct PadLayout: View {
    let server: URL
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var store = InboxStore.shared
    @State private var columns = NavigationSplitViewVisibility.all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            Group {
                if model.isLinked {
                    InboxView()
                } else {
                    ContentUnavailableView(
                        "Sign in to see your Inbox",
                        systemImage: "link",
                        description: Text("Sign in on the page, and this iPad links itself.")
                    )
                }
            }
            .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 440)
        } detail: {
            DashboardScreen(server: server, showsInbox: false, showsMenu: false)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        if columns == .detailOnly && store.count > 0 {
                            Button { columns = .all } label: {
                                Label("Inbox: \(store.count) waiting", systemImage: "tray.full")
                                    .labelStyle(.titleAndIcon)
                            }
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) { AppMenu(compact: true) }
                }
        }
        .navigationSplitViewStyle(.balanced)
    }
}

/// The dashboard page, with the composer bar beneath it once the device is linked.
/// The bar sits below the page rather than over it, so it never covers the page's own inputs,
/// and it leaves while the page's own text box has the keyboard.
private struct DashboardScreen: View {
    let server: URL
    let showsInbox: Bool
    let showsMenu: Bool
    @EnvironmentObject private var model: AppModel

    private var showsBar: Bool {
        model.isLinked && !model.pageIsTyping && Composer.shared.showsOn(path: model.currentPath)
    }

    var body: some View {
        VStack(spacing: 0) {
            DashboardWebView(
                server: server, navigation: model.navigation, textBox: model.pageTextBox,
                onPathChanged: { model.currentPath = $0 }
            ) { webView in
                model.pageLoaded(in: webView)
            }
            // The page moves its own inputs above the keyboard, as it does in Safari.
            .ignoresSafeArea(.keyboard)
            .ignoresSafeArea(.container, edges: showsBar ? [] : .bottom)
            if showsBar {
                ComposerBar(showsInbox: showsInbox, showsMenu: showsMenu)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.15), value: showsBar)
        .background(Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x19 / 255))
        .overlay(alignment: .top) {
            if let error = model.lastError {
                Text(error)
                    .font(.footnote)
                    .padding(10)
                    .background(.red.opacity(0.85), in: RoundedRectangle(cornerRadius: 10))
                    .foregroundStyle(.white)
                    .padding()
                    .onTapGesture { model.lastError = nil }
            }
        }
    }
}

/// The few native settings there are, tucked into one small button so the dashboard keeps the screen.
struct AppMenu: View {
    /// In the composer bar or a toolbar, a plain icon; floating over the page, a dimmed round button.
    var compact = false
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Menu {
            if model.isLinked {
                Button("Show Live Activity", systemImage: "rectangle.badge.checkmark") {
                    Task { await LiveActivityController.shared.start() }
                }
                Button("Hide Live Activity", systemImage: "rectangle.badge.xmark") {
                    Task { await LiveActivityController.shared.endAll() }
                }
            } else {
                Text("Sign in on the page to link this phone")
            }
            Button("Change dashboard", systemImage: "server.rack", role: .destructive) {
                model.forgetServer()
            }
        } label: {
            if compact {
                Image(systemName: "ellipsis.circle").font(.body)
            } else {
                Image(systemName: model.isLinked ? "ellipsis.circle" : "link.circle")
                    .font(.title2)
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(6)
                    .background(.black.opacity(0.25), in: Circle())
            }
        }
        .accessibilityLabel("Settings")
    }
}

struct SetupView: View {
    @EnvironmentObject private var model: AppModel
    @State private var address = "https://"
    @State private var problem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Agents").font(.largeTitle.bold())
            Text("Enter your dashboard's address. You'll sign in on the next screen, and this phone links itself.")
                .foregroundStyle(.secondary)
            TextField("https://agents.example.com", text: $address)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .onSubmit(connect)
            if let problem { Text(problem).foregroundStyle(.red).font(.footnote) }
            Button("Connect", action: connect).buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding(24)
    }

    private func connect() {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme, ["https", "http"].contains(scheme), url.host() != nil else {
            problem = "That doesn't look like a web address."
            return
        }
        model.setServer(url)
    }
}
