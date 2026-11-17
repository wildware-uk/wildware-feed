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
    let entry: WaitingEntry

    private var count: String { entry.waiting.map { "\($0)" } ?? "–" }

    private var sentence: String {
        switch entry.waiting {
        case nil: "Open Agents"
        case 0?: "Nobody waiting"
        case 1?: "1 question waiting"
        case let waiting?: "\(waiting) questions waiting"
        }
    }

    private var tint: Color { (entry.waiting ?? 0) > 0 ? .orange : .secondary }

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: "hand.raised.fill")
                        .font(.caption2)
                        .foregroundStyle(tint)
                    Text(count)
                        .font(.title3.bold())
                }
            }
        case .accessoryCorner:
            Text(count)
                .font(.title3.bold())
                .foregroundStyle(tint)
                .widgetLabel(sentence)
        case .accessoryInline:
            Label(sentence, systemImage: "hand.raised.fill")
        default:
            VStack(alignment: .leading, spacing: 2) {
                Label("Agents", systemImage: "hand.raised.fill")
                    .font(.headline)
                    .foregroundStyle(tint)
                Text(sentence)
                    .font(.body)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
