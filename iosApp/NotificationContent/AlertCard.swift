import LocalAuthentication
import Shared
import SwiftUI
import UIKit
import UserNotifications

/// What the long-press view shows, filled in as the dashboard answers.
@MainActor
final class AlertModel: ObservableObject {
    let payload: AlertPayload
    @Published var details: AlertDetails?
    /// Pictures that came with the alert, by attachment identifier ("option-0", "media").
    @Published var pictures: [String: UIImage]
    @Published var answer: AnswerState = .idle

    var openApp: () -> Void = {}
    var openLink: (URL) -> Void = { _ in }
    /// Closes the long-press view, once an answer has gone.
    var close: () -> Void = {}
    /// Tells the controller the card changed height.
    var resized: () -> Void = {}

    enum AnswerState: Equatable {
        case idle
        case sending(String)
        case sent(String)
        /// Tapped with no signal: it waits in the outbox.
        case queued(String)
        case failed(String)
    }

    init(payload: AlertPayload, details: AlertDetails? = nil, pictures: [String: UIImage] = [:]) {
        self.payload = payload
        self.details = details
        self.pictures = pictures
    }

    var agentName: String? { details?.agentName ?? payload.agentNameFromTitle }

    /// Answers with one of the question's buttons, after Face ID or the passcode, as the alert's menu does.
    func answer(actionId: String, title: String) {
        guard case .idle = answer, let requestId = payload.requestId, let kind = payload.requestKind else { return }
        Task {
            guard await confirmIdentity() else { return }
            set(.sending(title))
            let queued = OutboxQueue.enqueueAnswer(
                agentName: agentName ?? payload.title,
                requestId: requestId,
                kind: kind,
                options: payload.options ?? details?.request?.options,
                actionId: actionId,
                typedText: nil
            )
            switch await OutboxQueue.flush(only: queued) {
            case .sent?:
                set(.sent(title))
                try? await Task.sleep(for: .seconds(1))
                close()
            case .refused?:
                set(.failed("The dashboard refused that answer. Open Wildware Feed to see why."))
            case .waiting?, nil:
                set(.queued(title))
            }
        }
    }

    private func set(_ state: AnswerState) {
        withAnimation(Theme.motion(reduced: UIAccessibility.isReduceMotionEnabled)) { answer = state }
        resized()
    }

    private func confirmIdentity() async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No passcode on this phone: nothing to check against.
            return true
        }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Answer \(agentName ?? "the agent")")) ?? false
    }
}

/// The long-press view: the same card the app shows for this alert.
struct AlertCard: View {
    @ObservedObject var model: AlertModel

    var body: some View {
        // No ScrollView: its ideal height is not its content's, so the alert would size to nothing.
        // Anything past the height cap is cut off; a tap opens the full card in the app.
        Group {
            switch model.payload.kind {
            case .request: RequestCard(model: model)
            case .message: MessageCard(model: model)
            case .update: UpdateCard(model: model)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.l)
        .background(model.payload.kind == .request ? Theme.waitingSoft : Theme.surface)
        .contentShape(Rectangle())
        .onTapGesture(perform: model.openApp)
        .environment(\.openURL, OpenURLAction { url in
            model.openLink(url)
            return .handled
        })
    }
}

// MARK: - Question

private struct RequestCard: View {
    @ObservedObject var model: AlertModel

    private var request: PendingRequest? { model.details?.request }
    private var options: [String] { model.payload.options ?? request?.options ?? [] }
    private var question: String { request?.question ?? model.payload.markdown ?? model.payload.body }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            CardHeader(
                name: model.agentName,
                agentId: model.details?.agentId,
                trailingProject: model.details?.projectName ?? model.payload.projectSlug
            ) {
                if let expiry = request?.expiry, expiry > .now {
                    TimeLeftText(expiry: expiry, now: .now)
                        .font(Theme.Font.meta.weight(.medium))
                        .foregroundStyle(Theme.waiting)
                }
            }
            Text(inlineMarkdown(question))
                .font(Theme.Font.sans(17, .semibold, relativeTo: .headline))
                .foregroundStyle(Theme.text)
                .fixedSize(horizontal: false, vertical: true)
            if let detail = request?.detail, !detail.isEmpty {
                MarkdownView(blocks: MarkdownBlock.parse(detail))
            }
            if model.details?.requestIsGone == true {
                Label("Already answered, or it timed out", systemImage: "checkmark.circle")
                    .font(Theme.Font.label)
                    .foregroundStyle(Theme.textMuted)
            } else {
                answers
            }
            footer
        }
    }

    @ViewBuilder private var answers: some View {
        switch model.answer {
        case let .sending(title):
            HStack(spacing: Theme.Space.s) {
                ProgressView().tint(Theme.waiting)
                Text("Sending “\(title)”…").font(Theme.Font.label).foregroundStyle(Theme.textMuted)
            }
        case let .sent(title):
            Label("Sent: \(title)", systemImage: "checkmark")
                .font(Theme.Font.label)
                .foregroundStyle(Theme.success)
                .padding(.horizontal, 10)
                .frame(height: 30)
                .background(Theme.accentSoft, in: Capsule())
        case let .queued(title):
            Label("“\(title)” will send when you're back online", systemImage: "clock.arrow.circlepath")
                .font(Theme.Font.label)
                .foregroundStyle(Theme.waiting)
        case let .failed(message):
            Label(message, systemImage: "exclamationmark.triangle")
                .font(Theme.Font.label)
                .foregroundStyle(Theme.error)
        case .idle:
            buttons
        }
    }

    @ViewBuilder private var buttons: some View {
        let kind = model.payload.requestKind.flatMap { RequestKind.companion.fromWire(value: $0) }
        let actions = kind.map { RequestActions.shared.allActionsFor(kind: $0, options: options) } ?? []
        let tappable = actions.filter { !$0.isTextInput }
        let suggested = suggestedId(tappable)
        if !tappable.isEmpty, hasPictures {
            PictureTiles(model: model, options: options, actions: tappable, suggested: suggested)
        } else if !tappable.isEmpty {
            FlowLayout(spacing: Theme.Space.s) {
                ForEach(tappable, id: \.id) { action in
                    Button { model.answer(actionId: action.id, title: action.title) } label: {
                        HStack(spacing: 6) {
                            if action.id == suggested { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)) }
                            Text(action.title)
                        }
                    }
                    .buttonStyle(CardButtonStyle(prominent: action.id == suggested))
                    .accessibilityLabel("Answer \(action.title)")
                }
            }
        } else if actions.contains(where: \.isTextInput) {
            Label("Hold on, then choose Reply… below to answer", systemImage: "arrow.down")
                .font(Theme.Font.label)
                .foregroundStyle(Theme.textMuted)
        } else {
            Button(action: model.openApp) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up.forward.app").font(.system(size: 12, weight: .bold))
                    Text("Answer in Wildware Feed")
                }
            }
            .buttonStyle(CardButtonStyle(prominent: true))
        }
    }

    private var hasPictures: Bool {
        options.indices.contains { model.pictures["option-\($0)"] != nil }
    }

    private var footer: some View {
        let waiting = model.details?.waiting ?? 0
        return HStack {
            if hasPictures, case .idle = model.answer {
                Text("Tap a picture to answer")
            }
            Spacer(minLength: 0)
            if waiting > 1 {
                Text("+\(waiting - 1) waiting")
            }
        }
        .font(Theme.Font.meta)
        .foregroundStyle(Theme.textFaint)
    }

    /// The agent's suggested answer, else the first.
    private func suggestedId(_ actions: [NotificationAction]) -> String? {
        if let label = request?.defaultLabel, let match = actions.first(where: { $0.title == label }) { return match.id }
        return actions.first?.id
    }
}

/// Picture questions: each option as its picture, tapped to answer.
private struct PictureTiles: View {
    @ObservedObject var model: AlertModel
    let options: [String]
    let actions: [NotificationAction]
    let suggested: String?

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: Theme.Space.s), count: actions.count == 2 || actions.count == 4 ? 2 : 3)
        LazyVGrid(columns: columns, spacing: Theme.Space.s) {
            ForEach(Array(actions.enumerated()), id: \.element.id) { index, action in
                let isSuggested = action.id == suggested
                Button { model.answer(actionId: action.id, title: action.title) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Color.clear
                            .aspectRatio(3 / 4, contentMode: .fit)
                            .overlay {
                                if let picture = model.pictures["option-\(index)"] {
                                    Image(uiImage: picture).resizable().scaledToFill()
                                } else {
                                    Theme.sunken.overlay(Image(systemName: "photo").foregroundStyle(Theme.textFaint))
                                }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
                        HStack(spacing: 4) {
                            if isSuggested { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.waiting) }
                            Text(action.title).fixedSize(horizontal: false, vertical: true)
                        }
                        .font(Theme.Font.label)
                        .foregroundStyle(Theme.text)
                        .padding(.horizontal, 3)
                    }
                    .padding(5)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: Theme.Radius.large, style: .continuous)
                            .strokeBorder(isSuggested ? Theme.waiting : Theme.borderStrong, lineWidth: isSuggested ? 2 : 1)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Answer \(action.title)")
                .accessibilityHint("Picture option")
            }
        }
    }
}

// MARK: - Reply and update

private struct MessageCard: View {
    @ObservedObject var model: AlertModel

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            CardHeader(name: model.agentName, agentId: model.details?.agentId, title: model.payload.title, trailingProject: nil) {
                Text(meta(project: model.payload.projectSlug, date: model.payload.date))
                    .font(Theme.Font.meta)
                    .foregroundStyle(Theme.textMuted)
            }
            if let quote = model.details?.quote {
                VStack(alignment: .leading, spacing: 3) {
                    Text("You · \(quote.date.formatted(.relative(presentation: .named)))").overline().foregroundStyle(Theme.textFaint)
                    Text(inlineMarkdown(quote.text))
                        .font(Theme.Font.label.weight(.regular))
                        .foregroundStyle(Theme.textMuted)
                        .lineLimit(4)
                }
                .padding(Theme.Space.s + 2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.sunken, in: RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
                .overlay(alignment: .leading) { Rectangle().fill(Theme.borderStrong).frame(width: 2).padding(.vertical, 6) }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Replying to you: \(quote.text)")
            }
            CardBody(markdown: model.payload.markdown, plain: model.payload.body)
            Picture(image: model.pictures["media"])
        }
    }
}

private struct UpdateCard: View {
    @ObservedObject var model: AlertModel

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            CardHeader(name: model.agentName, agentId: model.details?.agentId, trailingProject: model.payload.projectSlug) {
                Text(model.payload.date.formatted(.relative(presentation: .named)))
                    .font(Theme.Font.meta)
                    .foregroundStyle(Theme.textMuted)
            }
            if !model.payload.title.isEmpty {
                Text(model.payload.title)
                    .font(Theme.Font.sans(17, .semibold, relativeTo: .headline))
                    .foregroundStyle(Theme.text)
                    .accessibilityAddTraits(.isHeader)
            }
            CardBody(markdown: model.payload.markdown, plain: model.payload.body)
            Picture(image: model.pictures["media"])
        }
    }
}

// MARK: - Pieces

/// Who, where, and something on the right: time left, or when.
private struct CardHeader<Trailing: View>: View {
    let name: String?
    let agentId: String?
    var title: String?
    let trailingProject: String?
    @ViewBuilder let trailing: Trailing

    var body: some View {
        // One line when it fits; with big text or a long name, the time goes on a line of its own.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Theme.Space.s) {
                who.fixedSize()
                Spacer(minLength: Theme.Space.xs)
                trailing.fixedSize()
            }
            VStack(alignment: .leading, spacing: Theme.Space.xs) {
                who
                trailing.fixedSize()
            }
        }
    }

    private var who: some View {
        HStack(spacing: Theme.Space.s) {
            if let name {
                AgentChip(name: name, agentId: agentId)
            } else {
                AppMarkTile(size: 22)
            }
            Text(title ?? name ?? "Wildware Feed")
                .font(Theme.Font.label.weight(.semibold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
            if let trailingProject {
                Text(trailingProject).font(Theme.Font.meta).foregroundStyle(Theme.textMuted).lineLimit(1)
            }
        }
    }
}

private struct CardBody: View {
    let markdown: String?
    let plain: String

    var body: some View {
        if let markdown, !markdown.isEmpty {
            MarkdownView(blocks: MarkdownBlock.parse(markdown))
        } else {
            Text(plain).font(Theme.Font.body).foregroundStyle(Theme.text)
        }
    }
}

private struct Picture: View {
    let image: UIImage?

    var body: some View {
        if let image {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, maxHeight: 200)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.medium, style: .continuous))
                .accessibilityLabel("Attached picture")
        }
    }
}

private func meta(project: String?, date: Date) -> String {
    [project, date.formatted(.relative(presentation: .named))].compactMap { $0 }.joined(separator: " · ")
}

/// Bold, italics, code and links inside one line, as the app's titles allow.
func inlineMarkdown(_ text: String) -> AttributedString {
    let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace, failurePolicy: .returnPartiallyParsedIfPossible)
    return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
}
