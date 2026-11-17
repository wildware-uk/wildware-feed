import Shared
import SwiftUI

/// The list of every question waiting on the owner. On iPad it is the sidebar; on iPhone, a sheet the bar opens.
/// Swipe right says Yes and swipe left says No to a yes-or-no question; tap any row for the whole question.
struct InboxView: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject private var store = InboxStore.shared
    @State private var opened: OpenedRequest?
    @State private var problem: String?
    /// Called once the owner leaves the Inbox for the page or the composer, so the iPhone sheet can close.
    var leave: () -> Void = {}

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let requests = store.pending(at: context.date)
            List {
                if let message = problem ?? store.problem {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .onTapGesture { problem = nil }
                }
                ForEach(requests, id: \.id) { request in
                    Button { opened = OpenedRequest(request: request) } label: {
                        InboxRow(request: request, now: context.date)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        if Inbox.shared.canSwipe(request: request) {
                            Button("Yes", systemImage: "checkmark") { answer(request, RequestActions.shared.Yes) }
                                .tint(.green)
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        if Inbox.shared.canSwipe(request: request) {
                            Button("No", systemImage: "xmark") { answer(request, RequestActions.shared.No) }
                                .tint(.red)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .overlay {
                if requests.isEmpty && store.hasLoaded && store.problem == nil {
                    ContentUnavailableView(
                        "Nobody is waiting on you",
                        systemImage: "checkmark.circle",
                        description: Text("When an agent asks you something, it shows up here.")
                    )
                }
            }
        }
        .refreshable { await store.refresh() }
        .navigationTitle(store.count > 0 ? "Inbox (\(store.count))" : "Inbox")
        .sheet(item: $opened) { opened in
            RequestSheet(request: opened.request, leave: leave)
                .environmentObject(model)
        }
        .task { await store.refresh() }
    }

    private func answer(_ request: PendingRequest, _ actionId: String) {
        Task {
            do {
                _ = try await store.answer(request, actionId: actionId)
            } catch {
                problem = "Your answer didn't send: \(error.localizedDescription)"
            }
        }
    }
}

struct OpenedRequest: Identifiable {
    let request: PendingRequest
    var id: String { request.id }
}

struct InboxRow: View {
    let request: PendingRequest
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(request.agentName).font(.subheadline.weight(.semibold))
                if let project = request.projectName {
                    Text(project).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
                if let left = Inbox.shared.timeLeft(expiresAt: request.expiresAt, nowMillis: now.millis) {
                    Text(left)
                        .font(.caption.weight(.medium))
                        .monospacedDigit()
                        .foregroundStyle(Inbox.shared.isUrgent(expiresAt: request.expiresAt, nowMillis: now.millis) ? Color.red : Color.secondary)
                }
            }
            Text(request.question)
                .font(.body)
                .lineLimit(3)
            if Inbox.shared.canSwipe(request: request) {
                Text("Swipe right for Yes, left for No")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// One question in full, with the control its kind needs.
struct RequestSheet: View {
    let request: PendingRequest
    let leave: () -> Void

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var typed: String
    @State private var sending = false
    @State private var problem: String?

    init(request: PendingRequest, leave: @escaping () -> Void) {
        self.request = request
        self.leave = leave
        _typed = State(initialValue: request.defaultAnswer ?? "")
    }

    private var control: InboxControl { Inbox.shared.control(kind: request.kind) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    Text(request.question)
                        .font(.title3.weight(.semibold))
                        .textSelection(.enabled)
                    if let detail = request.detail, !detail.isEmpty {
                        Text(detail)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    if let problem {
                        Text(problem).font(.footnote).foregroundStyle(.red)
                    }
                    answerControl
                        .disabled(sending)
                    Divider()
                    Button("Reply without answering", systemImage: "bubble.left") {
                        model.replyFromComposer(to: request)
                        dismiss()
                        leave()
                    }
                    if control != .dashboard {
                        Button("Open in dashboard", systemImage: "safari") { openInDashboard() }
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .overlay { if sending { ProgressView().controlSize(.large) } }
        }
        .presentationDetents([.medium, .large])
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(request.agentName).font(.headline)
                if let project = request.projectName {
                    Text(project).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Spacer()
            TimelineView(.periodic(from: .now, by: 30)) { context in
                if let left = Inbox.shared.timeLeft(expiresAt: request.expiresAt, nowMillis: context.date.millis) {
                    Text(left)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Inbox.shared.isUrgent(expiresAt: request.expiresAt, nowMillis: context.date.millis) ? Color.red : Color.secondary)
                }
            }
        }
    }

    @ViewBuilder
    private var answerControl: some View {
        if control == .choices {
            VStack(spacing: 10) {
                ForEach(Inbox.shared.choices(request: request), id: \.actionId) { choice in
                    Button(role: choice.isDestructive ? .destructive : nil) {
                        send(choice.actionId)
                    } label: {
                        Text(choice.title).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(choice.isDestructive ? .red : .accentColor)
                    .controlSize(.large)
                }
            }
        } else if control == .text {
            VStack(alignment: .trailing, spacing: 10) {
                TextField("Your answer", text: $typed, axis: .vertical)
                    .lineLimit(2...8)
                    .textFieldStyle(.roundedBorder)
                Button("Send answer") { send(Inbox.shared.TextActionId, typed: typed) }
                    .buttonStyle(.borderedProminent)
                    .disabled(typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("This one needs the full card: it has several picks or a form.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button { openInDashboard() } label: {
                    Label("Open in dashboard", systemImage: "safari").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        }
    }

    private func send(_ actionId: String, typed: String? = nil) {
        sending = true
        problem = nil
        Task {
            defer { sending = false }
            do {
                if try await InboxStore.shared.answer(request, actionId: actionId, typedText: typed) {
                    dismiss()
                }
            } catch {
                problem = "Your answer didn't send: \(error.localizedDescription)"
            }
        }
    }

    private func openInDashboard() {
        model.open(path: Inbox.shared.dashboardPath(request: request))
        dismiss()
        leave()
    }
}
