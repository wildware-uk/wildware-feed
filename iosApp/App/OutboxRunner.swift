import BackgroundTasks
import Network
import Shared
import SwiftUI

/// Keeps the outbox moving while the app lives: sends on launch, on coming forward, when the network
/// comes back, when another process queues something, when the next retry falls due, and in the background.
@MainActor
final class OutboxRunner: ObservableObject {
    static let shared = OutboxRunner()

    static let refreshTask = "dev.wildware.agents.outbox.refresh"
    static let processingTask = "dev.wildware.agents.outbox.processing"

    @Published private(set) var items: [OutboxItem] = []

    private let network = NWPathMonitor()
    private var isOnline = true
    private var flushing: Task<Void, Never>?
    private var wake: Task<Void, Never>?
    private var pendingKick: Bool?

    /// Still waiting, oldest first, then those the dashboard refused.
    var waiting: [OutboxItem] { Outbox.shared.waiting(items: items) }
    var failed: [OutboxItem] { Outbox.shared.failed(items: items) }

    func start() {
        reload()
        network.pathUpdateHandler = { path in
            Task { @MainActor in
                let online = path.status == .satisfied
                defer { OutboxRunner.shared.isOnline = online }
                // Coming back online is the moment most likely to work, so don't wait out the backoff.
                if online && !OutboxRunner.shared.isOnline { OutboxRunner.shared.kick(ignoreBackoff: true) }
            }
        }
        network.start(queue: DispatchQueue(label: "outbox-network"))
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(), nil,
            { _, _, _, _, _ in Task { @MainActor in OutboxRunner.shared.queueChanged() } },
            OutboxQueue.changedNotification as CFString, nil, .deliverImmediately
        )
        kick(ignoreBackoff: true)
    }

    func reload() {
        items = OutboxQueue.items()
    }

    /// Sends what is due. Calls while a pass runs ask for one more pass after it.
    func kick(ignoreBackoff: Bool = false) {
        guard Dashboard.isLinked, !Sample.isOn else { return }
        if flushing != nil {
            pendingKick = (pendingKick ?? false) || ignoreBackoff
            return
        }
        flushing = Task {
            _ = await Task.detached { await OutboxQueue.flush(ignoreBackoff: ignoreBackoff) }.value
            flushing = nil
            reload()
            scheduleWake()
            scheduleBackground()
            if let again = pendingKick {
                pendingKick = nil
                kick(ignoreBackoff: again)
            }
        }
    }

    func retry(_ item: OutboxItem) {
        OutboxQueue.retryNow(item.id)
        reload()
        kick(ignoreBackoff: true)
    }

    func delete(_ item: OutboxItem) {
        OutboxQueue.delete(item.id)
        reload()
    }

    private func queueChanged() {
        reload()
        // Our own writes announce too; a new item from elsewhere still needs a pass.
        if flushing == nil, Outbox.shared.next(items: items, nowMillis: OutboxQueue.nowMillis, ignoreBackoff: false, skip: []) != nil {
            kick()
        }
    }

    /// While the app is open, wake for the next retry instead of waiting for something else to happen.
    private func scheduleWake() {
        wake?.cancel()
        guard let next = Outbox.shared.nextWakeMillis(items: items)?.int64Value else { return }
        let delay = max(1, Double(next - OutboxQueue.nowMillis) / 1000)
        wake = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            kick()
        }
    }

    // MARK: Background

    /// Registered once at launch, before the app finishes launching, as iOS requires.
    nonisolated static func registerBackgroundTasks() {
        for id in [refreshTask, processingTask] {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
                let work = Task.detached {
                    await OutboxQueue.flush(ignoreBackoff: true)
                    let left = Outbox.shared.waiting(items: OutboxQueue.items()).isEmpty == false
                    task.setTaskCompleted(success: true)
                    if left { await MainActor.run { OutboxRunner.shared.reload(); OutboxRunner.shared.scheduleBackground() } }
                }
                task.expirationHandler = { work.cancel() }
            }
        }
    }

    /// Asks iOS for time in the background while anything still waits to send.
    func scheduleBackground() {
        guard !waiting.isEmpty else {
            BGTaskScheduler.shared.cancelAllTaskRequests()
            return
        }
        let earliest = Outbox.shared.nextWakeMillis(items: items).map {
            Date(timeIntervalSince1970: Double($0.int64Value) / 1000)
        } ?? .now
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshTask)
        refresh.earliestBeginDate = max(earliest, .now.addingTimeInterval(60))
        let processing = BGProcessingTaskRequest(identifier: Self.processingTask)
        processing.requiresNetworkConnectivity = true
        processing.earliestBeginDate = refresh.earliestBeginDate
        try? BGTaskScheduler.shared.submit(refresh)
        try? BGTaskScheduler.shared.submit(processing)
    }
}

/// The "Waiting to send" list: what hasn't gone yet, and what the dashboard refused.
struct OutboxSheet: View {
    @ObservedObject private var runner = OutboxRunner.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 15)) { context in
                List {
                    if !runner.waiting.isEmpty {
                        Section("Waiting to send") {
                            ForEach(runner.waiting, id: \.id) { row($0, now: context.date) }
                        }
                    }
                    if !runner.failed.isEmpty {
                        Section("Couldn't send") {
                            ForEach(runner.failed, id: \.id) { row($0, now: context.date) }
                        }
                    }
                }
                .overlay {
                    if runner.items.isEmpty {
                        ContentUnavailableView("Everything has been sent", systemImage: "checkmark.circle")
                    }
                }
            }
            .navigationTitle("Outbox")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Send now") { runner.kick(ignoreBackoff: true) }.disabled(runner.waiting.isEmpty)
                }
            }
            .refreshable { runner.kick(ignoreBackoff: true) }
        }
    }

    private func row(_ item: OutboxItem, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(item.label).font(.subheadline.weight(.semibold))
            if let text = item.text ?? item.answer?.typedText, !text.isEmpty {
                Text(text).lineLimit(3)
            }
            if !item.files.isEmpty {
                Label(item.files.count == 1 ? "1 photo" : "\(item.files.count) photos", systemImage: "photo")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(Outbox.shared.status(item: item, nowMillis: Int64(now.timeIntervalSince1970 * 1000)))
                .font(.caption)
                .foregroundStyle(item.gaveUp ? Color.red : Color.secondary)
        }
        .swipeActions(edge: .trailing) {
            Button("Delete", systemImage: "trash", role: .destructive) { runner.delete(item) }
        }
        .swipeActions(edge: .leading) {
            Button("Try again", systemImage: "arrow.clockwise") { runner.retry(item) }.tint(.blue)
        }
    }
}
