import LocalAuthentication
import Shared
import SwiftUI
import UIKit
import WidgetKit

/// Every question waiting on the owner, and the projects the composer can post to.
/// Read from `GET /api/summary` when the app comes forward, when a push arrives, on pull to refresh,
/// and every minute while the app is open.
@MainActor
final class InboxStore: ObservableObject {
    static let shared = InboxStore()

    @Published private(set) var requests: [PendingRequest] = []
    @Published private(set) var projects: [ProjectRef] = []
    @Published private(set) var problem: String?
    @Published private(set) var hasLoaded = false

    private var refreshing: Task<Void, Never>?

    /// Open questions, the one closing soonest first.
    func pending(at now: Date = .now) -> [PendingRequest] {
        Inbox.shared.pending(requests: requests, nowMillis: now.millis)
    }

    var count: Int { pending().count }

    func refresh() async {
        if Sample.isOn {
            if !hasLoaded { requests = Sample.requests; projects = Sample.projects }
            hasLoaded = true
            return
        }
        // Several triggers can land at once (a push while the app comes forward); one read serves them all.
        if let refreshing { return await refreshing.value }
        let task = Task { await load() }
        refreshing = task
        await task.value
        refreshing = nil
    }

    private func load() async {
        guard let client = Dashboard.client() else { return }
        defer { client.close() }
        do {
            let summary = try await client.summary()
            requests = summary.requests
            projects = summary.projects
            problem = nil
        } catch {
            problem = "Couldn't reach the dashboard: \(error.localizedDescription)"
        }
        hasLoaded = true
    }

    /// Answers a question once the owner has proved it's them, as the lock screen's buttons do.
    /// Returns false when they cancelled Face ID. Throws when the dashboard refused.
    func answer(_ request: PendingRequest, actionId: String, typedText: String? = nil) async throws -> Bool {
        guard await OwnerCheck.confirm("Answer \(request.agentName)") else { return false }
        if Sample.isOn {
            requests.removeAll { $0.id == request.id }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            return true
        }
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        let answered = try await client.answerFromAction(
            requestId: request.id,
            kind: request.kind.wireName,
            options: request.options,
            actionId: actionId,
            typedText: typedText
        )
        guard answered.boolValue else { throw InboxError.notAnAnswer }
        requests.removeAll { $0.id == request.id }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        WidgetCenter.shared.reloadAllTimelines()
        Task {
            await LiveActivityController.shared.refresh(startIfNeeded: false)
            await refresh()
        }
        return true
    }
}

enum InboxError: LocalizedError {
    case notAnAnswer

    var errorDescription: String? {
        switch self {
        case .notAnAnswer: "That answer doesn't fit the question any more. Open it in the dashboard."
        }
    }
}

/// Face ID, Touch ID or the passcode before anything is approved, the same rule as the lock screen's buttons.
enum OwnerCheck {
    static func confirm(_ reason: String) async -> Bool {
        if Sample.isOn { return true }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // A phone with no passcode has nothing to check, and iOS lets its alert buttons through too.
            return (error as? LAError)?.code == .passcodeNotSet
        }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

extension Date {
    var millis: Int64 { Int64(timeIntervalSince1970 * 1000) }
}

/// Made-up questions and projects, so screenshots can show the Inbox and composer without a dashboard.
/// Debug builds only, switched on with the `-sample-inbox` launch argument.
enum Sample {
    static var isOn: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-sample-inbox")
        #else
        false
        #endif
    }

    static let projects = [
        ProjectRef(slug: "garden-game", name: "Garden Game"),
        ProjectRef(slug: "harbor-app", name: "Harbor App"),
        ProjectRef(slug: "agent-dashboard", name: "Agent Dashboard"),
    ]

    static var requests: [PendingRequest] {
        func request(
            _ id: String, _ agent: String, _ project: ProjectRef, _ question: String, _ kind: String,
            options: [String]? = nil, minutes: Double, detail: String? = nil
        ) -> PendingRequest {
            PendingRequest(
                id: id,
                agentName: agent,
                projectSlug: project.slug,
                projectName: project.name,
                question: question,
                kind: RequestKind.companion.fromWire(value: kind)!,
                options: options,
                expiresAt: ISO8601DateFormatter().string(from: .now.addingTimeInterval(minutes * 60)),
                detail: detail,
                defaultAnswer: nil
            )
        }
        return [
            request("s1", "Garden Game", projects[0], "Tests pass. Merge the fee-rounding branch into main?", "confirm",
                    minutes: 4, detail: "12 files changed. The rounding now matches the bank's statements to the penny."),
            request("s2", "Harbor App", projects[1], "The build is green. What next?", "choice",
                    options: ["Ship it", "Hold", "Roll back"], minutes: 38),
            request("s3", "Dashboard Dev", projects[2], "What should the new Inbox's empty state say?", "text", minutes: 95),
            request("s4", "Garden Game", projects[0], "Pick the release notes to include", "multi_choice",
                    options: ["Fees", "Exports", "Dark mode"], minutes: 300),
        ]
    }
}
