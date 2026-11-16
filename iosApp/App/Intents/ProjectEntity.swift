import AppIntents
import Shared

/// A dashboard project as Siri sees it, so "the garden game agent" can be resolved to a board.
struct ProjectEntity: AppEntity {
    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "Project")
    static let defaultQuery = ProjectQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct ProjectQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [ProjectEntity] {
        try await projects().filter { identifiers.contains($0.slug) }.map(ProjectEntity.init)
    }

    func entities(matching string: String) async throws -> [ProjectEntity] {
        let all = try await projects()
        guard let best = ProjectMatcher.shared.bestMatch(spoken: string, projects: all) else { return [] }
        return [ProjectEntity(best)]
    }

    func suggestedEntities() async throws -> [ProjectEntity] {
        try await projects().map(ProjectEntity.init)
    }

    private func projects() async throws -> [ProjectRef] {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        return try await client.summary().projects
    }
}

extension ProjectEntity {
    init(_ project: ProjectRef) {
        self.init(id: project.slug, name: project.name)
    }
}
