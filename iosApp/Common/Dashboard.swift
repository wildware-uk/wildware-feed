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

extension PendingRequest {
    /// When the question times out, or nil when the server's time doesn't read.
    var expiry: Date? {
        Self.isoFractional.date(from: expiresAt) ?? Self.iso.date(from: expiresAt)
    }

    private static let iso = ISO8601DateFormatter()
    private static let isoFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

extension RecentUpdate {
    var date: Date { Date(timeIntervalSince1970: Double(createdAt) / 1000) }
}
