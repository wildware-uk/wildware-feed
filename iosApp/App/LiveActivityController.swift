import ActivityKit
import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// Keeps one Live Activity on the lock screen and Dynamic Island, and tells the dashboard
/// where to push its updates so it stays current while the app is closed.
actor LiveActivityController {
    static let shared = LiveActivityController()

    private var isWatching = false
    private var watchedActivities: Set<String> = []

    /// Watches for the tokens iOS hands out: one to let the server start an activity on its own,
    /// and one per running activity to update it.
    func watchTokens() {
        guard !isWatching else { return }
        isWatching = true

        Task {
            for await token in Activity<AgentsActivityAttributes>.pushToStartTokenUpdates {
                await send { try await $0.setPushToStartToken(token: token.hexString) }
            }
        }
        Task {
            for await activity in Activity<AgentsActivityAttributes>.activityUpdates {
                await self.endOthers(keeping: activity)
                await self.watch(activity)
            }
        }
        if let newest = Activity<AgentsActivityAttributes>.activities.last {
            Task { await endOthers(keeping: newest) }
        }
        for activity in Activity<AgentsActivityAttributes>.activities {
            watch(activity)
        }
    }

    /// The dashboard can start an activity by push while one is already showing, so keep
    /// only the newest rather than letting them stack up on the lock screen.
    private func endOthers(keeping kept: Activity<AgentsActivityAttributes>) async {
        for activity in Activity<AgentsActivityAttributes>.activities where activity.id != kept.id {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    func start() async {
        watchTokens()
        guard ActivityAuthorizationInfo().areActivitiesEnabled, Activity<AgentsActivityAttributes>.activities.isEmpty,
              let state = await currentState()
        else { return }
        do {
            let activity = try Activity.request(
                attributes: AgentsActivityAttributes(),
                content: ActivityContent(state: state, staleDate: nil),
                pushType: .token
            )
            watch(activity)
        } catch {
            return
        }
    }

    /// Brings the activity up to date. When the app goes to the background with agents busy, starts one.
    func refresh(startIfNeeded: Bool) async {
        guard let state = await currentState() else { return }
        let running = Activity<AgentsActivityAttributes>.activities
        if running.isEmpty {
            if startIfNeeded, state.online > 0 || state.waiting > 0 { await start() }
            return
        }
        for activity in running {
            await activity.update(ActivityContent(state: state.keepingServerFields(of: activity.content.state), staleDate: nil))
        }
    }

    func endAll() async {
        for activity in Activity<AgentsActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        await send { try await $0.setLiveActivityToken(token: nil) }
    }

    private func watch(_ activity: Activity<AgentsActivityAttributes>) {
        guard watchedActivities.insert(activity.id).inserted else { return }
        Task {
            for await token in activity.pushTokenUpdates {
                await send { try await $0.setLiveActivityToken(token: token.hexString) }
            }
        }
        Task {
            for await state in activity.activityStateUpdates where state == .ended || state == .dismissed {
                let anotherIsShowing = Activity<AgentsActivityAttributes>.activities.contains {
                    $0.id != activity.id && $0.activityState == .active
                }
                if !anotherIsShowing { await send { try await $0.setLiveActivityToken(token: nil) } }
            }
        }
    }

    private func currentState() async -> AgentsActivityAttributes.ContentState? {
        guard let client = Dashboard.client() else { return nil }
        defer { client.close() }
        guard let summary = try? await client.summary() else { return nil }
        return AgentsActivityAttributes.ContentState(summary)
    }

    private func send(_ call: (DashboardClient) async throws -> Void) async {
        guard let client = Dashboard.client() else { return }
        defer { client.close() }
        try? await call(client)
    }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

extension AgentsActivityAttributes.ContentState {
    /// The app builds its state from the summary, which doesn't yet carry everything the server pushes
    /// (the asking agent's id, what agents are working on). Keeps those from the last push rather than
    /// blanking them until the next one.
    func keepingServerFields(of pushed: Self) -> Self {
        var merged = self
        if merged.working == nil { merged.working = pushed.working }
        if merged.topAgentId == nil, merged.topRequestId == pushed.topRequestId { merged.topAgentId = pushed.topAgentId }
        return merged
    }
}
