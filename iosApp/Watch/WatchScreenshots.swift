#if DEBUG
import Foundation
import Shared

/// Debug builds only. Launched with `-screenshots` (the inbox) or `-screenshots-question` (one
/// question), the watch app shows these sample questions with no phone and no network, so the
/// manual screenshots job (.github/workflows/screenshots.yml) can draw it for App Store Connect.
/// Release builds, which TestFlight and the App Store get, leave this file out.
enum WatchScreenshots {
    static let question = ProcessInfo.processInfo.arguments.contains("-screenshots-question")
    static let on = question || ProcessInfo.processInfo.arguments.contains("-screenshots")

    static var summary: DashboardSummary {
        let iso = ISO8601DateFormatter()
        func inMinutes(_ minutes: Double) -> String { iso.string(from: Date.now.addingTimeInterval(minutes * 60)) }
        func kind(_ wire: String) -> RequestKind { RequestKind.companion.fromWire(value: wire)! }
        let requests = [
            PendingRequest(
                id: "q-1", agentName: "Lumen", projectSlug: "garden-game", projectName: "garden-game",
                question: "Use the new watering-can sound for everyone?", kind: kind("buttons"),
                options: ["Ship it", "Keep testing", "Not now"], expiresAt: inMinutes(23 * 60 + 41),
                detail: "Testers kept it on 84% of the time.", defaultAnswer: "Ship it", agentId: "a-lumen"
            ),
            PendingRequest(
                id: "q-2", agentName: "Rover", projectSlug: "garden-game", projectName: "garden-game",
                question: "Roll rain growth out to the winter map too?", kind: kind("confirm"),
                options: nil, expiresAt: inMinutes(52), detail: nil, defaultAnswer: "true", agentId: "a-rover"
            ),
            PendingRequest(
                id: "q-3", agentName: "Pixel", projectSlug: "harbor-app", projectName: "harbor-app",
                question: "What should the delayed-ferry alert say?", kind: kind("text"),
                options: nil, expiresAt: inMinutes(5 * 60), detail: nil, defaultAnswer: nil, agentId: "a-pixel"
            ),
        ]
        return DashboardSummary(
            at: iso.string(from: Date.now),
            onlineAgents: [
                OnlineAgent(agentId: "a-rover", name: "Rover", host: nil, cwd: nil),
                OnlineAgent(agentId: "a-lumen", name: "Lumen", host: nil, cwd: nil),
                OnlineAgent(agentId: "a-pixel", name: "Pixel", host: nil, cwd: nil),
            ],
            requests: requests,
            work: WorkCounts(todo: 0, inProgress: 2, blocked: 1),
            projects: [ProjectRef(slug: "garden-game", name: "garden-game"), ProjectRef(slug: "harbor-app", name: "harbor-app")],
            working: []
        )
    }
}
#endif
