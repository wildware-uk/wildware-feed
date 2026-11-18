#if COMPOSE_APP
import ComposeApp
#else
import Shared
#endif
import SwiftUI

/// "What are my agents doing": the three numbers that matter, and the oldest question.
struct StatusSnippet: View {
    let waiting: Int
    let working: Int
    let online: Int
    let top: PendingRequest?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            HStack(alignment: .top) {
                stat(waiting, "Waiting", Theme.waiting)
                stat(working, "Working", Theme.text)
                stat(online, "Online", Theme.online)
            }
            if let top {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        AgentChip(name: top.agentName, agentId: top.agentId, size: 18)
                        Text(top.agentName).font(Theme.Font.label.weight(.semibold))
                        if let project = top.projectName {
                            Text(project).font(Theme.Font.meta).foregroundStyle(Theme.textMuted)
                        }
                    }
                    Text(top.question).font(Theme.Font.bodyStrong).lineLimit(3)
                }
                .foregroundStyle(Theme.text)
                .padding(Theme.Space.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.waitingSoft, in: RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous))
            }
        }
        .padding(Theme.Space.l)
    }

    private func stat(_ value: Int, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(Theme.Font.mono(28, .medium, relativeTo: .title)).foregroundStyle(color)
            Text(label).overline().foregroundStyle(Theme.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
