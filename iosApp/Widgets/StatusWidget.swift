import AppIntents
import Shared
import SwiftUI
import WidgetKit

struct StatusEntry: TimelineEntry {
    let date: Date
    let state: State

    enum State {
        case notLinked
        case unreachable
        case ready(AgentsActivityAttributes.ContentState, top: PendingRequest?)
    }
}

struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, state: .ready(.init(online: 2, waiting: 1, inProgress: 3, topAgent: "Builder", topQuestion: "Can I merge?", topRequestId: nil, topKind: nil), top: nil))
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        if context.isPreview { return completion(placeholder(in: context)) }
        Task { completion(await entry()) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        Task {
            // Pushes reload the widget the moment something changes; this is only the fallback.
            let next = Date.now.addingTimeInterval(15 * 60)
            completion(Timeline(entries: [await entry()], policy: .after(next)))
        }
    }

    private func entry() async -> StatusEntry {
        guard let client = Dashboard.client() else { return StatusEntry(date: .now, state: .notLinked) }
        defer { client.close() }
        guard let summary = try? await client.summary() else { return StatusEntry(date: .now, state: .unreachable) }
        return StatusEntry(date: .now, state: .ready(.init(summary), top: summary.topRequest))
    }
}

struct StatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AgentsStatus", provider: StatusProvider()) { entry in
            StatusWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color.dashboardBackground }
        }
        .configurationDisplayName("Agents")
        .description("Who is online, and what is waiting on you.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct StatusWidgetView: View {
    let entry: StatusEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        StatusWidgetContent(entry: entry, family: family)
    }
}

/// The widget's look for one size. Separate from `StatusWidgetView` so it can be drawn outside WidgetKit, for screenshots.
struct StatusWidgetContent: View {
    let entry: StatusEntry
    let family: WidgetFamily

    var body: some View {
        switch entry.state {
        case .notLinked:
            Text("Open Agents to sign in").font(.caption)
        case .unreachable:
            Label("Dashboard unreachable", systemImage: "wifi.exclamationmark").font(.caption)
        case let .ready(state, top):
            content(state, top: top)
                .widgetURL(DeepLink.open(path: "/"))
        }
    }

    @ViewBuilder
    private func content(_ state: AgentsActivityAttributes.ContentState, top: PendingRequest?) -> some View {
        switch family {
        case .accessoryInline:
            Text(state.waiting > 0 ? "\(state.waiting) waiting · \(state.online) online" : "\(state.online) agents online")
        case .accessoryCircular:
            Gauge(value: Double(state.waiting), in: 0...Double(max(state.waiting, 1))) {
                Image(systemName: "person.2.wave.2")
            } currentValueLabel: {
                Text(state.waiting > 0 ? "\(state.waiting)" : "\(state.online)")
            }
            .gaugeStyle(.accessoryCircular)
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text(state.headline).font(.headline).lineLimit(1).widgetAccentable()
                Text(state.topQuestion ?? "\(state.online) online · \(state.inProgress) in progress")
                    .font(.caption).lineLimit(2)
            }
        default:
            VStack(alignment: .leading, spacing: 6) {
                CountsRow(state: state)
                if let top {
                    Text(top.agentName).font(.caption.bold()).foregroundStyle(.orange)
                    Text(top.question).font(.caption).lineLimit(family == .systemSmall ? 3 : 2)
                    if family == .systemMedium { AnswerButtons(request: top) }
                } else {
                    Spacer()
                    Text("Nobody is waiting on you").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(.white)
        }
    }
}

struct CountsRow: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 10) {
            Label("\(state.online)", systemImage: "circle.fill").foregroundStyle(.green)
            Label("\(state.inProgress)", systemImage: "hammer.fill").foregroundStyle(.blue)
            Label("\(state.waiting)", systemImage: "hand.raised.fill").foregroundStyle(state.waiting > 0 ? .orange : .secondary)
        }
        .font(.caption.bold())
        .labelStyle(.titleAndIcon)
    }
}

/// Tap-to-answer buttons for a question short enough to answer from the home screen or Live Activity.
struct AnswerButtons: View {
    let request: PendingRequest

    var body: some View {
        let actions = RequestActions.shared.actionsFor(kind: request.kind, options: request.options)
        let tappable = actions.filter { !$0.isTextInput }.prefix(3)
        if !tappable.isEmpty {
            HStack(spacing: 6) {
                ForEach(Array(tappable), id: \.id) { action in
                    Button(intent: AnswerRequestIntent(
                        requestId: request.id,
                        kind: request.kind.wireName,
                        options: request.options ?? [],
                        actionId: action.id
                    )) {
                        Text(action.title).font(.caption.bold()).lineLimit(1)
                    }
                    .tint(action.isDestructive ? .red : .orange)
                }
            }
            .buttonStyle(.borderedProminent)
        }
    }
}

extension Color {
    static let dashboardBackground = Color(red: 0x11 / 255, green: 0x14 / 255, blue: 0x19 / 255)
}
