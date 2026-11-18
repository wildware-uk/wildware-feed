import ActivityKit
import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// The Live Activity's shape. The server pushes `ContentState` with these exact keys,
/// and names this type in push-to-start payloads, so renaming anything here breaks both.
struct AgentsActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var online: Int
        var waiting: Int
        var inProgress: Int
        var topAgent: String?
        var topQuestion: String?
        var topRequestId: String?
        var topKind: String?
    }
}

extension AgentsActivityAttributes.ContentState {
    init(_ summary: DashboardSummary) {
        let top = summary.topRequest
        self.init(
            online: summary.onlineAgents.count,
            waiting: summary.requests.count,
            inProgress: Int(summary.work.inProgress),
            topAgent: top?.agentName,
            topQuestion: top?.question,
            topRequestId: top?.id,
            topKind: top?.kind.wireName
        )
    }

    var topIsConfirm: Bool { topKind == "confirm" }

    var headline: String {
        if waiting > 0, let topAgent { return "\(topAgent) is waiting on you" }
        if inProgress > 0 { return "\(inProgress) in progress" }
        return online == 1 ? "1 agent online" : "\(online) agents online"
    }
}
