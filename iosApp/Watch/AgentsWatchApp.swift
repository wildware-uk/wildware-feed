import Shared
import SwiftUI

@main
struct AgentsWatchApp: App {
    @StateObject private var model = WatchModel.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        PhoneLink.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environmentObject(model)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refresh() } }
        }
    }
}

struct WatchRootView: View {
    @EnvironmentObject private var model: WatchModel
    /// Changing it starts the stack again at the inbox, which is where the complication leads.
    @State private var stack = UUID()

    var body: some View {
        if model.isLinked {
            NavigationStack { InboxView() }
                .id(stack)
                .onOpenURL { _ in stack = UUID() }
        } else {
            NotLinkedView()
        }
    }
}

/// The watch has no login page: the iPhone signs in and hands over its key.
struct NotLinkedView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "iphone")
                .font(.title)
                .foregroundStyle(.orange)
            Text("Open Wildware Feed on your iPhone")
                .font(.headline)
                .multilineTextAlignment(.center)
            Text("Sign in there and your watch picks it up.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .onAppear { PhoneLink.shared.askPhone() }
    }
}
