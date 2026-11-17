import Shared
import SwiftUI

/// Dictates a message and posts it: to a project's feed, or into one question's thread.
struct SayView: View {
    /// The question whose thread this replies in, or nil to post to a project.
    let request: PendingRequest?
    @EnvironmentObject private var model: WatchModel
    @Environment(\.dismiss) private var dismiss
    @State private var projectSlug: String?
    @State private var sending = false
    @State private var failure: String?

    private var projects: [ProjectRef] { model.summary?.projects ?? [] }

    var body: some View {
        List {
            if let request {
                Text(request.question)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            } else if projects.isEmpty {
                Text("No projects yet.")
                    .foregroundStyle(.secondary)
            } else {
                Picker("Project", selection: $projectSlug) {
                    ForEach(projects, id: \.slug) { project in
                        Text(project.name).tag(Optional(project.slug))
                    }
                }
            }

            TextFieldLink(prompt: Text("Say it")) {
                Label("Speak", systemImage: "mic.fill")
                    .foregroundStyle(.orange)
            } onSubmit: { text in
                send(text)
            }
            .disabled(sending || (request == nil && projectSlug == nil))

            if sending { ProgressView() }
            if let failure {
                Text(failure)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .navigationTitle(request == nil ? "Say something" : "Reply")
        .onAppear(perform: pickProject)
        .onChange(of: projects.map(\.slug)) { _, _ in pickProject() }
    }

    /// Starts on the project last posted to, or the first one.
    private func pickProject() {
        guard projectSlug == nil || !projects.contains(where: { $0.slug == projectSlug }) else { return }
        let last = CredentialStore.lastProject?.slug
        projectSlug = projects.first(where: { $0.slug == last })?.slug ?? projects.first?.slug
    }

    private func send(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        sending = true
        failure = nil
        Task {
            let project = projects.first(where: { $0.slug == projectSlug })
            let failed = await model.say(text, project: project, request: request)
            sending = false
            if let failed {
                failure = failed
            } else {
                dismiss()
            }
        }
    }
}
