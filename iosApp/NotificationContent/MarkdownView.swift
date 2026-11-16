import SwiftUI
import UIKit

/// One block of markdown (a paragraph, heading, list item, quote or code block) with its inline styling.
/// SwiftUI's `Text` styles inline markdown but lays every block out as one run of text, so blocks are split here.
struct MarkdownBlock: Identifiable {
    enum Kind { case paragraph, heading(level: Int), code }

    let id: Int
    let kind: Kind
    let text: AttributedString
    /// The bullet or number, on the first block of a list item only.
    let marker: String?
    let listDepth: Int
    let quoted: Bool

    static func parse(_ markdown: String) -> [MarkdownBlock] {
        let options = AttributedString.MarkdownParsingOptions(
            interpretedSyntax: .full,
            failurePolicy: .returnPartiallyParsedIfPossible
        )
        guard var parsed = try? AttributedString(markdown: markdown, options: options) else {
            return [MarkdownBlock(id: 0, kind: .paragraph, text: AttributedString(markdown), marker: nil, listDepth: 0, quoted: false)]
        }
        let inlineCode = parsed.runs[\.inlinePresentationIntent].compactMap { intent, range in
            intent?.contains(.code) == true ? range : nil
        }
        for range in inlineCode {
            parsed[range][AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute.self] = codeBackground
        }

        var blocks: [MarkdownBlock] = []
        var markedItems: Set<Int> = []
        for (intent, range) in parsed.runs[\.presentationIntent] {
            let components = intent?.components ?? []
            var kind = Kind.paragraph
            var marker: String?
            var listDepth = 0
            var quoted = false

            for (index, component) in components.enumerated() {
                switch component.kind {
                case .header(let level): kind = .heading(level: level)
                case .codeBlock: kind = .code
                case .blockQuote: quoted = true
                case .orderedList, .unorderedList: listDepth += 1
                case .listItem(let ordinal) where listDepth == 0:
                    if markedItems.insert(component.identity).inserted {
                        marker = components.dropFirst(index + 1).first?.kind == .orderedList ? "\(ordinal)." : "•"
                    }
                default: break
                }
            }

            var text = AttributedString(parsed[range])
            if case .code = kind {
                while text.characters.last == "\n" { text.characters.removeLast() }
            }
            blocks.append(MarkdownBlock(id: blocks.count, kind: kind, text: text, marker: marker, listDepth: listDepth, quoted: quoted))
        }
        return blocks
    }
}

private let codeBackground = Color(uiColor: .tertiarySystemFill)

struct MarkdownView: View {
    let blocks: [MarkdownBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(blocks) { BlockView(block: $0) }
        }
    }
}

private struct BlockView: View {
    private static let indent: CGFloat = 18
    let block: MarkdownBlock

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            if block.listDepth > 0 {
                Text(block.marker ?? "")
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .frame(minWidth: Self.indent, alignment: .trailing)
            }
            content
        }
        .padding(.leading, CGFloat(max(block.listDepth - 1, 0)) * Self.indent)
        .padding(.leading, block.quoted ? 12 : 0)
        .overlay(alignment: .leading) {
            if block.quoted {
                Capsule().fill(.tertiary).frame(width: 3)
            }
        }
        .foregroundStyle(block.quoted ? HierarchicalShapeStyle.secondary : .primary)
    }

    @ViewBuilder private var content: some View {
        switch block.kind {
        case .paragraph:
            Text(block.text)
        case .heading(let level):
            Text(block.text)
                .font(level == 1 ? Font.title3 : level == 2 ? Font.headline : Font.subheadline)
                .bold()
        case .code:
            Text(block.text)
                .font(.callout.monospaced())
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(codeBackground, in: RoundedRectangle(cornerRadius: 6))
        }
    }
}
