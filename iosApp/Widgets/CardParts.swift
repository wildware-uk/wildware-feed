import AppIntents
import Shared
import SwiftUI
import WidgetKit

/// The pieces the app's waiting card is made of, sized for widgets and Live Activities.

/// Who asked and where, with the time left on the right.
struct AskerRow: View {
    let agent: String
    var agentId: String?
    var project: String?
    var expiry: Date?
    let now: Date
    var chipSize: CGFloat = 20
    /// False where there's only room for the chip and the time (the small widget); VoiceOver still says who.
    var showsName = true

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            AgentChip(name: agent, agentId: agentId, size: chipSize)
            if showsName {
                Text(agent).font(Theme.Font.label.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
            }
            if let project {
                Text(project).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
            }
            Spacer(minLength: Theme.Space.xs)
            if let expiry, expiry > now {
                TimeLeftText(expiry: expiry, now: now, showsClock: showsName)
                    .font(Theme.Font.meta.weight(.medium))
                    .foregroundStyle(Theme.waiting)
                    .widgetAccentable()
                    .layoutPriority(1)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([agent, project, spokenTimeLeft(until: expiry, from: now)].compactMap { $0 }.joined(separator: ", "))
    }
}

/// The question and its answers, fitted to the room: two lines and full-size buttons if they fit,
/// then smaller buttons, then one line of question. The answers themselves are never cut short.
struct QuestionAndAnswers: View {
    let request: PendingRequest
    var fullSizeButtons = true
    var stretch = false
    /// A note at the end of the buttons, such as "+1 waiting".
    var trailing: String?
    var spacing: CGFloat = 9

    var body: some View {
        ViewThatFits(in: .vertical) {
            if fullSizeButtons { stack(lines: 2, compact: false) }
            stack(lines: 2, compact: true)
            stack(lines: 1, compact: true)
        }
    }

    private func stack(lines: Int, compact: Bool) -> some View {
        VStack(alignment: .leading, spacing: spacing) {
            Text(request.question)
                .font(Theme.Font.title)
                .foregroundStyle(Theme.text)
                .lineLimit(lines)
                .frame(maxWidth: .infinity, alignment: .leading)
            AnswerRow(request: request, compact: compact, stretch: stretch, trailing: trailing)
        }
    }
}

/// One tap answers. Questions that need typing, many options or several picks get one "Answer" link
/// to the question in the app instead. Buttons sit on one row when they fit and wrap to a second
/// when they don't; a label is never shortened.
struct AnswerRow: View {
    let request: PendingRequest
    var compact = false
    var stretch = false
    /// How many option buttons fit before it switches to the single "Answer" link.
    var maxButtons = 3
    /// A note after the buttons, such as "+1 waiting".
    var trailing: String?

    var body: some View {
        let actions = RequestActions.shared.actionsFor(kind: request.kind, options: request.options)
            .filter { !$0.isTextInput }
        let fits = !actions.isEmpty && actions.count <= maxButtons && actions.allSatisfy { $0.title.count <= 24 }
        if fits {
            let suggested = suggestedId(actions)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Theme.Space.s) {
                    buttons(actions, suggested: suggested, stretch: stretch)
                    if !stretch { Spacer(minLength: 0) }
                    note
                }
                FlowLayout(spacing: Theme.Space.s) {
                    buttons(actions, suggested: suggested, stretch: false)
                    note
                }
            }
        } else {
            HStack(spacing: Theme.Space.s) {
                Link(destination: DeepLink.open(path: focusPath)) {
                    HStack(spacing: 5) {
                        Image(systemName: "pencil").font(.system(size: compact ? 10 : 11, weight: .bold))
                        Text("Answer").fixedSize()
                    }
                    .cardButtonChrome(prominent: true, compact: compact, stretch: stretch)
                }
                .accessibilityLabel("Answer in Wildware Feed")
                if !stretch { Spacer(minLength: 0) }
                note
            }
        }
    }

    @ViewBuilder private var note: some View {
        if let trailing {
            Text(trailing).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).fixedSize().frame(minHeight: compact ? 28 : 34)
        }
    }

    private func buttons(_ actions: [NotificationAction], suggested: String?, stretch: Bool) -> some View {
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
                    // Whole words or nothing: if a row can't hold them all, the row wraps, not the label.
                    Text(action.title).fixedSize()
                }
            }
            .buttonStyle(CardButtonStyle(prominent: action.id == suggested, compact: compact, stretch: stretch))
            .accessibilityLabel("Answer \(action.title)")
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

extension EnvironmentValues {
    /// True when drawn to a picture (the screenshot tests): views that only tick on a real screen,
    /// like a timer-driven ring, draw a still frame instead of a placeholder.
    @Entry var drawsStill = false
}

/// An amber ring that drains as a question runs out of time, with a label inside.
struct CountdownRing<Label: View>: View {
    let expiry: Date
    let now: Date
    @ViewBuilder let label: Label
    @Environment(\.drawsStill) private var drawsStill

    var body: some View {
        if drawsStill {
            ZStack {
                Circle().stroke(Theme.waiting.opacity(0.25), lineWidth: 3)
                Circle().trim(from: 0, to: 0.7).stroke(Theme.waiting, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90))
                label
            }
            .padding(2)
        } else {
            ProgressView(timerInterval: now...expiry, countsDown: true) {
                EmptyView()
            } currentValueLabel: {
                label
            }
            .progressViewStyle(.circular)
            .tint(Theme.waiting)
        }
    }
}
