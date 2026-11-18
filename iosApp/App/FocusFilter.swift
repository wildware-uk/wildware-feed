import AppIntents
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif

/// Settings → Focus → a Focus → Add Filter → Feed. While that Focus is on, only the projects
/// picked here alert this phone; the dashboard holds the rest back. Turning the Focus off runs
/// this again with nothing picked, which lets every project through.
struct AgentsFocusFilter: SetFocusFilterIntent {
    static let title: LocalizedStringResource = "Wildware Feed alerts"
    static let description: IntentDescription? = IntentDescription(
        "Pick the projects that can still alert you during this Focus. Leave it empty to hear from every project."
    )

    @Parameter(title: "Projects that still alert")
    var projects: [ProjectEntity]?

    var displayRepresentation: DisplayRepresentation {
        let names = (projects ?? []).map(\.name)
        if names.isEmpty { return DisplayRepresentation(title: "Every project alerts") }
        return DisplayRepresentation(title: "Only \(names.joined(separator: ", "))", subtitle: "Other projects stay quiet")
    }

    func perform() async throws -> some IntentResult {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        let slugs = (projects ?? []).map(\.id)
        try await client.setAlertProjects(projectSlugs: slugs.isEmpty ? nil : slugs)
        return .result()
    }
}
