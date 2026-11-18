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
                HStack(spacing: 5) {
                    AgentChip(name: request.agentName, agentId: request.agentId, size: 15)
                    Text(request.byline).textCase(.uppercase).lineLimit(1)
                }
                .font(Theme.Font.meta)
                .foregroundStyle(Theme.textMuted)
                Text(request.question)
                    .font(Theme.Font.sans(16, .semibold, relativeTo: .headline))
                    .foregroundStyle(Theme.text)
                if let expiry = request.expiry, expiry > .now {
                    TimeLeftText(expiry: expiry, now: .now)
                    .font(Theme.Font.meta)
                    .foregroundStyle(Theme.waiting)
                }
                if let detail = request.detail {
                    Text(detail)
                        .font(Theme.Font.label.weight(.regular))
                        .foregroundStyle(Theme.textMuted)
                }

                answers

                NavigationLink {
                    SayView(request: request)
                } label: {
                    Label("Reply in thread", systemImage: "arrowshape.turn.up.left")
                        .font(Theme.Font.bodyStrong)
                }
                .tint(Theme.accent)

                if sending { ProgressView() }
                if let failure {
                    Text(failure)
                        .font(Theme.Font.label)
                        .foregroundStyle(Theme.error)
                }
            }
        }
        .navigationTitle(request.agentName)
        .containerBackground(Theme.waitingSoft.gradient, for: .navigation)
        .disabled(sending)
    }

    @ViewBuilder private var answers: some View {
        let actions = RequestActions.shared.allActionsFor(kind: request.kind, options: request.options)
        if actions.isEmpty {
            Label("Answer on your iPhone", systemImage: "iphone")
                .font(Theme.Font.label)
                .foregroundStyle(Theme.waiting)
        }
        ForEach(actions, id: \.id) { action in
            if action.isTextInput {
                // Opens the watch's own input: dictation, Scribble or the keyboard.
                TextFieldLink(prompt: Text(request.defaultLabel ?? "Your answer")) {
                    Label("Answer", systemImage: "mic.fill")
                        .font(Theme.Font.bodyStrong)
                        .foregroundStyle(Theme.onWaiting)
                        .frame(maxWidth: .infinity)
                } onSubmit: { text in
                    send(action.id, typed: text)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(Theme.waiting)
            } else {
                let suggested = isSuggested(action, among: actions)
                Button {
                    send(action.id, typed: nil)
                } label: {
                    HStack(spacing: 5) {
                        if suggested { Image(systemName: "checkmark") }
                        Text(action.title)
                    }
                    .font(Theme.Font.bodyStrong)
                    .foregroundStyle(suggested ? Theme.onWaiting : action.isDestructive ? Theme.error : Theme.text)
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(suggested ? Theme.waiting : Theme.raised)
            }
        }
    }

    /// The agent's suggested answer is the amber one, else the first, as on the phone. No is red text.
    private func isSuggested(_ action: NotificationAction, among actions: [NotificationAction]) -> Bool {
        if let label = request.defaultLabel, actions.contains(where: { $0.title == label }) { return action.title == label }
        return action.id == actions.first?.id
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
