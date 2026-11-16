import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

struct AgentsLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentsActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .padding()
                .activityBackgroundTint(.dashboardBackground)
                .activitySystemActionForegroundColor(.white)
                .widgetURL(DeepLink.open(path: "/"))
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("\(state.online)", systemImage: "circle.fill").foregroundStyle(.green).font(.caption.bold())
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Label("\(state.waiting)", systemImage: "hand.raised.fill")
                        .foregroundStyle(state.waiting > 0 ? .orange : .secondary).font(.caption.bold())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(state.headline).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if let question = state.topQuestion {
                        Text(question).font(.caption).lineLimit(2)
                        ConfirmButtons(state: state)
                    } else {
                        Text("\(state.inProgress) in progress").font(.caption).foregroundStyle(.secondary)
                    }
                }
            } compactLeading: {
                Image(systemName: "person.2.wave.2").foregroundStyle(.green)
            } compactTrailing: {
                Text(state.waiting > 0 ? "\(state.waiting)" : "\(state.online)")
                    .foregroundStyle(state.waiting > 0 ? .orange : .white)
            } minimal: {
                Image(systemName: state.waiting > 0 ? "hand.raised.fill" : "person.2.wave.2")
                    .foregroundStyle(state.waiting > 0 ? .orange : .green)
            }
            .widgetURL(DeepLink.open(path: "/"))
        }
    }
}

struct LockScreenView: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(state.headline).font(.headline)
                Spacer()
                CountsRow(state: state)
            }
            if let question = state.topQuestion {
                Text(question).font(.subheadline).lineLimit(2)
                ConfirmButtons(state: state)
            }
        }
        .foregroundStyle(.white)
    }
}

/// The Live Activity only carries the top question's text, so it offers buttons just for yes-or-no questions.
/// Anything else is one tap away in the app.
struct ConfirmButtons: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        if let id = state.topRequestId, state.topIsConfirm {
            HStack {
                Button(intent: AnswerRequestIntent(requestId: id, kind: "confirm", options: [], actionId: "confirm-yes")) {
                    Text("Yes").bold()
                }.tint(.green)
                Button(intent: AnswerRequestIntent(requestId: id, kind: "confirm", options: [], actionId: "confirm-no")) {
                    Text("No").bold()
                }.tint(.red)
            }
        }
    }
}
