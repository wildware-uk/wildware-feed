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
                "What are my \(.applicationName) doing",
                "\(.applicationName) status",
                "Check my \(.applicationName)",
            ],
            shortTitle: "Agent Status",
            systemImageName: "waveform"
        )
        AppShortcut(
            intent: AnswerLatestRequestIntent(),
            phrases: [
                "Answer my \(.applicationName)",
                "Reply to my \(.applicationName)",
                "What do my \(.applicationName) need",
            ],
            shortTitle: "Answer Agents",
            systemImageName: "questionmark.bubble"
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
    }
}
