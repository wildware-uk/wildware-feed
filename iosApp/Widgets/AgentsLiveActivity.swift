import ActivityKit
import AppIntents
import Shared
import SwiftUI
import WidgetKit

/// The Live Activity: the app's waiting card, small. Amber while someone waits on the owner, with the
/// oldest question, a live countdown and its answer buttons; a calm panel of who is working otherwise.
struct AgentsLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentsActivityAttributes.self) { context in
            let state = context.state
            LockScreenView(state: state)
                .padding(14)
                .activityBackgroundTint(state.showsQuestion ? Theme.waitingSoft : Theme.surface)
                .activitySystemActionForegroundColor(Theme.text)
                .widgetURL(DeepLink.open(path: state.focusPath))
        } dynamicIsland: { context in
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    IslandLeading(state: state)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    IslandTrailing(state: state)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    IslandBottom(state: state)
                }
            } compactLeading: {
                CompactLeading(state: state)
            } compactTrailing: {
                CompactTrailing(state: state)
            } minimal: {
                MinimalView(state: state)
            }
            .widgetURL(DeepLink.open(path: state.focusPath))
            .keylineTint(state.showsQuestion ? Theme.waiting : Theme.robot)
        }
    }
}

extension AgentsActivityAttributes.ContentState {
    /// A question to show: waiting, and with its words.
    var showsQuestion: Bool { waiting > 0 && topQuestion != nil }

    var focusPath: String { topRequestId.map { "/?focus=\($0)" } ?? "/" }
}

// MARK: - Lock screen

/// The lock screen and banner presentation. Apple caps it at 160pt tall, which this always fits.
struct LockScreenView: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        Group {
            if let answer = state.sentAnswer, let question = state.topQuestion {
                SentCard(state: state, answer: answer, question: question)
            } else if state.showsQuestion, let request = state.topRequest {
                QuestionCard(state: state, request: request)
            } else {
                WorkingPanel(state: state)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct QuestionCard: View {
    let state: AgentsActivityAttributes.ContentState
    let request: PendingRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            AskerRow(
                agent: request.agentName,
                agentId: state.topAgentId,
                project: state.topProject,
                expiry: state.topExpiry,
                now: .now,
                live: true
            )
            Text(request.question)
                .font(Theme.Font.title)
                .foregroundStyle(Theme.text)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: Theme.Space.s) {
                AnswerRow(request: request)
                Spacer(minLength: 0)
                if let others = state.othersWaiting {
                    Text(others).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).fixedSize()
                }
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// Straight after a tap: the question settles to "Sent" before the server's next push lands.
private struct SentCard: View {
    let state: AgentsActivityAttributes.ContentState
    let answer: String
    let question: String

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: Theme.Space.s) {
                AgentChip(name: state.topAgent ?? "A", agentId: state.topAgentId, size: 20)
                Text(state.topAgent ?? "Agent").font(Theme.Font.label.weight(.semibold)).foregroundStyle(Theme.text)
                if let project = state.topProject {
                    Text(project).font(Theme.Font.meta).foregroundStyle(Theme.textMuted)
                }
                Spacer(minLength: 0)
                Label("Sent", systemImage: "checkmark")
                    .font(Theme.Font.meta.weight(.medium))
                    .foregroundStyle(Theme.success)
            }
            Text(question)
                .font(Theme.Font.title)
                .foregroundStyle(Theme.textMuted)
                .lineLimit(2)
            HStack {
                Label(answer, systemImage: "checkmark")
                    .font(Theme.Font.meta.weight(.medium))
                    .foregroundStyle(Theme.success)
                    .padding(.horizontal, 8)
                    .frame(height: 24)
                    .background(Theme.accentSoft, in: Capsule())
                Spacer(minLength: 0)
                Text(state.waiting > 1 ? "+\(state.waiting - 1) waiting" : "Nothing else waiting")
                    .font(Theme.Font.meta)
                    .foregroundStyle(Theme.textMuted)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Sent \(answer) to \(state.topAgent ?? "the agent")")
    }
}

/// Nobody waiting: what each agent is doing, or the counts when the server doesn't say.
private struct WorkingPanel: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: Theme.Space.s) {
                AppMarkTile(size: 20)
                Text(title).font(Theme.Font.label.weight(.semibold)).foregroundStyle(Theme.text)
                Spacer(minLength: 0)
                OnlineLabel(count: state.online)
            }
            if let working = state.working, !working.isEmpty {
                ForEach(Array(working.prefix(3).enumerated()), id: \.offset) { _, item in
                    WorkingRow(item: item)
                }
            } else {
                Text(state.online > 0 ? "Nothing is waiting on you." : "No agents are online.")
                    .font(Theme.Font.body)
                    .foregroundStyle(Theme.textMuted)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        switch state.inProgress {
        case 0: "All quiet"
        case 1: "1 task in progress"
        default: "\(state.inProgress) tasks in progress"
        }
    }
}

private struct WorkingRow: View {
    let item: AgentsActivityAttributes.ContentState.Working

    var body: some View {
        HStack(spacing: Theme.Space.s) {
            AgentChip(name: item.agent, agentId: item.agentId, size: 20)
            Text(item.task)
                .font(Theme.Font.label.weight(.regular))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
            Spacer(minLength: Theme.Space.xs)
            if let since = item.since {
                // Counts up on its own, so the server needn't push every minute.
                Text(Date(timeIntervalSince1970: TimeInterval(since)), style: .timer)
                    .font(Theme.Font.meta)
                    .foregroundStyle(Theme.textFaint)
                    .monospacedDigit()
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 56, alignment: .trailing)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(item.agent): \(item.task)")
    }
}

// MARK: - Dynamic Island

/// The robot, with an amber count of questions while any wait.
struct CompactLeading: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        RobotMark()
            .frame(width: 22, height: 22)
            .overlay(alignment: .topTrailing) {
                if state.waiting > 0 {
                    Text("\(state.waiting)")
                        .font(Theme.Font.fixedMono(10))
                        .foregroundStyle(Theme.onWaiting)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 14, minHeight: 14)
                        .background(Theme.waiting, in: Capsule())
                        .offset(x: 7, y: -4)
                }
            }
            .padding(.leading, 2)
            .accessibilityLabel(state.waiting > 0 ? "\(state.waiting) waiting on you" : "Wildware Feed")
    }
}

/// The oldest question's countdown, or how many are online.
struct CompactTrailing: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        if state.waiting > 0 {
            if let expiry = state.topExpiry, expiry > .now {
                Text(timerInterval: Date.now...expiry, countsDown: true, showsHours: false)
                    .font(Theme.Font.fixedMono(14))
                    .foregroundStyle(Theme.waiting)
                    .monospacedDigit()
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 46)
            } else {
                Text("\(state.waiting)")
                    .font(Theme.Font.fixedMono(14))
                    .foregroundStyle(Theme.waiting)
            }
        } else {
            HStack(spacing: 4) {
                StatusDot(color: Theme.online, size: 6)
                Text("\(state.online)").font(Theme.Font.fixedMono(14))
            }
            .foregroundStyle(Theme.online)
            .accessibilityLabel("\(state.online) online")
        }
    }
}

/// When another app shares the island: an amber ring that drains with the time left, or the robot.
struct MinimalView: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        if state.waiting > 0 {
            if let expiry = state.topExpiry, expiry > .now {
                ProgressView(timerInterval: Date.now...expiry, countsDown: true) {
                    EmptyView()
                } currentValueLabel: {
                    Text("\(state.waiting)").font(Theme.Font.fixedMono(12))
                }
                .progressViewStyle(.circular)
                .tint(Theme.waiting)
                .foregroundStyle(Theme.waiting)
                .accessibilityLabel("\(state.waiting) waiting on you")
            } else {
                Text("\(state.waiting)")
                    .font(Theme.Font.fixedMono(13))
                    .foregroundStyle(Theme.waiting)
                    .accessibilityLabel("\(state.waiting) waiting on you")
            }
        } else {
            RobotMark().frame(width: 20, height: 20).accessibilityLabel("Wildware Feed")
        }
    }
}

struct IslandLeading: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        if state.showsQuestion {
            HStack(spacing: Theme.Space.s) {
                AgentChip(name: state.topAgent ?? "A", agentId: state.topAgentId, size: 26)
                VStack(alignment: .leading, spacing: 0) {
                    Text(state.topAgent ?? "An agent").font(Theme.Font.label.weight(.semibold)).lineLimit(1)
                    if let project = state.topProject {
                        Text(project).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
                    }
                }
            }
            .padding(.leading, 4)
        } else {
            HStack(spacing: Theme.Space.s) {
                RobotMark().frame(width: 24, height: 24)
                Text(state.inProgress == 1 ? "1 in progress" : "\(state.inProgress) in progress")
                    .font(Theme.Font.label.weight(.semibold))
                    .lineLimit(1)
            }
            .padding(.leading, 4)
        }
    }
}

struct IslandTrailing: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        if state.showsQuestion {
            VStack(alignment: .trailing, spacing: 0) {
                if let expiry = state.topExpiry, expiry > .now {
                    Text(timerInterval: Date.now...expiry, countsDown: true, showsHours: true)
                        .font(Theme.Font.mono(17, .medium, relativeTo: .headline))
                        .foregroundStyle(Theme.waiting)
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                }
                Text(state.waiting > 1 ? "left · +\(state.waiting - 1) waiting" : "left")
                    .font(Theme.Font.meta)
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.textMuted)
            }
            .padding(.trailing, 4)
        } else {
            OnlineLabel(count: state.online).padding(.trailing, 4)
        }
    }
}

struct IslandBottom: View {
    let state: AgentsActivityAttributes.ContentState

    var body: some View {
        Group {
            if let answer = state.sentAnswer {
                Label("Sent: \(answer)", systemImage: "checkmark")
                    .font(Theme.Font.label)
                    .foregroundStyle(Theme.success)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if state.showsQuestion, let request = state.topRequest {
                VStack(alignment: .leading, spacing: 10) {
                    Text(request.question)
                        .font(Theme.Font.title)
                        .foregroundStyle(Theme.text)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    AnswerRow(request: request, stretch: true)
                }
            } else if let working = state.working, !working.isEmpty {
                VStack(spacing: 6) {
                    ForEach(Array(working.prefix(2).enumerated()), id: \.offset) { _, item in
                        WorkingRow(item: item)
                    }
                }
            } else {
                Text(state.online > 0 ? "Nothing is waiting on you." : "No agents are online.")
                    .font(Theme.Font.body)
                    .foregroundStyle(Theme.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 6)
    }
}
