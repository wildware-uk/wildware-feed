import AppIntents
import Shared
import WidgetKit

struct CreateTicketIntent: AppIntent {
    static let title: LocalizedStringResource = "Create a Ticket"
    static let description = IntentDescription("Puts a new card on a project's board for its agent to pick up.")

    @Parameter(title: "Project", requestValueDialog: "Which project is it for?")
    var project: ProjectEntity

    @Parameter(title: "What to do", requestValueDialog: "What should the ticket say?")
    var details: String

    static var parameterSummary: some ParameterSummary {
        Summary("Ticket for \(\.$project): \(\.$details)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        _ = try await client.createTicket(projectSlug: project.id, text: details)
        WidgetCenter.shared.reloadAllTimelines()
        return .result(dialog: "\(Speech.shared.ticketCreated(project: ProjectRef(slug: project.id, name: project.name)))")
    }
}

struct MessageAgentIntent: AppIntent {
    static let title: LocalizedStringResource = "Message an Agent"
    static let description = IntentDescription("Posts a message to a project's feed, where its agent hears it.")

    @Parameter(title: "Project", requestValueDialog: "Which project's agent?")
    var project: ProjectEntity

    @Parameter(title: "Message", requestValueDialog: "What should I tell it?")
    var message: String

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        _ = try await client.share(projectSlug: project.id, text: message, attachments: [])
        return .result(dialog: "Sent to \(project.name).")
    }
}

struct AgentStatusIntent: AppIntent {
    static let title: LocalizedStringResource = "What Are My Agents Doing"
    static let description = IntentDescription("Reads out who is online, what is in progress, and what is waiting on you.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        let summary = try await client.summary()
        return .result(dialog: "\(Speech.shared.status(summary: summary))")
    }
}

/// Reads the oldest question out and answers it with what is said back.
struct AnswerLatestRequestIntent: AppIntent {
    static let title: LocalizedStringResource = "Answer My Agents"
    static let description = IntentDescription("Reads the question that has waited longest and sends your spoken answer.")

    @Parameter(title: "Answer")
    var answer: String?

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        guard let request = try await client.summary().topRequest else {
            return .result(dialog: "Nobody is waiting on you.")
        }
        guard !["multi_choice", "form"].contains(request.kind.wireName) else {
            return .result(dialog: "\(request.agentName) needs an answer that won't fit in a sentence. Open the app to reply.")
        }
        let spoken: String
        if let answer {
            spoken = answer
        } else {
            spoken = try await $answer.requestValue("\(Speech.shared.request(request: request))")
        }
        guard try await client.answerSpoken(request: request, spoken: spoken).boolValue else {
            return .result(dialog: "I didn't catch which answer you meant, so I haven't sent anything.")
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result(dialog: "Sent. \(request.agentName) has its answer.")
    }
}

/// A button on a widget or the Live Activity that answers a request without opening the app.
struct AnswerRequestIntent: AppIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Answer a Request"
    static let isDiscoverable = false

    @Parameter(title: "Request") var requestId: String
    @Parameter(title: "Kind") var kind: String
    @Parameter(title: "Options") var options: [String]
    @Parameter(title: "Action") var actionId: String

    init() {}

    init(requestId: String, kind: String, options: [String], actionId: String) {
        self.requestId = requestId
        self.kind = kind
        self.options = options
        self.actionId = actionId
    }

    func perform() async throws -> some IntentResult {
        guard let client = Dashboard.client() else { throw DashboardError.notLinked }
        defer { client.close() }
        _ = try await client.answerFromAction(requestId: requestId, kind: kind, options: options, actionId: actionId, typedText: nil)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
