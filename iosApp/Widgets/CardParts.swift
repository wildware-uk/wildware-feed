import AppIntents
import Shared
import SwiftUI
import WidgetKit

/// The pieces the app's waiting card is made of, sized for widgets and Live Activities.

/// "41m", "2h", "3d": how long a question has left, as of [now]. Nil once it has run out.
func timeLeft(until expiry: Date?, from now: Date) -> String? {
    guard let expiry else { return nil }
    let seconds = expiry.timeIntervalSince(now)
    guard seconds > 0 else { return nil }
    let minutes = Int((seconds / 60).rounded(.up))
    if minutes < 60 { return "\(minutes)m" }
    let hours = minutes / 60
    if hours < 48 { return "\(hours)h" }
    return "\(hours / 24)d"
}

/// "41 minutes left", for VoiceOver.
func spokenTimeLeft(until expiry: Date?, from now: Date) -> String? {
    guard let expiry, expiry > now else { return nil }
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .full
    formatter.maximumUnitCount = 1
    formatter.allowedUnits = [.day, .hour, .minute]
    return formatter.string(from: now, to: expiry).map { "\($0) left" }
}

/// Who asked and where, with the time left on the right.
struct AskerRow: View {
    let agent: String
    var agentId: String?
    var project: String?
    var expiry: Date?
    let now: Date
    var chipSize: CGFloat = 20
    /// Show a live mm:ss countdown (Live Activities) rather than a minute count (widgets).
    var live = false

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            AgentChip(name: agent, agentId: agentId, size: chipSize)
            Text(agent).font(Theme.Font.label.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
            if let project {
                Text(project).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
            }
            Spacer(minLength: Theme.Space.xs)
            if let expiry, expiry > now {
                HStack(spacing: 4) {
                    Image(systemName: "clock").font(.system(size: 10, weight: .semibold))
                    if live {
                        Text(timerInterval: now...expiry, countsDown: true)
                            .monospacedDigit()
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: 52, alignment: .trailing)
                    } else if let left = timeLeft(until: expiry, from: now) {
                        Text(left)
                    }
                }
                .font(Theme.Font.meta.weight(.medium))
                .foregroundStyle(Theme.waiting)
                .widgetAccentable()
                .fixedSize()
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([agent, project, spokenTimeLeft(until: expiry, from: now)].compactMap { $0 }.joined(separator: ", "))
    }
}

/// One tap answers. Questions that need typing, many options or several picks get one "Answer" link
/// to the question in the app instead.
struct AnswerRow: View {
    let request: PendingRequest
    var compact = false
    var stretch = false
    /// How many option buttons fit before it switches to the single "Answer" link.
    var maxButtons = 3

    var body: some View {
        let actions = RequestActions.shared.actionsFor(kind: request.kind, options: request.options)
            .filter { !$0.isTextInput }
        let fits = !actions.isEmpty && actions.count <= maxButtons
            && actions.allSatisfy { $0.title.count <= (compact ? 14 : 18) }
        HStack(spacing: Theme.Space.s) {
            if fits {
                let suggested = suggestedId(actions)
                ForEach(actions, id: \.id) { action in
                    Button(intent: AnswerRequestIntent(
                        requestId: request.id,
                        kind: request.kind.wireName,
                        options: request.options ?? [],
                        actionId: action.id
                    )) {
                        HStack(spacing: 5) {
                            if action.id == suggested {
                                Image(systemName: "checkmark").font(.system(size: compact ? 10 : 11, weight: .bold))
                            }
                            Text(action.title)
                        }
                    }
                    .buttonStyle(CardButtonStyle(prominent: action.id == suggested, compact: compact, stretch: stretch))
                    .accessibilityLabel("Answer \(action.title)")
                }
            } else {
                Link(destination: DeepLink.open(path: focusPath)) {
                    HStack(spacing: 5) {
                        Image(systemName: "pencil").font(.system(size: compact ? 10 : 11, weight: .bold))
                        Text("Answer")
                    }
                    .cardButtonChrome(prominent: true, compact: compact, stretch: stretch)
                }
                .accessibilityLabel("Answer in Wildware Feed")
            }
        }
    }

    private var focusPath: String {
        let project = request.projectSlug.map { "/projects/\($0)" } ?? "/"
        return "\(project)?focus=\(request.id)"
    }

    /// The agent's suggested answer, else the first: the one the owner most likely means.
    private func suggestedId(_ actions: [NotificationAction]) -> String? {
        if let label = request.defaultLabel, let match = actions.first(where: { $0.title == label }) { return match.id }
        return actions.first?.id
    }
}

/// "● 2 WAITING" and the like: a dot and an overline in one colour.
struct DotLabel: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            StatusDot(color: color, size: 6)
            Text(text).overline().lineLimit(1)
        }
        .foregroundStyle(color)
    }
}

extension View {
    /// Keeps the app's colours in full colour mode, and lets iOS tint the rest in tinted and clear modes.
    @ViewBuilder
    func cardBackground(waiting: Bool) -> some View {
        containerBackground(for: .widget) { waiting ? Theme.waitingSoft : Theme.surface }
    }
}
