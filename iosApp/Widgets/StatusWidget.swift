import AppIntents
import Shared
import SwiftUI
import WidgetKit

/// What the status widget knows: the questions waiting (oldest first), who is online, and how much is in progress.
struct StatusSnapshot {
    var requests: [PendingRequest]
    var online: [OnlineAgent]
    var inProgress: Int

    init(requests: [PendingRequest], online: [OnlineAgent], inProgress: Int) {
        self.requests = requests
        self.online = online
        self.inProgress = inProgress
    }

    init(_ summary: DashboardSummary) {
        self.init(requests: summary.requests, online: summary.onlineAgents, inProgress: Int(summary.work.inProgress))
    }

    var waiting: Int { requests.count }
    var top: PendingRequest? { requests.first }
}

struct StatusEntry: TimelineEntry {
    let date: Date
    let state: State

    enum State {
        case notLinked
        case unreachable
        case ready(StatusSnapshot)
    }
}

struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, state: .ready(.sample))
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        if context.isPreview { return completion(placeholder(in: context)) }
        Task { completion(await entries().first!) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        Task {
            // Pushes reload the widget the moment something changes; this is only the fallback.
            completion(Timeline(entries: await entries(), policy: .after(Date.now.addingTimeInterval(15 * 60))))
        }
    }

    /// One entry now, then one every five minutes with the same data, so "41m left" counts down between reloads.
    private func entries() async -> [StatusEntry] {
        guard let client = Dashboard.client() else { return [StatusEntry(date: .now, state: .notLinked)] }
        defer { client.close() }
        guard let summary = try? await client.summary() else { return [StatusEntry(date: .now, state: .unreachable)] }
        let snapshot = StatusSnapshot(summary)
        let now = Date.now
        return (0..<4).map { StatusEntry(date: now.addingTimeInterval(Double($0) * 5 * 60), state: .ready(snapshot)) }
    }
}

struct StatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AgentsStatus", provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
        }
        .configurationDisplayName("Wildware Feed")
        .description("What your agents are waiting on you for, and what they're working on.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct StatusWidgetView: View {
    let entry: StatusEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        StatusWidgetContent(entry: entry, family: family)
            .cardBackground(waiting: entry.isWaiting)
    }
}

extension StatusEntry {
    var isWaiting: Bool {
        if case let .ready(snapshot) = state { return snapshot.waiting > 0 }
        return false
    }
}

/// The widget's look for one size. Separate from `StatusWidgetView` so it can be drawn outside WidgetKit, for screenshots.
struct StatusWidgetContent: View {
    let entry: StatusEntry
    let family: WidgetFamily

    var body: some View {
        switch entry.state {
        case .notLinked:
            NoticeView(symbol: "person.crop.circle.badge.questionmark", text: "Open Wildware Feed to sign in", family: family)
        case .unreachable:
            NoticeView(symbol: "wifi.exclamationmark", text: "Can't reach your dashboard", family: family)
        case let .ready(snapshot):
            content(snapshot)
                .widgetURL(DeepLink.open(path: snapshot.top.map { "/?focus=\($0.id)" } ?? "/"))
        }
    }

    @ViewBuilder
    private func content(_ snapshot: StatusSnapshot) -> some View {
        switch family {
        case .accessoryInline: StatusInline(snapshot: snapshot)
        case .accessoryCircular: StatusCircular(snapshot: snapshot, now: entry.date)
        case .accessoryRectangular: StatusRectangular(snapshot: snapshot, now: entry.date)
        case .systemSmall: StatusSmall(snapshot: snapshot, now: entry.date)
        case .systemLarge, .systemExtraLarge: StatusLarge(snapshot: snapshot, now: entry.date)
        default: StatusMedium(snapshot: snapshot, now: entry.date)
        }
    }
}

// MARK: - Home screen

private struct StatusSmall: View {
    let snapshot: StatusSnapshot
    let now: Date

    var body: some View {
        if let top = snapshot.top {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    DotLabel(text: "\(snapshot.waiting) waiting", color: Theme.waiting).widgetAccentable()
                    Spacer(minLength: 0)
                    AppMarkTile(size: 16)
                }
                Text(top.question)
                    .font(Theme.Font.title)
                    .foregroundStyle(Theme.text)
                    .lineLimit(4)
                    .minimumScaleFactor(0.85)
                    .padding(.top, Theme.Space.s)
                Spacer(minLength: Theme.Space.xs)
                AskerRow(agent: top.agentName, project: nil, expiry: top.expiry, now: now, chipSize: 18)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else {
            WorkingSmall(snapshot: snapshot)
        }
    }
}

private struct WorkingSmall: View {
    let snapshot: StatusSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Working").overline().foregroundStyle(Theme.textMuted)
                Spacer(minLength: 0)
                AppMarkTile(size: 16)
            }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("\(snapshot.inProgress)").font(Theme.Font.mono(34, .medium, relativeTo: .largeTitle)).foregroundStyle(Theme.text)
                Text(snapshot.inProgress == 1 ? "task" : "tasks").font(Theme.Font.label).foregroundStyle(Theme.textMuted)
            }
            .padding(.top, Theme.Space.m)
            OnlineLabel(count: snapshot.online.count).padding(.top, Theme.Space.xs)
            Spacer(minLength: 0)
            AgentChips(agents: snapshot.online, limit: 5)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Nobody is waiting on you. \(snapshot.inProgress) in progress, \(snapshot.online.count) online.")
    }
}

private struct StatusMedium: View {
    let snapshot: StatusSnapshot
    let now: Date

    var body: some View {
        if let top = snapshot.top {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    DotLabel(text: "Waiting on you · \(snapshot.waiting)", color: Theme.waiting).widgetAccentable()
                    Spacer(minLength: Theme.Space.s)
                    Text("\(snapshot.inProgress) working · \(snapshot.online.count) online")
                        .font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
                }
                AskerRow(agent: top.agentName, project: top.projectName, expiry: top.expiry, now: now, chipSize: 18)
                    .padding(.top, Theme.Space.s)
                QuestionAndAnswers(request: top, fullSizeButtons: false, spacing: Theme.Space.s)
                    .padding(.top, 6)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else {
            HStack(alignment: .top, spacing: Theme.Space.l) {
                WorkingSmall(snapshot: snapshot).frame(width: 110)
                VStack(alignment: .leading, spacing: 6) {
                    Text("Online").overline().foregroundStyle(Theme.textMuted)
                    ForEach(Array(snapshot.online.prefix(4)), id: \.agentId) { agent in
                        HStack(spacing: Theme.Space.s) {
                            AgentChip(name: agent.name, agentId: agent.agentId, size: 18)
                            Text(agent.name).font(Theme.Font.label).foregroundStyle(Theme.text).lineLimit(1)
                        }
                    }
                    if snapshot.online.isEmpty {
                        Text("Nobody online").font(Theme.Font.label).foregroundStyle(Theme.textFaint)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct StatusLarge: View {
    let snapshot: StatusSnapshot
    let now: Date

    var body: some View {
        if snapshot.top == nil {
            StatusMedium(snapshot: snapshot, now: now)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    DotLabel(text: "Waiting on you · \(snapshot.waiting)", color: Theme.waiting).widgetAccentable()
                    Spacer(minLength: 0)
                    AppMarkTile(size: 18)
                }
                ForEach(Array(snapshot.requests.prefix(3).enumerated()), id: \.element.id) { index, request in
                    if index > 0 { Rectangle().fill(Theme.waitingLine).frame(height: 1) }
                    VStack(alignment: .leading, spacing: 5) {
                        AskerRow(agent: request.agentName, project: request.projectName, expiry: request.expiry, now: now, chipSize: 18)
                        QuestionAndAnswers(request: request, fullSizeButtons: false, spacing: 5)
                    }
                    .padding(.vertical, Theme.Space.s)
                }
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    OnlineLabel(count: snapshot.online.count)
                    Text("· \(snapshot.inProgress) working").font(Theme.Font.meta).foregroundStyle(Theme.textMuted)
                    if snapshot.waiting > 3 {
                        Spacer(minLength: 0)
                        Text("+\(snapshot.waiting - 3) more").font(Theme.Font.meta).foregroundStyle(Theme.waiting)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

// MARK: - Lock screen

private struct StatusInline: View {
    let snapshot: StatusSnapshot

    var body: some View {
        Label {
            Text(snapshot.waiting > 0
                ? "\(snapshot.waiting) waiting · \(snapshot.inProgress) working"
                : "\(snapshot.inProgress) working · \(snapshot.online.count) online")
        } icon: {
            Image("RobotMark")
        }
    }
}

private struct StatusCircular: View {
    let snapshot: StatusSnapshot
    let now: Date

    var body: some View {
        if let top = snapshot.top, let expiry = top.expiry, expiry > now {
            // The ring drains as the oldest question runs out of time.
            ProgressView(timerInterval: now...expiry, countsDown: true) {
                EmptyView()
            } currentValueLabel: {
                VStack(spacing: 0) {
                    RobotMark(color: .primary).frame(height: 13)
                    Text("\(snapshot.waiting)").font(Theme.Font.fixedMono(18))
                }
            }
            .progressViewStyle(.circular)
            .widgetAccentable()
            .accessibilityLabel("\(snapshot.waiting) waiting on you")
        } else {
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    RobotMark(color: .primary).frame(height: snapshot.waiting > 0 ? 14 : 26)
                    if snapshot.waiting > 0 {
                        Text("\(snapshot.waiting)").font(Theme.Font.fixedMono(18))
                    }
                }
            }
            .widgetAccentable()
            .accessibilityLabel(snapshot.waiting > 0 ? "\(snapshot.waiting) waiting on you" : "Nobody waiting")
        }
    }
}

private struct StatusRectangular: View {
    let snapshot: StatusSnapshot
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            if let top = snapshot.top {
                HStack(spacing: 4) {
                    RobotMark(color: .primary).frame(height: 12)
                    Text(top.agentName)
                        .font(Theme.Font.sans(13, .semibold, relativeTo: .headline))
                        .lineLimit(1)
                    if let expiry = top.expiry, expiry > now {
                        Text("·")
                        TimeLeftText(expiry: expiry, now: now, showsClock: false)
                            .font(Theme.Font.sans(13, .semibold, relativeTo: .headline))
                    }
                }
                .widgetAccentable()
                Text(top.question).font(Theme.Font.sans(14, .medium, relativeTo: .body)).lineLimit(2)
            } else {
                HStack(spacing: 4) {
                    RobotMark(color: .primary).frame(height: 12)
                    Text("Nobody waiting").font(Theme.Font.sans(13, .semibold, relativeTo: .headline))
                }
                .widgetAccentable()
                Text("\(snapshot.inProgress) working").font(Theme.Font.sans(14, .medium, relativeTo: .body))
                Text("\(snapshot.online.count) online").font(Theme.Font.sans(14, .regular, relativeTo: .body)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Pieces

struct OnlineLabel: View {
    let count: Int

    var body: some View {
        HStack(spacing: 5) {
            StatusDot(color: Theme.online, size: 6)
            Text("\(count) online").font(Theme.Font.meta)
        }
        .foregroundStyle(Theme.online)
    }
}

struct AgentChips: View {
    let agents: [OnlineAgent]
    var limit = 4

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(agents.prefix(limit)), id: \.agentId) { agent in
                AgentChip(name: agent.name, agentId: agent.agentId, size: 20)
            }
            if agents.count > limit {
                Text("+\(agents.count - limit)").font(Theme.Font.meta).foregroundStyle(Theme.textFaint)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Not signed in, or the dashboard can't be reached.
struct NoticeView: View {
    let symbol: String
    let text: String
    let family: WidgetFamily

    var body: some View {
        switch family {
        case .accessoryInline:
            Label(text, systemImage: symbol)
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: symbol)
            }
            .accessibilityLabel(text)
        default:
            VStack(alignment: .leading, spacing: Theme.Space.s) {
                AppMarkTile(size: 20)
                Spacer(minLength: 0)
                Image(systemName: symbol).font(.system(size: 18, weight: .medium)).foregroundStyle(Theme.textMuted)
                Text(text).font(Theme.Font.label).foregroundStyle(Theme.textMuted)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

extension StatusSnapshot {
    /// What the widget gallery shows, and the screenshots draw.
    static var sample: StatusSnapshot {
        let soon = ISO8601DateFormatter().string(from: Date.now.addingTimeInterval(41 * 60 + 52))
        let later = ISO8601DateFormatter().string(from: Date.now.addingTimeInterval(2 * 3600))
        return StatusSnapshot(
            requests: [
                PendingRequest(
                    id: "req-1", agentName: "Rover", projectSlug: "garden-game", projectName: "garden-game",
                    question: "Tests pass. Merge the fee-rounding branch into main?",
                    kind: RequestKind.companion.fromWire(value: "confirm")!, options: nil,
                    expiresAt: soon, detail: nil, defaultAnswer: "true"
                ),
                PendingRequest(
                    id: "req-2", agentName: "Lumen", projectSlug: "harbor-app", projectName: "harbor-app",
                    question: "The build is green. What next?",
                    kind: RequestKind.companion.fromWire(value: "choice")!, options: ["Ship it", "Keep testing", "Not now"],
                    expiresAt: later, detail: nil, defaultAnswer: nil
                ),
            ],
            online: [
                OnlineAgent(agentId: "a-rover", name: "Rover", host: nil, cwd: nil),
                OnlineAgent(agentId: "a-lumen", name: "Lumen", host: nil, cwd: nil),
                OnlineAgent(agentId: "a-pixel", name: "Pixel", host: nil, cwd: nil),
            ],
            inProgress: 4
        )
    }
}
