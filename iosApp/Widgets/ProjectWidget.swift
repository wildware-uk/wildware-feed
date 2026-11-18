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
                .cardBackground(waiting: false)
        }
        .configurationDisplayName("Project")
        .description("One project's latest news, and what it's waiting on you for.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
        // StandBy and the lock screen drop the card and draw the words straight on the background.
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
            NoticeView(symbol: "person.crop.circle.badge.questionmark", text: "Open Wildware Feed to sign in", family: family)
        case .unreachable:
            NoticeView(symbol: "wifi.exclamationmark", text: "Can't reach your dashboard", family: family)
        case .unchosen:
            NoticeView(symbol: "square.and.pencil", text: "Hold the widget and choose Edit to pick a project", family: family)
        case let .gone(name):
            NoticeView(symbol: "questionmark.folder", text: "\(name) is no longer on the dashboard", family: family)
        case let .ready(card):
            content(card)
                .widgetURL(DeepLink.open(path: link(card)))
        }
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
            Label {
                Text("\(card.project.name): \(card.statusLine)")
            } icon: {
                Image("RobotMark")
            }
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    RobotMark(color: .primary).frame(height: 13)
                    Text(card.waiting > 0 ? "\(card.waiting)" : String(card.project.name.prefix(3)))
                        .font(Theme.Font.fixedMono(card.waiting > 0 ? 18 : 13))
                }
            }
            .widgetAccentable()
            .accessibilityLabel("\(card.project.name): \(card.statusLine)")
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    RobotMark(color: .primary).frame(height: 12)
                    Text(card.project.name).font(Theme.Font.sans(13, .semibold, relativeTo: .headline)).lineLimit(1)
                }
                .widgetAccentable()
                Text(card.statusLine).font(Theme.Font.sans(14, .medium, relativeTo: .body)).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .systemSmall:
            small(card)
        default:
            roomy(card)
        }
    }

    private func small(_ card: ProjectCard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Header(card: card, big: !showsBackground, narrow: true)
            Text(card.headline)
                .font(showsBackground ? Theme.Font.title : Theme.Font.sans(22, .semibold, relativeTo: .title2))
                .foregroundStyle(Theme.text)
                .lineLimit(3)
                .minimumScaleFactor(0.8)
                .padding(.top, Theme.Space.s)
            if showsBackground, !card.excerpt.isEmpty {
                Text(card.excerpt).font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.textMuted).lineLimit(2).padding(.top, 3)
            }
            Spacer(minLength: 0)
            if let latest = card.latest { Byline(update: latest, big: !showsBackground, showsName: false) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func roomy(_ card: ProjectCard) -> some View {
        let large = family == .systemLarge || family == .systemExtraLarge
        let asking = !card.questions.isEmpty
        return VStack(alignment: .leading, spacing: 0) {
            Header(card: card, big: !showsBackground)
            if large || !asking {
                News(card: card, large: large, asking: asking).padding(.top, Theme.Space.s)
            }
            if asking {
                if large {
                    VStack(spacing: Theme.Space.s) {
                        ForEach(Array(card.questions.prefix(2)), id: \.id) { QuestionWell(request: $0, now: entry.date) }
                    }
                    .padding(.top, Theme.Space.m)
                } else {
                    // A medium widget gives the question the room; the news shrinks to its headline beside it.
                    HStack(alignment: .top, spacing: Theme.Space.m) {
                        News(card: card, large: false, asking: true)
                        if let request = card.questions.first {
                            QuestionWell(request: request, now: entry.date, narrow: true).frame(width: 140)
                        }
                    }
                    .padding(.top, Theme.Space.s)
                }
            } else if large {
                Spacer()
                Text("Nothing waiting on you").font(Theme.Font.meta).foregroundStyle(Theme.textFaint)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private struct Header: View {
        let card: ProjectCard
        let big: Bool
        /// The small widget: the count goes without its word, so the project's name has the room.
        var narrow = false

        var body: some View {
            HStack(spacing: 6) {
                Text(card.project.name)
                    .overline()
                    .font(big ? Theme.Font.mono(14, .medium, relativeTo: .headline) : Theme.Font.overline)
                    .foregroundStyle(Theme.textMuted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
                if card.waiting > 0 {
                    DotLabel(text: narrow ? "\(card.waiting)" : "\(card.waiting) waiting", color: Theme.waiting)
                        .widgetAccentable()
                        .fixedSize()
                        .accessibilityLabel("\(card.waiting) waiting on you")
                } else if !big {
                    AppMarkTile(size: 16)
                }
            }
        }
    }

    /// The latest update: headline, a few lines, who and when.
    private struct News: View {
        let card: ProjectCard
        let large: Bool
        let asking: Bool

        var body: some View {
            VStack(alignment: .leading, spacing: 3) {
                Text(card.headline)
                    .font(large ? Theme.Font.sans(18, .semibold, relativeTo: .title3) : Theme.Font.title)
                    .foregroundStyle(Theme.text)
                    .lineLimit(2)
                if !card.excerpt.isEmpty {
                    Text(card.excerpt)
                        .font(Theme.Font.label.weight(.regular))
                        .foregroundStyle(Theme.textMuted)
                        .lineLimit(large ? (asking ? 3 : 7) : 2)
                }
                Spacer(minLength: 0)
                if let latest = card.latest { Byline(update: latest, big: false) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// A question, set in an amber well like the app's waiting card.
    private struct QuestionWell: View {
        let request: PendingRequest
        let now: Date
        var narrow = false

        var body: some View {
            VStack(alignment: .leading, spacing: 6) {
                if !narrow {
                    AskerRow(agent: request.agentName, agentId: request.agentId, project: nil, expiry: request.expiry, now: now, chipSize: 18)
                }
                Text(request.question)
                    .font(narrow ? Theme.Font.label.weight(.semibold) : Theme.Font.bodyStrong)
                    .foregroundStyle(Theme.text)
                    .lineLimit(narrow ? 3 : 2)
                Spacer(minLength: 0)
                AnswerRow(request: request, compact: true, stretch: narrow, maxButtons: narrow ? 2 : 3)
            }
            .padding(Theme.Space.s + 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.waitingSoft, in: RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous).strokeBorder(Theme.waitingLine, lineWidth: 1))
        }
    }

    private struct Byline: View {
        let update: RecentUpdate
        let big: Bool
        /// False in the small widget, where the chip's initial says who and the time needs the room.
        var showsName = true

        var body: some View {
            HStack(spacing: 6) {
                AgentChip(name: update.agentName, agentId: nil, size: big ? 22 : 18)
                // A clock time, not "6 min ago": the widget redraws every few minutes, and a ticking timer reads badly.
                let time = Text(update.date, format: Calendar.current.isDateInToday(update.date)
                    ? Date.FormatStyle.dateTime.hour().minute()
                    : Date.FormatStyle.dateTime.month(.abbreviated).day())
                (showsName ? Text("\(update.agentName) · \(time)") : time)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(update.agentName), \(update.date.formatted(date: .omitted, time: .shortened))")
            .font(big ? Theme.Font.mono(14, relativeTo: .subheadline) : Theme.Font.meta)
            .foregroundStyle(Theme.textMuted)
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
        let project = ProjectRef(slug: "garden-game", name: "garden-game")
        let update = RecentUpdate(
            id: "u-sample",
            project: project,
            agentName: "Rover",
            title: "Fee rounding fixed",
            text: "Fee rounding fixed\nThe checkout now rounds fees to the penny. All 412 tests pass. A pull request is open for review.",
            createdAt: Int64(Date.now.addingTimeInterval(-6 * 60).timeIntervalSince1970 * 1000)
        )
        let request = PendingRequest(
            id: "req-sample",
            agentName: "Rover",
            projectSlug: project.slug,
            projectName: project.name,
            question: "Tests pass. Merge the fee-rounding branch into main?",
            kind: RequestKind.companion.fromWire(value: "confirm")!,
            options: nil,
            expiresAt: ISO8601DateFormatter().string(from: Date.now.addingTimeInterval(41 * 60 + 43)),
            detail: nil,
            defaultAnswer: nil,
            agentId: "a-rover"
        )
        let activity = RecentActivity(projects: [project], updates: [update], messages: [])
        let summary = DashboardSummary(
            at: "2026-11-16T12:00:00Z",
            onlineAgents: [],
            requests: [request],
            work: WorkCounts(todo: 0, inProgress: 1, blocked: 0),
            projects: [project],
            working: []
        )
        return ProjectCard.companion.build(projectSlug: project.slug, summary: summary, activity: activity, size: size)!
    }
}
