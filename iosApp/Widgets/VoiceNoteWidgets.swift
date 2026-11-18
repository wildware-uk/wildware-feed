import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

/// A button for the lock screen, Control Center and the Action button that records a voice note without unlocking.
struct VoiceNoteControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "dev.wildware.agents.voice-note") {
            ControlWidgetButton(action: RecordVoiceNoteIntent()) {
                Label("Voice Note", systemImage: "mic.fill")
            }
        }
        .displayName("Voice Note")
        .description("Record a note for your agents. Press again, or tap Stop, to send it.")
    }
}

struct VoiceNoteLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: VoiceNoteActivityAttributes.self) { context in
            VoiceNoteStatusView(attributes: context.attributes, state: context.state)
                .padding(14)
                .activityBackgroundTint(Theme.surface)
                .activitySystemActionForegroundColor(Theme.text)
        } dynamicIsland: { context in
            let attributes = context.attributes
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.bottom) {
                    VoiceNoteStatusView(attributes: attributes, state: state).padding(.horizontal, 4)
                }
            } compactLeading: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            } compactTrailing: {
                if state.phase == .recording {
                    Text(attributes.startedAt, style: .timer)
                        .font(Theme.Font.fixedMono(14))
                        .foregroundStyle(state.tint)
                        .monospacedDigit()
                        .frame(maxWidth: 44)
                }
            } minimal: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            }
            .keylineTint(state.tint)
        }
    }
}

struct VoiceNoteStatusView: View {
    let attributes: VoiceNoteActivityAttributes
    let state: VoiceNoteActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: Theme.Space.m) {
            Image(systemName: state.symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(state.tint)
                .frame(width: 40, height: 40)
                .background(state.tint.opacity(0.16), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(attributes.headline(state))
                    .font(Theme.Font.bodyStrong.weight(.semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                switch state.phase {
                case .recording:
                    Text(attributes.startedAt, style: .timer)
                        .font(Theme.Font.mono(13, .medium, relativeTo: .subheadline))
                        .foregroundStyle(state.tint)
                        .monospacedDigit()
                case .sending:
                    Text("Writing down what you said").font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.textMuted)
                case .sent:
                    EmptyView()
                case .queued:
                    Text("No connection. It's saved on your phone.").font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.textMuted)
                case .failed:
                    Text(state.detail ?? "").font(Theme.Font.label.weight(.regular)).foregroundStyle(Theme.textMuted).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            if state.phase == .recording {
                Button(intent: SendVoiceNoteIntent()) {
                    HStack(spacing: 6) {
                        Image(systemName: "stop.fill").font(.system(size: 11, weight: .bold))
                        Text("Send")
                    }
                    .font(Theme.Font.bodyStrong)
                    .foregroundStyle(Color(hex: 0x1A0505))
                    .padding(.horizontal, 14)
                    .frame(height: 34)
                    .background(Theme.error, in: RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop and send the voice note")
            }
        }
    }
}

extension VoiceNoteActivityAttributes.ContentState {
    var symbol: String {
        switch phase {
        case .recording: "mic.fill"
        case .sending: "arrow.up"
        case .sent: "checkmark"
        case .queued: "clock.arrow.circlepath"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    var tint: Color {
        switch phase {
        case .recording: Theme.error
        case .sending: Theme.info
        case .sent: Theme.success
        case .queued: Theme.waiting
        case .failed: Theme.waiting
        }
    }
}
