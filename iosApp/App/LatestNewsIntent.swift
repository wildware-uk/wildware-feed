import AppIntents
#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import SwiftUI

/// "What did Garden Game say in Wildware Feed?" Reads the project's latest update out, and shows it.
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
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            if let update {
                HStack(spacing: Theme.Space.s) {
                    AgentChip(name: update.agentName, agentId: nil, size: 20)
                    Text(update.agentName).font(Theme.Font.label.weight(.semibold)).foregroundStyle(Theme.text)
                    Text(projectName).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(update.date, format: .relative(presentation: .named))
                        .font(Theme.Font.meta)
                        .foregroundStyle(Theme.textMuted)
                }
                Text(update.headline).font(Theme.Font.sans(17, .semibold, relativeTo: .headline)).foregroundStyle(Theme.text)
                let more = PlainText.shared.sentences(text: update.text, count: 3, skip: update.sentencesInHeadline, maxLength: 320)
                if !more.isEmpty {
                    Text(more).font(Theme.Font.body).foregroundStyle(Theme.textMuted).lineLimit(5)
                }
            } else {
                HStack(spacing: Theme.Space.s) {
                    AppMarkTile(size: 20)
                    Text("Nothing from \(projectName) yet.").font(Theme.Font.title).foregroundStyle(Theme.text)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.l)
    }
}
