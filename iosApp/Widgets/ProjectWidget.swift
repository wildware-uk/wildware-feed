import AppIntents
import Shared
import SwiftUI
import WidgetKit

/// The one setting the project widget has: which project it follows.
struct ProjectWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Project"
    static let description = IntentDescription("Pick the project this widget follows.")

    @Parameter(title: "Project")
    var project: ProjectEntity?

    init() {}

    init(project: ProjectEntity?) {
        self.project = project
    }
}

struct ProjectEntry: TimelineEntry {
    let date: Date
    let state: State

    enum State {
        case notLinked
        case unreachable
        case unchosen
        case gone(String)
        case ready(ProjectCard)
    }
}

struct ProjectProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ProjectEntry {
        ProjectEntry(date: .now, state: .ready(ProjectCard.sample(size: CardSize.of(context.family))))
    }

    func snapshot(for configuration: ProjectWidgetIntent, in context: Context) async -> ProjectEntry {
        if context.isPreview { return placeholder(in: context) }
        return await entry(for: configuration, family: context.family)
    }

    func timeline(for configuration: ProjectWidgetIntent, in context: Context) async -> Timeline<ProjectEntry> {
        // Pushes reload widgets the moment something changes; this is only the fallback.
        let next = Date.now.addingTimeInterval(15 * 60)
        return Timeline(entries: [await entry(for: configuration, family: context.family)], policy: .after(next))
    }

    private func entry(for configuration: ProjectWidgetIntent, family: WidgetFamily) async -> ProjectEntry {
        guard let project = configuration.project else { return ProjectEntry(date: .now, state: .unchosen) }
        guard let client = Dashboard.client() else { return ProjectEntry(date: .now, state: .notLinked) }
        defer { client.close() }
        guard let summary = try? await client.summary() else { return ProjectEntry(date: .now, state: .unreachable) }
        // Checked before asking for the project's feed, which a deleted project would refuse.
        guard summary.projects.contains(where: { $0.slug == project.id }) else {
            return ProjectEntry(date: .now, state: .gone(project.name))
        }
        guard let activity = try? await client.recent(projectSlug: project.id, limit: 3) else {
            return ProjectEntry(date: .now, state: .unreachable)
        }
        guard let card = ProjectCard.companion.build(projectSlug: project.id, summary: summary, activity: activity, size: CardSize.of(family)) else {
            return ProjectEntry(date: .now, state: .gone(project.name))
        }
        return ProjectEntry(date: .now, state: .ready(card))
    }
}

struct ProjectWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "AgentsProject", intent: ProjectWidgetIntent.self, provider: ProjectProvider()) { entry in
            ProjectWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color.dashboardBackground }
        }
        .configurationDisplayName("Project")
        .description("One project's latest news, and what it's waiting on you for.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
        // StandBy and the lock screen drop the dark card and draw the words straight on the background.
        .containerBackgroundRemovable(true)
    }
}

struct ProjectWidgetView: View {
    let entry: ProjectEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.showsWidgetContainerBackground) private var showsBackground

    var body: some View {
        ProjectWidgetContent(entry: entry, family: family, showsBackground: showsBackground)
    }
}

/// The widget's look for one size. Separate from `ProjectWidgetView` so it can be drawn outside WidgetKit, for screenshots.
struct ProjectWidgetContent: View {
    let entry: ProjectEntry
    let family: WidgetFamily
    /// False in StandBy, where the widget fills a dark, distant screen and needs bigger words.
    let showsBackground: Bool

    var body: some View {
        switch entry.state {
        case .notLinked:
            message("Open Agents to sign in")
        case .unreachable:
            Label("Dashboard unreachable", systemImage: "wifi.exclamationmark").font(.caption)
        case .unchosen:
            message("Hold the widget and choose Edit to pick a project")
        case let .gone(name):
            message("\(name) is no longer on the dashboard")
        case let .ready(card):
            content(card)
                .widgetURL(DeepLink.open(path: link(card)))
        }
    }

    private func message(_ text: String) -> some View {
        Text(text).font(.caption).foregroundStyle(.secondary)
    }

    private func link(_ card: ProjectCard) -> String {
        let project = "/projects/\(card.project.slug)"
        if let request = card.questions.first { return "\(project)?focus=\(request.id)" }
        if let latest = card.latest { return "\(project)?focus=\(latest.id)" }
        return project
    }

    @ViewBuilder
    private func content(_ card: ProjectCard) -> some View {
        switch family {
        case .accessoryInline:
            Text("\(card.project.name): \(card.statusLine)")
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: card.waiting > 0 ? "hand.raised.fill" : "text.bubble")
                    Text(card.waiting > 0 ? "\(card.waiting)" : String(card.project.name.prefix(3)))
                        .font(.caption.bold())
                }
                .widgetAccentable()
            }
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(card.project.name).font(.headline).lineLimit(1).widgetAccentable()
                Text(card.statusLine).font(.caption).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .systemSmall:
            small(card)
        default:
            roomy(card)
        }
    }

    private func small(_ card: ProjectCard) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Header(card: card, big: !showsBackground)
            Text(card.headline)
                .font(showsBackground ? .subheadline.bold() : .title3.bold())
                .lineLimit(showsBackground ? 4 : 3)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            if let latest = card.latest { Byline(update: latest, big: !showsBackground) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .foregroundStyle(.white)
    }

    private func roomy(_ card: ProjectCard) -> some View {
        let large = family == .systemLarge
        // A medium widget with a question gives the question the room; the news shrinks to its headline.
        let asking = !card.questions.isEmpty
        return VStack(alignment: .leading, spacing: large ? 8 : 4) {
            Header(card: card, big: !showsBackground)
            Text(card.headline).font(large ? .title3.bold() : .headline).lineLimit(large || !asking ? 2 : 1)
            if large || !asking {
                if !card.excerpt.isEmpty {
                    Text(card.excerpt).font(.caption).foregroundStyle(.white.opacity(0.75)).lineLimit(large ? (asking ? 3 : 6) : 2)
                }
                if let latest = card.latest { Byline(update: latest, big: false) }
            }
            if asking {
                if large { Divider().overlay(.white.opacity(0.3)) }
                ForEach(card.questions, id: \.id) { request in
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(request.agentName) asks").font(.caption2.bold()).foregroundStyle(.orange)
                        Text(request.question).font(.caption).lineLimit(2)
                        AnswerButtons(request: request)
                    }
                }
            } else if large {
                Spacer()
                Text("Nothing waiting on you").font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .foregroundStyle(.white)
    }

    private struct Header: View {
        let card: ProjectCard
        let big: Bool

        var body: some View {
            HStack(spacing: 6) {
                Text(card.project.name)
                    .font(big ? .headline : .caption.bold())
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(1)
                Spacer(minLength: 0)
                if card.waiting > 0 {
                    Label("\(card.waiting)", systemImage: "hand.raised.fill")
                        .font(big ? .headline : .caption.bold())
                        .foregroundStyle(.orange)
                        .labelStyle(.titleAndIcon)
                }
            }
        }
    }

    private struct Byline: View {
        let update: RecentUpdate
        let big: Bool

        var body: some View {
            HStack(spacing: 4) {
                Text(update.agentName).bold().lineLimit(1)
                Text("·")
                // A clock time, not "6 min ago": the widget redraws every few minutes, and a ticking timer reads badly.
                Text(update.date, format: Calendar.current.isDateInToday(update.date)
                    ? Date.FormatStyle.dateTime.hour().minute()
                    : Date.FormatStyle.dateTime.month(.abbreviated).day())
            }
            .lineLimit(1)
            .font(big ? .subheadline : .caption2)
            .foregroundStyle(.white.opacity(0.6))
        }
    }
}

extension CardSize {
    static func of(_ family: WidgetFamily) -> CardSize {
        switch family {
        case .systemSmall: .small
        case .systemMedium: .medium
        case .systemLarge, .systemExtraLarge: .large
        default: .accessory
        }
    }
}

extension ProjectCard {
    /// What the widget gallery shows before a project is picked, and the screenshots draw.
    static func sample(size: CardSize) -> ProjectCard {
        let project = ProjectRef(slug: "garden-game", name: "Garden Game")
        let update = RecentUpdate(
            id: "u-sample",
            project: project,
            agentName: "Builder",
            title: "Fee rounding fixed",
            text: "Fee rounding fixed\nThe checkout now rounds fees to the penny. All 412 tests pass. A pull request is open for review.",
            createdAt: Int64(Date.now.addingTimeInterval(-6 * 60).timeIntervalSince1970 * 1000)
        )
        let request = PendingRequest(
            id: "req-sample",
            agentName: "Builder",
            projectSlug: project.slug,
            projectName: project.name,
            question: "Tests pass. Merge the fee-rounding branch into main?",
            kind: RequestKind.companion.fromWire(value: "confirm")!,
            options: nil,
            expiresAt: "2026-11-16T12:00:00Z",
            detail: nil,
            defaultAnswer: nil
        )
        let activity = RecentActivity(projects: [project], updates: [update], messages: [])
        let summary = DashboardSummary(
            at: "2026-11-16T12:00:00Z",
            onlineAgents: [],
            requests: [request],
            work: WorkCounts(todo: 0, inProgress: 1, blocked: 0),
            projects: [project]
        )
        return ProjectCard.companion.build(projectSlug: project.slug, summary: summary, activity: activity, size: size)!
    }
}
