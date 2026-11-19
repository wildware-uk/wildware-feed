import ActivityKit
import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// The Live Activity's shape. The server pushes `ContentState` with these exact keys,
/// and names this type in push-to-start payloads, so renaming anything here breaks both.
///
/// The first seven keys are the original contract. Everything after them is optional and newer:
/// a server that leaves them out still decodes, and the views fall back to what the seven say.
struct AgentsActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var online: Int
        var waiting: Int
        var inProgress: Int
        var topAgent: String?
        var topQuestion: String?
        var topRequestId: String?
        var topKind: String?

        /// The asking agent's id, so its chip has the colour it has in the app.
        var topAgentId: String? = nil
        /// The project the oldest question is in.
        var topProject: String? = nil
        /// When the oldest question times out, in whole seconds since 1970.
        var topExpiresAt: Int? = nil
        /// Its options, for buttons and choice questions.
        var topOptions: [String]? = nil
        /// The answer the agent suggests: an option, "true" or "false".
        var topDefault: String? = nil
        /// What agents are busy with, at most three.
        var working: [Working]? = nil
        /// Set on the phone, never by the server: the answer just tapped on the activity, shown as
        /// "Sent" until the server's next push replaces the whole state.
        var sentAnswer: String? = nil

        struct Working: Codable, Hashable {
            var agentId: String?
            var agent: String
            var task: String
            /// When the agent started, in whole seconds since 1970.
            var since: Int?
        }
    }
}

extension AgentsActivityAttributes.ContentState {
    /// Decodes the seven original keys strictly and every newer one leniently. ActivityKit draws a
    /// blank box when a pushed state fails to decode, so one newer field of an unexpected type (a
    /// list where a string was expected, say) must cost only that field, never the whole activity.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        online = try c.decode(Int.self, forKey: .online)
        waiting = try c.decode(Int.self, forKey: .waiting)
        inProgress = try c.decode(Int.self, forKey: .inProgress)
        topAgent = try c.decodeIfPresent(String.self, forKey: .topAgent)
        topQuestion = try c.decodeIfPresent(String.self, forKey: .topQuestion)
        topRequestId = try c.decodeIfPresent(String.self, forKey: .topRequestId)
        topKind = try c.decodeIfPresent(String.self, forKey: .topKind)
        topAgentId = (try? c.decodeIfPresent(String.self, forKey: .topAgentId)) ?? nil
        topProject = (try? c.decodeIfPresent(String.self, forKey: .topProject)) ?? nil
        topExpiresAt = (try? c.decodeIfPresent(Int.self, forKey: .topExpiresAt)) ?? nil
        topOptions = (try? c.decodeIfPresent([String].self, forKey: .topOptions)) ?? nil
        topDefault = (try? c.decodeIfPresent(String.self, forKey: .topDefault)) ?? nil
        working = (try? c.decodeIfPresent([Working].self, forKey: .working)) ?? nil
        sentAnswer = (try? c.decodeIfPresent(String.self, forKey: .sentAnswer)) ?? nil
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
            topKind: top?.kind.wireName,
            topAgentId: top?.agentId,
            topProject: top?.projectName,
            topExpiresAt: top?.expiry.map { Int($0.timeIntervalSince1970) },
            topOptions: top?.options,
            topDefault: top?.defaultAnswer,
            working: summary.working.isEmpty ? nil : summary.working.prefix(3).map {
                Working(agentId: $0.agentId, agent: $0.agent, task: $0.task, since: Int($0.since))
            }
        )
    }

    var topIsConfirm: Bool { topKind == "confirm" }

    var topExpiry: Date? { topExpiresAt.map { Date(timeIntervalSince1970: TimeInterval($0)) } }

    /// The oldest question as the widget views take it, rebuilt from the pushed fields.
    var topRequest: PendingRequest? {
        guard let id = topRequestId, let question = topQuestion,
              let kind = topKind.flatMap({ RequestKind.companion.fromWire(value: $0) })
        else { return nil }
        return PendingRequest(
            id: id,
            agentName: topAgent ?? "An agent",
            projectSlug: nil,
            projectName: topProject,
            question: question,
            kind: kind,
            options: topOptions,
            expiresAt: topExpiry.map { ISO8601DateFormatter().string(from: $0) } ?? "",
            detail: nil,
            defaultAnswer: topDefault,
            agentId: topAgentId
        )
    }

    var headline: String {
        if waiting > 0, let topAgent { return "\(topAgent) is waiting on you" }
        if inProgress > 0 { return inProgress == 1 ? "1 task in progress" : "\(inProgress) tasks in progress" }
        return online == 1 ? "1 agent online" : "\(online) agents online"
    }

    /// "+2 waiting" beside the oldest question, or nil when it is the only one.
    var othersWaiting: String? {
        waiting > 1 ? "+\(waiting - 1) waiting" : nil
    }

    /// The whole state in one sentence, for VoiceOver.
    var spoken: String {
        var parts: [String] = []
        if waiting > 0, let topQuestion {
            parts.append("\(topAgent ?? "An agent") asks: \(topQuestion)")
            if waiting > 1 { parts.append("\(waiting - 1) more waiting") }
        }
        parts.append(headlineCounts)
        return parts.joined(separator: ". ")
    }

    var headlineCounts: String {
        "\(inProgress) in progress, \(online) online"
    }
}
