import Shared
import SwiftUI

/// The questions agents are waiting on, oldest first, with a way to say something new at the top.
struct InboxView: View {
    @EnvironmentObject private var model: WatchModel

    var body: some View {
        List {
            NavigationLink {
                SayView(request: nil)
            } label: {
                Label("Say something", systemImage: "mic.fill")
                    .font(Theme.Font.bodyStrong)
                    .foregroundStyle(Theme.accent)
            }

            if let requests = model.summary?.requests {
                if requests.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        RobotMark().frame(height: 22)
                        Text("Nobody is waiting on you.")
                            .font(Theme.Font.body)
                            .foregroundStyle(Theme.textMuted)
                    }
                    .listRowBackground(Color.clear)
                }
                ForEach(Array(requests.enumerated()), id: \.element.id) { index, request in
                    NavigationLink {
                        RequestView(request: request)
                    } label: {
                        RequestRow(request: request)
                    }
                    // The oldest question gets the amber card; the rest stay quiet.
                    .listRowBackground(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(index == 0 ? Theme.waitingSoft : Theme.raised)
                    )
                }
            } else if model.isLoading {
                ProgressView()
            }

            if let error = model.error {
                Text(error)
                    .font(Theme.Font.label)
                    .foregroundStyle(Theme.error)
            }
        }
        .navigationTitle {
            Text(model.summary.map { $0.requests.isEmpty ? "Waiting" : "Waiting · \($0.requests.count)" } ?? "Waiting")
                .foregroundStyle(Theme.waiting)
        }
        // Pulling down, by finger or by turning the crown past the top, checks again.
        .refreshable { await model.refresh() }
        .onAppear { Task { await model.refresh() } }
    }
}

struct RequestRow: View {
    let request: PendingRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(request.question)
                .font(Theme.Font.title)
                .foregroundStyle(Theme.text)
                .lineLimit(3)
            HStack(spacing: 5) {
                AgentChip(name: request.agentName, agentId: nil, size: 15)
                Text(request.agentName).lineLimit(1)
                Spacer(minLength: 0)
                if let expiry = request.expiry, expiry > .now {
                    Text(timerInterval: Date.now...expiry, countsDown: true, showsHours: true)
                        .foregroundStyle(Theme.waiting)
                        .monospacedDigit()
                        .frame(maxWidth: 60, alignment: .trailing)
                }
            }
            .font(Theme.Font.meta)
            .foregroundStyle(Theme.textMuted)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

extension PendingRequest {
    /// "Builder · Garden Game": who asked, and in which project when there is one.
    var byline: String {
        [agentName, projectName].compactMap { $0 }.joined(separator: " · ")
    }
}
