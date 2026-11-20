import CoreSpotlight
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import SwiftUI

@main
struct AgentsApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    // ComposeApp exports a `Scene` of its own, so name SwiftUI's.
    var body: some SwiftUI.Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onOpenURL { url in
                    // agents://invite?server=…&token=…, from an invite's page: the join screen, filled in.
                    if DeepLink.isInvite(url) {
                        _ = model.navigator.openInvite(link: url.absoluteString)
                    } else if let path = DeepLink.path(from: url) {
                        model.open(path: path)
                    }
                }
                // Launch, as well as each return to the app below, so search knows the latest.
                .task { if model.isLinked { await SpotlightIndexer.refresh() } }
                .onContinueUserActivity(CSSearchableItemActionType) { activity in
                    if let path = SpotlightIndexer.path(from: activity) { model.open(path: path) }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            guard model.isLinked else { return }
            if phase == .active {
                OutboxRunner.shared.kick(ignoreBackoff: true)
                // The live connection died while iOS had the app frozen: renew it now, not when it times out.
                AppLifecycle.shared.foreground()
            }
            if phase == .background { OutboxRunner.shared.scheduleBackground() }
            // iOS lets the app start an activity only while it is in front, so that is when it tries.
            Task {
                if phase == .active { await LiveActivityController.shared.syncTokens() }
                await LiveActivityController.shared.refresh(startIfNeeded: phase == .active)
            }
            if phase == .active { Task { await SpotlightIndexer.refresh() } }
        }
    }
}

/// The Compose app, edge to edge. It draws its own sign-in, feed, composer and waiting list.
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ComposeScreen(navigator: model.navigator)
            .ignoresSafeArea()
            .background(Color(uiColor: .obsidian).ignoresSafeArea())
            // The mic writes notes down on the phone; on iOS 26 that model downloads once, ahead of time.
            .task { if VoiceNotes.microphoneAllowed { await Transcriber.prepare() } }
            // The share sheet and Siri offer the projects last read here, so keep them fresh.
            .task(id: scenePhase) {
                guard scenePhase == .active else { return }
                while !Task.isCancelled {
                    if model.isLinked { await KnownProjects.refresh() }
                    try? await Task.sleep(for: .seconds(60))
                }
            }
    }
}
