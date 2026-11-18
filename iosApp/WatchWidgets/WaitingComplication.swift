import Shared
import SwiftUI
import WidgetKit

@main
struct AgentsWatchWidgets: WidgetBundle {
    var body: some Widget {
        WaitingComplication()
    }
}

/// A watch face complication: how many questions agents are waiting on. Tapping it opens the inbox.
struct WaitingComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "AgentsWaiting", provider: WaitingProvider()) { entry in
            WaitingView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
                .widgetURL(WaitingCount.inboxURL)
        }
        .configurationDisplayName("Waiting")
        .description("How many questions your agents are waiting on.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryInline, .accessoryRectangular])
    }
}

struct WaitingEntry: TimelineEntry {
    let date: Date
    /// Nil when the watch has no key yet, or has never managed to count.
    let waiting: Int?
}

struct WaitingProvider: TimelineProvider {
    func placeholder(in context: Context) -> WaitingEntry {
        WaitingEntry(date: .now, waiting: 2)
    }

    func getSnapshot(in context: Context, completion: @escaping (WaitingEntry) -> Void) {
        if context.isPreview {
            completion(placeholder(in: context))
        } else {
            Task { completion(await current()) }
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WaitingEntry>) -> Void) {
        Task {
            // The watch app asks for a redraw whenever its count changes; this is the fallback.
            let next = Date.now.addingTimeInterval(15 * 60)
            completion(Timeline(entries: [await current()], policy: .after(next)))
        }
    }

    private func current() async -> WaitingEntry {
        guard let client = Dashboard.client() else { return WaitingEntry(date: .now, waiting: nil) }
        defer { client.close() }
        if let summary = try? await client.summary() {
            WaitingCount.saved = summary.requests.count
        }
        return WaitingEntry(date: .now, waiting: WaitingCount.saved)
    }
}

struct WaitingView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.widgetRenderingMode) private var renderingMode
    let entry: WaitingEntry

    private var count: String { entry.waiting.map { "\($0)" } ?? "–" }

    private var sentence: String {
        switch entry.waiting {
        case nil: "Open Wildware Feed"
        case 0?: "Nobody waiting"
        case 1?: "1 question waiting"
        case let waiting?: "\(waiting) questions waiting"
        }
    }

    private var isWaiting: Bool { (entry.waiting ?? 0) > 0 }
    /// Amber when someone waits, on faces drawn in full colour; the face's own tint otherwise.
    private var tint: Color { isWaiting ? Theme.waiting : Theme.textMuted }

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    RobotMark(color: renderingMode == .fullColor ? Theme.robot : .primary)
                        .frame(height: isWaiting ? 13 : 22)
                    if isWaiting || entry.waiting == nil {
                        Text(count)
                            .font(Theme.Font.fixedMono(17))
                            .foregroundStyle(tint)
                            .widgetAccentable()
                    }
                }
            }
            .accessibilityLabel(sentence)
        case .accessoryCorner:
            RobotMark(color: renderingMode == .fullColor ? Theme.robot : .primary)
                .padding(4)
                .widgetLabel {
                    Text(isWaiting ? "\(count) waiting" : sentence)
                        .foregroundStyle(tint)
                }
                .accessibilityLabel(sentence)
        case .accessoryInline:
            Label {
                Text(sentence)
            } icon: {
                Image("RobotMark")
            }
        default:
            HStack(spacing: 8) {
                RobotMark(color: renderingMode == .fullColor ? Theme.robot : .primary)
                    .frame(height: 26)
                VStack(alignment: .leading, spacing: 0) {
                    Text("Wildware Feed")
                        .font(Theme.Font.sans(13, .semibold, relativeTo: .headline))
                        .foregroundStyle(.secondary)
                    Text(sentence)
                        .font(Theme.Font.sans(15, .semibold, relativeTo: .body))
                        .foregroundStyle(tint)
                        .widgetAccentable()
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
        }
    }
}
