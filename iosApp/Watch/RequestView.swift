import Shared
import SwiftUI

/// One question, with its answers as buttons: Yes and No, one per option, or dictation for text.
struct RequestView: View {
    let request: PendingRequest
    @EnvironmentObject private var model: WatchModel
    @Environment(\.dismiss) private var dismiss
    @State private var sending = false
    @State private var failure: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(request.byline)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(request.question)
                    .font(.headline)
                if let detail = request.detail {
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                answers

                NavigationLink {
                    SayView(request: request)
                } label: {
                    Label("Reply in thread", systemImage: "bubble.left")
                }

                if sending { ProgressView() }
                if let failure {
                    Text(failure)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(request.agentName)
        .disabled(sending)
    }

    @ViewBuilder private var answers: some View {
        let actions = RequestActions.shared.allActionsFor(kind: request.kind, options: request.options)
        if actions.isEmpty {
            Label("Answer on your iPhone", systemImage: "iphone")
                .font(.footnote)
                .foregroundStyle(.orange)
        }
        ForEach(actions, id: \.id) { action in
            if action.isTextInput {
                // Opens the watch's own input: dictation, Scribble or the keyboard.
                TextFieldLink(prompt: Text(request.defaultLabel ?? "Your answer")) {
                    Label("Answer", systemImage: "mic.fill")
                        .frame(maxWidth: .infinity)
                } onSubmit: { text in
                    send(action.id, typed: text)
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
            } else {
                Button {
                    send(action.id, typed: nil)
                } label: {
                    Text(action.title)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(tint(for: action))
            }
        }
    }

    /// The agent's suggested answer stands out; No is red, as on the lock screen.
    private func tint(for action: NotificationAction) -> Color {
        if action.title == request.defaultLabel { return .orange }
        return action.isDestructive ? .red : .gray
    }

    private func send(_ actionId: String, typed: String?) {
        if let typed, typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return }
        sending = true
        failure = nil
        Task {
            let failed = await model.answer(request, actionId: actionId, typed: typed)
            sending = false
            if let failed {
                failure = failed
            } else {
                dismiss()
            }
        }
    }
}
