import Foundation
import Shared
import WidgetKit

/// What the watch knows: whether the phone has handed it a key, and the questions waiting.
@MainActor
final class WatchModel: ObservableObject {
    static let shared = WatchModel()

    #if DEBUG
    @Published private(set) var isLinked = WatchScreenshots.on || Dashboard.isLinked
    @Published private(set) var summary: DashboardSummary? = WatchScreenshots.on ? WatchScreenshots.summary : nil
    #else
    @Published private(set) var isLinked = Dashboard.isLinked
    @Published private(set) var summary: DashboardSummary?
    #endif
    @Published private(set) var isLoading = false
    @Published var error: String?

    func credentialsChanged() {
        isLinked = Dashboard.isLinked
        summary = nil
        error = nil
        if !isLinked { WaitingCount.saved = nil }
        WidgetCenter.shared.reloadAllTimelines()
        Task { await refresh() }
    }

    func refresh() async {
        #if DEBUG
        if WatchScreenshots.on { return }
        #endif
        guard let client = Dashboard.client() else {
            isLinked = false
            return
        }
        defer { client.close() }
        isLoading = true
        defer { isLoading = false }
        do {
            let summary = try await client.summary()
            self.summary = summary
            error = nil
            if WaitingCount.saved != summary.requests.count {
                WaitingCount.saved = summary.requests.count
                WidgetCenter.shared.reloadAllTimelines()
            }
        } catch {
            self.error = "Couldn't reach the dashboard: \(error.localizedDescription)"
        }
    }

    /// Answers [request] with one of its buttons, or typed text for a text question. Returns why it failed, or nil.
    func answer(_ request: PendingRequest, actionId: String, typed: String?) async -> String? {
        await send {
            let answered = try await $0.answerFromAction(
                requestId: request.id,
                kind: request.kind.wireName,
                options: request.options,
                actionId: actionId,
                typedText: typed
            )
            return answered.boolValue ? nil : "That answer no longer fits the question. Answer on your iPhone."
        }
    }

    /// Posts [text] to a project's feed, or into a question's thread when [request] is set.
    func say(_ text: String, project: ProjectRef?, request: PendingRequest?) async -> String? {
        let thread: [String: String]
        if let request {
            thread = ["request": request.id]
        } else if let project {
            thread = ["project": project.slug]
            CredentialStore.lastProject = project
        } else {
            return "Pick a project first."
        }
        return await send {
            _ = try await $0.reply(thread: thread, body: text)
            return nil
        }
    }

    private func send(_ call: (DashboardClient) async throws -> String?) async -> String? {
        guard let client = Dashboard.client() else { return DashboardError.notLinked.localizedDescription }
        defer { client.close() }
        do {
            let failure = try await call(client)
            await refresh()
            return failure
        } catch {
            return "It didn't send: \(error.localizedDescription)"
        }
    }
}
