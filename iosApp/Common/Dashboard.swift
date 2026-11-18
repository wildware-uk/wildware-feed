import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// The way into the Kotlin client from any target. Nil until the phone is linked.
enum Dashboard {
    static func client() -> DashboardClient? {
        guard let credentials = CredentialStore.load() else { return nil }
        return IosDashboardKt.dashboardClient(server: credentials.server.absoluteString, deviceToken: credentials.token)
    }

    static var isLinked: Bool { CredentialStore.load() != nil }
}

enum DashboardError: LocalizedError {
    case notLinked

    var errorDescription: String? {
        switch self {
        case .notLinked: "Open Wildware Feed and sign in to your dashboard first."
        }
    }
}

extension DashboardSummary {
    var topRequest: PendingRequest? { requests.first }
}

extension RecentUpdate {
    var date: Date { Date(timeIntervalSince1970: Double(createdAt) / 1000) }
}
