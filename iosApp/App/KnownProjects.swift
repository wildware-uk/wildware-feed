import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// Keeps the list of projects the share sheet and Siri offer, so they work with no signal.
/// Read when the app comes forward and once a minute while it is open.
@MainActor
enum KnownProjects {
    private static var refreshing: Task<Void, Never>?

    static func refresh() async {
        guard !Sample.isOn else { return }
        // Several triggers can land at once (a push while the app comes forward); one read serves them all.
        if let refreshing { return await refreshing.value }
        let task = Task { await load() }
        refreshing = task
        await task.value
        refreshing = nil
    }

    private static func load() async {
        guard let client = Dashboard.client() else { return }
        defer { client.close() }
        if let summary = try? await client.summary() {
            CredentialStore.knownProjects = summary.projects
        }
    }
}

/// Made-up data, so screenshots show the feed without a dashboard. Debug builds only, switched on
/// with the `-sample-inbox` launch argument. The Compose screen draws its own sample feed.
enum Sample {
    static var isOn: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-sample-inbox")
        #else
        false
        #endif
    }
}
