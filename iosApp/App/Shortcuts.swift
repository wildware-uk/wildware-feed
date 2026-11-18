import AppIntents

/// The phrases Siri listens for. Apple requires the app's name in each one.
struct AgentsShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: CreateTicketIntent(),
            phrases: [
                "Create a ticket in \(.applicationName)",
                "Create a \(.applicationName) ticket",
                "Create a ticket for \(\.$project) in \(.applicationName)",
                "New \(.applicationName) ticket for \(\.$project)",
            ],
            shortTitle: "Create Ticket",
            systemImageName: "plus.rectangle.on.rectangle"
        )
        AppShortcut(
            intent: AgentStatusIntent(),
            phrases: [
                "What are my agents doing in \(.applicationName)",
                "\(.applicationName) status",
                "Check \(.applicationName)",
            ],
            shortTitle: "Agent Status",
            systemImageName: "waveform"
        )
        AppShortcut(
            intent: AnswerLatestRequestIntent(),
            phrases: [
                "Answer my agents in \(.applicationName)",
                "Reply in \(.applicationName)",
                "What do my agents need in \(.applicationName)",
            ],
            shortTitle: "Answer Agents",
            systemImageName: "questionmark.bubble"
        )
        AppShortcut(
            intent: LatestNewsIntent(),
            phrases: [
                "What did \(\.$project) say in \(.applicationName)",
                "What's new on \(\.$project) in \(.applicationName)",
                "Latest from \(\.$project) in \(.applicationName)",
                "What did my agents say in \(.applicationName)",
            ],
            shortTitle: "Latest News",
            systemImageName: "text.bubble"
        )
        AppShortcut(
            intent: MessageAgentIntent(),
            phrases: [
                "Message \(\.$project) in \(.applicationName)",
                "Tell an agent in \(.applicationName)",
            ],
            shortTitle: "Message Agent",
            systemImageName: "bubble.left"
        )
        AppShortcut(
            intent: RecordVoiceNoteIntent(),
            phrases: [
                "Record a voice note in \(.applicationName)",
                "New \(.applicationName) voice note",
            ],
            shortTitle: "Voice Note",
            systemImageName: "mic"
        )
    }
}
