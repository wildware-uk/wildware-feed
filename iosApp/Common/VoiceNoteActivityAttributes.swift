import ActivityKit
import Foundation

/// The Live Activity iOS requires while a voice note records without the app open.
/// It lives on the phone alone: the server never pushes to it.
struct VoiceNoteActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        enum Phase: String, Codable { case recording, sending, sent, queued, failed }

        var phase: Phase
        /// Once sent or queued, the project it goes to; on failure, what went wrong.
        var detail: String?
    }

    var startedAt: Date
    var projectName: String?
}

extension VoiceNoteActivityAttributes {
    func headline(_ state: ContentState) -> String {
        switch state.phase {
        case .recording: projectName.map { "Recording · \($0)" } ?? "Recording a voice note"
        case .sending: "Sending your voice note…"
        case .sent: "Sent to \(state.detail ?? projectName ?? "your agents")"
        case .queued: "Will send to \(state.detail ?? projectName ?? "your agents") when there's signal"
        case .failed: "Your voice note didn't send"
        }
    }
}
