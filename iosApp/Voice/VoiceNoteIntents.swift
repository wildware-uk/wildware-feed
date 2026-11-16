import AppIntents

// Both intents are Live Activity intents as well, so iOS runs them in the app, which holds the
// microphone, even though the lock screen control and the Stop button live in the widget extension.

/// Records a voice note without unlocking: from the lock screen or Control Center, the Action button, or Siri.
/// Running it again while it records sends the note.
struct RecordVoiceNoteIntent: AudioRecordingIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Record a Voice Note"
    static let description = IntentDescription(
        "Records you, writes down what you said, and posts both to the project you last sent a voice note to."
    )

    func perform() async throws -> some IntentResult {
        try await VoiceNotes.shared.toggleFromIntent()
        return .result()
    }
}

/// The Live Activity's Stop button: ends the recording and sends it.
struct SendVoiceNoteIntent: AudioRecordingIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Send the Voice Note"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        try await VoiceNotes.shared.finish()
        return .result()
    }
}
