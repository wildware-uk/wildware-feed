import AppIntents
import Shared
import SwiftUI

/// "What did Garden Game say in Agents?" Reads the project's latest update out, and shows it.
struct LatestNewsIntent: AppIntent {
    static let title: LocalizedStringResource = "What Did a Project Say"
    static let description = IntentDescription("Reads out the latest update from a project: who posted it, when, and how it starts.")

    @Parameter(title: "Project", requestValueDialog: "Which project?")
    var project: ProjectEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Latest from \(\.$project)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        let activity = try await client.recent(projectSlug: project.id, limit: 5)
        let ref = ProjectRef(slug: project.id, name: project.name)
        let spoken = Speech.shared.latest(project: ref, activity: activity, nowMillis: Int64(Date.now.timeIntervalSince1970 * 1000))
        let update = activity.updates.first { $0.project?.slug == project.id }
        return .result(dialog: "\(spoken)", view: LatestNewsSnippet(projectName: project.name, update: update))
    }
}

struct LatestNewsSnippet: View {
    let projectName: String
    let update: RecentUpdate?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let update {
                HStack(alignment: .firstTextBaseline) {
                    Text(update.agentName).font(.subheadline.bold()).foregroundStyle(.orange)
                    Spacer()
                    Text(update.date, format: .relative(presentation: .named)).font(.caption).foregroundStyle(.secondary)
                }
                Text(update.headline).font(.headline)
                let more = PlainText.shared.sentences(text: update.text, count: 3, skip: (update.title ?? "").isEmpty ? 1 : 0, maxLength: 320)
                if !more.isEmpty {
                    Text(more).font(.subheadline).foregroundStyle(.secondary).lineLimit(5)
                }
            } else {
                Text("Nothing from \(projectName) yet.").font(.headline)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
    }
}
