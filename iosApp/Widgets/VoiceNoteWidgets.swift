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
                .padding()
                .activityBackgroundTint(.dashboardBackground)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let attributes = context.attributes
            let state = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.bottom) {
                    VoiceNoteStatusView(attributes: attributes, state: state)
                }
            } compactLeading: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            } compactTrailing: {
                if state.phase == .recording {
                    Text(attributes.startedAt, style: .timer).monospacedDigit().frame(maxWidth: 44)
                }
            } minimal: {
                Image(systemName: state.symbol).foregroundStyle(state.tint)
            }
        }
    }
}

struct VoiceNoteStatusView: View {
    let attributes: VoiceNoteActivityAttributes
    let state: VoiceNoteActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: state.symbol)
                .font(.title2)
                .foregroundStyle(state.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(attributes.headline(state)).font(.headline).lineLimit(1)
                switch state.phase {
                case .recording:
                    Text(attributes.startedAt, style: .timer).font(.subheadline.monospacedDigit())
                case .sending:
                    Text("Writing down what you said").font(.subheadline).opacity(0.7)
                case .sent:
                    EmptyView()
                case .failed:
                    Text(state.detail ?? "").font(.subheadline).opacity(0.7).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            if state.phase == .recording {
                Button(intent: SendVoiceNoteIntent()) {
                    Label("Stop", systemImage: "stop.fill").bold()
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
        }
        .foregroundStyle(.white)
    }
}

extension VoiceNoteActivityAttributes.ContentState {
    var symbol: String {
        switch phase {
        case .recording: "mic.fill"
        case .sending: "arrow.up.circle"
        case .sent: "checkmark.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    var tint: Color {
        switch phase {
        case .recording: .red
        case .sending: .white
        case .sent: .green
        case .failed: .orange
        }
    }
}
