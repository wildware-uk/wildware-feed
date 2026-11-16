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
        }
        .onChange(of: scenePhase) { _, phase in
            guard model.isLinked else { return }
            Task { await LiveActivityController.shared.refresh(startIfNeeded: phase == .background) }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let server = model.server {
            DashboardWebView(server: server, navigation: model.navigation, onPathChanged: { model.currentPath = $0 }) { webView in
                model.pageLoaded(in: webView)
            }
            .ignoresSafeArea(edges: .bottom)
            .background(Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x19 / 255))
            .overlay(alignment: .bottomLeading) {
                HStack(alignment: .bottom, spacing: 8) {
                    AppMenu()
                    if model.isLinked { VoiceNoteButton() }
                }
                .padding(12)
            }
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
        } else {
            SetupView()
        }
    }
}

/// The few native settings there are, tucked into one small button so the dashboard keeps the screen.
struct AppMenu: View {
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
            Image(systemName: model.isLinked ? "ellipsis.circle" : "link.circle")
                .font(.title2)
                .foregroundStyle(.white.opacity(0.55))
                .padding(6)
                .background(.black.opacity(0.25), in: Circle())
        }
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
