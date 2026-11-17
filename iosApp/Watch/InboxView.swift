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
                    .foregroundStyle(.orange)
            }

            if let requests = model.summary?.requests {
                if requests.isEmpty {
                    Text("Nobody is waiting on you.")
                        .foregroundStyle(.secondary)
                }
                ForEach(requests, id: \.id) { request in
                    NavigationLink {
                        RequestView(request: request)
                    } label: {
                        RequestRow(request: request)
                    }
                }
            } else if model.isLoading {
                ProgressView()
            }

            if let error = model.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .navigationTitle("Waiting")
        // Pulling down, by finger or by turning the crown past the top, checks again.
        .refreshable { await model.refresh() }
        .onAppear { Task { await model.refresh() } }
    }
}

struct RequestRow: View {
    let request: PendingRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(request.question)
                .font(.headline)
                .lineLimit(3)
            Text(request.byline)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
    }
}

extension PendingRequest {
    /// "Builder · Garden Game": who asked, and in which project when there is one.
    var byline: String {
        [agentName, projectName].compactMap { $0 }.joined(separator: " · ")
    }
}
