import ActivityKit
import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// Keeps one Live Activity on the lock screen and Dynamic Island, and tells the dashboard
/// where to push its updates so it stays current while the app is closed.
///
/// It is there while a question waits on the owner, and only then. The app starts one when it
/// comes to the front (iOS refuses a start from the background); the dashboard starts one by push
/// when the app is closed. Whichever sees nothing waiting first ends it, at once.
actor LiveActivityController {
    static let shared = LiveActivityController()

    private var isWatching = false
    private var watchedActivities: Set<String> = []
    /// The tokens the dashboard has taken since launch, so each goes once per launch, and again when it changes.
    private var sentStartToken: String?
    private var sentUpdateTokens: Set<String> = []
    /// Why the last start failed, in plain words, until one succeeds.
    private var problem: String?

    /// Watches for the tokens iOS hands out: one to let the server start an activity on its own,
    /// and one per running activity to update it. Also sends the ones iOS already holds.
    func watchTokens() {
        guard !isWatching else { return }
        isWatching = true

        Task {
            for await token in Activity<AgentsActivityAttributes>.pushToStartTokenUpdates {
                await self.sendStartToken(token.hexString)
            }
        }
        Task {
            for await activity in Activity<AgentsActivityAttributes>.activityUpdates {
                await self.endOthers(keeping: activity)
                await self.watch(activity)
            }
        }
        Task {
            for await allowed in ActivityAuthorizationInfo().activityEnablementUpdates {
                await self.report(allowed: allowed)
                if allowed { await self.syncTokens() }
            }
        }
        if let newest = Activity<AgentsActivityAttributes>.activities.last {
            Task { await endOthers(keeping: newest) }
        }
        for activity in Activity<AgentsActivityAttributes>.activities {
            watch(activity)
        }
        Task { await syncTokens() }
    }

    /// Sends the tokens iOS holds right now. Its streams hand each one out only when it changes, so
    /// one send that failed, or a dashboard that dropped a token, would otherwise leave the server
    /// unable to reach this phone for good.
    func syncTokens() async {
        report(allowed: ActivityAuthorizationInfo().areActivitiesEnabled)
        if let token = Activity<AgentsActivityAttributes>.pushToStartToken {
            await sendStartToken(token.hexString)
        }
        for activity in Activity<AgentsActivityAttributes>.activities where activity.activityState == .active {
            if let token = activity.pushToken { await sendUpdateToken(token.hexString) }
        }
    }

    private func sendStartToken(_ hex: String) async {
        guard hex != sentStartToken else { return }
        if await send("push-to-start token", { try await $0.setPushToStartToken(token: hex) }) { sentStartToken = hex }
    }

    private func sendUpdateToken(_ hex: String) async {
        guard !sentUpdateTokens.contains(hex) else { return }
        if await send("update token", { try await $0.setLiveActivityToken(token: hex) }) { sentUpdateTokens.insert(hex) }
    }

    private func clearUpdateToken() async {
        sentUpdateTokens.removeAll()
        await send("update token", { try await $0.setLiveActivityToken(token: nil) })
    }

    /// The dashboard can start an activity by push while one is already showing, so keep
    /// only the newest rather than letting them stack up on the lock screen.
    private func endOthers(keeping kept: Activity<AgentsActivityAttributes>) async {
        for activity in Activity<AgentsActivityAttributes>.activities where activity.id != kept.id {
            await Self.end(activity)
        }
    }

    /// Starts an activity showing [state]. Only works while the app is in front.
    private func start(_ state: AgentsActivityAttributes.ContentState) async {
        watchTokens()
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            report(allowed: false)
            return
        }
        guard Activity<AgentsActivityAttributes>.activities.isEmpty else { return }
        do {
            let activity = try Activity.request(
                attributes: AgentsActivityAttributes(),
                content: ActivityContent(state: state, staleDate: nil),
                pushType: .token
            )
            problem = nil
            report(allowed: true)
            watch(activity)
        } catch {
            let (code, words) = Self.explain(error)
            problem = words
            note("start failed: \(code)")
            report(allowed: ActivityAuthorizationInfo().areActivitiesEnabled)
        }
    }

    /// Brings the activity up to date, or ends it when nothing waits on the owner any more. With
    /// [startIfNeeded] (the app in front), starts one when something does.
    func refresh(startIfNeeded: Bool) async {
        guard let state = await currentState() else { return }
        let running = Activity<AgentsActivityAttributes>.activities
        if running.isEmpty {
            if startIfNeeded, state.needsOwner { await start(state) }
            return
        }
        // Also covers an activity the dashboard can't end itself, because its update token never reached it.
        guard state.needsOwner else {
            for activity in running { await Self.end(activity) }
            return
        }
        for activity in running {
            await activity.update(ActivityContent(state: state.keepingServerFields(of: activity.content.state), staleDate: nil))
        }
    }

    func endAll() async {
        for activity in Activity<AgentsActivityAttributes>.activities {
            await Self.end(activity)
        }
        sentStartToken = nil
        await clearUpdateToken()
    }

    /// Ends [activity] with a whole, all-clear last frame rather than none, and takes it away at once,
    /// so iOS has nothing half-drawn to leave behind on the lock screen or in the Dynamic Island.
    private static func end(_ activity: Activity<AgentsActivityAttributes>) async {
        let final = ActivityContent(state: activity.content.state.allClear, staleDate: nil)
        await activity.end(final, dismissalPolicy: .immediate)
    }

    private func watch(_ activity: Activity<AgentsActivityAttributes>) {
        guard watchedActivities.insert(activity.id).inserted else { return }
        Task {
            for await token in activity.pushTokenUpdates {
                await self.sendUpdateToken(token.hexString)
            }
        }
        Task {
            for await state in activity.activityStateUpdates where state == .ended || state == .dismissed {
                let anotherIsShowing = Activity<AgentsActivityAttributes>.activities.contains {
                    $0.id != activity.id && $0.activityState == .active
                }
                if !anotherIsShowing { await self.clearUpdateToken() }
            }
        }
    }

    private func currentState() async -> AgentsActivityAttributes.ContentState? {
        guard let client = Dashboard.client() else { return nil }
        defer { client.close() }
        guard let summary = try? await client.summary() else { return nil }
        return AgentsActivityAttributes.ContentState(summary)
    }

    /// Whether the dashboard took it. A failure is noted for crash reports, by what was being sent only.
    @discardableResult
    private func send(_ what: String, _ call: (DashboardClient) async throws -> Void) async -> Bool {
        guard let client = Dashboard.client() else { return false }
        defer { client.close() }
        do {
            try await call(client)
            return true
        } catch {
            let code = (error as NSError).code
            note("could not send the \(what): error \(code)")
            return false
        }
    }

    /// For the settings sheet: whether iOS allows Live Activities for this app, and why the last start failed.
    private func report(allowed: Bool) {
        #if COMPOSE_APP
        LiveActivities.shared.update(allowed: allowed, problem: problem)
        #endif
    }

    /// A note for crash reports. Holds nothing of the owner's.
    private func note(_ message: String) {
        #if COMPOSE_APP
        LiveActivities.shared.note(message: message)
        #endif
    }

    /// A short code for crash reports, and the plain words the settings sheet shows.
    private static func explain(_ error: Error) -> (String, String) {
        guard let error = error as? ActivityAuthorizationError else {
            return ("\((error as NSError).domain) \((error as NSError).code)", "iOS refused it (\(error.localizedDescription)).")
        }
        switch error {
        case .denied:
            return ("denied", "Live Activities are turned off for this app.")
        case .visibility:
            return ("visibility", "the app was not on screen. It tries again next time you open it.")
        case .globalMaximumExceeded, .targetMaximumExceeded:
            return ("maximum", "too many Live Activities are already showing on this iPhone.")
        case .unsupported:
            return ("unsupported", "this device does not support Live Activities.")
        default:
            return (String(describing: error), "iOS refused it (\(error.localizedDescription)).")
        }
    }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

extension AgentsActivityAttributes.ContentState {
    /// A question waits on the owner: the only time the activity is up. Work in progress alone
    /// doesn't keep it. The same rule the dashboard starts and ends it by (`LiveActivity.needed`).
    var needsOwner: Bool { waiting > 0 }

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
