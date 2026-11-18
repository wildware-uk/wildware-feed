import CoreSpotlight
import Foundation
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import UniformTypeIdentifiers

/// Puts projects and recent updates into the phone's search. Each item's identifier is the
/// dashboard page it opens, so tapping a result is just a deep link.
@MainActor
enum SpotlightIndexer {
    private static let indexedKey = "spotlightIndexed"
    private static let domain = "dev.wildware.agents.dashboard"
    /// Coming back to the app twice in a minute shouldn't fetch twice.
    private static let minimumGap: TimeInterval = 60
    private static var lastRun: Date?

    static func refresh() async {
        if let lastRun, Date.now.timeIntervalSince(lastRun) < minimumGap { return }
        guard CSSearchableIndex.isIndexingAvailable(), let client = Dashboard.client() else { return }
        defer { client.close() }
        lastRun = .now
        guard let activity = try? await client.recent(projectSlug: nil, limit: 50) else {
            lastRun = nil
            return
        }

        let entries = Spotlight.shared.entries(activity: activity)
        let defaults = CredentialStore.defaults
        let removed = Spotlight.shared.removed(previous: defaults.stringArray(forKey: indexedKey) ?? [], current: entries)
        let index = CSSearchableIndex.default()
        do {
            if !removed.isEmpty { try await index.deleteSearchableItems(withIdentifiers: removed) }
            try await index.indexSearchableItems(entries.map(item))
            defaults.set(entries.map(\.id), forKey: indexedKey)
        } catch {
            lastRun = nil
        }
    }

    /// When the phone is unlinked, what it found on that dashboard shouldn't stay searchable.
    static func clear() async {
        CredentialStore.defaults.removeObject(forKey: indexedKey)
        lastRun = nil
        try? await CSSearchableIndex.default().deleteSearchableItems(withDomainIdentifiers: [domain])
    }

    /// The dashboard page a tapped Spotlight result opens.
    nonisolated static func path(from activity: NSUserActivity) -> String? {
        activity.userInfo?[CSSearchableItemActivityIdentifier] as? String
    }

    private static func item(_ entry: SpotlightEntry) -> CSSearchableItem {
        let attributes = CSSearchableItemAttributeSet(contentType: .text)
        attributes.title = entry.title
        attributes.contentDescription = entry.text
        attributes.keywords = entry.keywords
        if let createdAt = entry.createdAt {
            let date = Date(timeIntervalSince1970: Double(createdAt.int64Value) / 1000)
            attributes.contentCreationDate = date
            attributes.contentModificationDate = date
        }
        return CSSearchableItem(uniqueIdentifier: entry.id, domainIdentifier: domain, attributeSet: attributes)
    }
}
