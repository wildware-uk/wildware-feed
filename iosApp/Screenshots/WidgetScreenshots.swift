import CoreText
import Shared
import SwiftUI
import WidgetKit
import XCTest

/// Draws the real widget, Live Activity, alert and Siri views with sample data and saves them as PNGs,
/// in dark and light. A home screen can't be scripted, so these are the views on a stand-in wallpaper,
/// not the system's own frame.
@MainActor
final class WidgetScreenshots: XCTestCase {
    override class func setUp() {
        super.setUp()
        Fonts.register()
    }

    private let snapshot = StatusSnapshot.sample
    private var quiet: StatusSnapshot {
        var quiet = StatusSnapshot.sample
        quiet.requests = []
        return quiet
    }

    private let waiting = AgentsActivityAttributes.ContentState(
        online: 3,
        waiting: 2,
        inProgress: 4,
        topAgent: "Rover",
        topQuestion: "Tests pass. Merge the fee-rounding branch into main?",
        topRequestId: "req-1",
        topKind: "confirm",
        topAgentId: "a-rover",
        topProject: "garden-game",
        topExpiresAt: Int(Date.now.addingTimeInterval(41 * 60 + 52).timeIntervalSince1970),
        topDefault: "true"
    )
    private let quietState = AgentsActivityAttributes.ContentState(
        online: 3, waiting: 0, inProgress: 4, topAgent: nil, topQuestion: nil, topRequestId: nil, topKind: nil
    )

    // MARK: Home screen

    func testStatusWidget() throws {
        for scheme in Scheme.allCases {
            try save("widget-small-\(scheme)", status(.systemSmall, snapshot), scheme: scheme, width: 158, height: 158)
            try save("widget-small-quiet-\(scheme)", status(.systemSmall, quiet), scheme: scheme, width: 158, height: 158)
            try save("widget-medium-\(scheme)", status(.systemMedium, snapshot), scheme: scheme, width: 338, height: 158)
            try save("widget-medium-quiet-\(scheme)", status(.systemMedium, quiet), scheme: scheme, width: 338, height: 158)
            try save("widget-large-\(scheme)", status(.systemLarge, snapshot), scheme: scheme, width: 338, height: 354)
        }
    }

    func testProjectWidget() throws {
        for scheme in Scheme.allCases {
            try save("project-small-\(scheme)", project(.systemSmall), scheme: scheme, width: 158, height: 158)
            try save("project-medium-\(scheme)", project(.systemMedium), scheme: scheme, width: 338, height: 158)
            try save("project-large-\(scheme)", project(.systemLarge), scheme: scheme, width: 338, height: 354)
        }
        // StandBy: no card behind it, on a black screen across the room.
        let standBy = ProjectWidgetContent(entry: projectEntry(.small), family: .systemSmall, showsBackground: false)
            .padding(16)
            .background(.black)
        try save("project-standby", standBy, scheme: .dark, width: 170, height: 170)
    }

    // MARK: Lock screen

    func testLockScreenWidgets() throws {
        try save("lock-rectangular", accessory(.accessoryRectangular, width: 172, height: 76), scheme: .dark)
        try save("lock-circular", accessory(.accessoryCircular, width: 76, height: 76), scheme: .dark)
        try save("lock-inline", accessory(.accessoryInline, width: 260, height: 26), scheme: .dark)
        let projectLock = ProjectWidgetContent(entry: projectEntry(.accessory), family: .accessoryRectangular, showsBackground: false)
            .foregroundStyle(.white)
            .frame(width: 172, height: 76)
        try save("project-lock-rectangular", projectLock, scheme: .dark)
    }

    // MARK: Alerts, Live Activities, Siri

    func testAlerts() throws {
        for scheme in Scheme.allCases {
            try saveAlert("alert-question-\(scheme)", Samples.confirm(), scheme: scheme)
            try saveAlert("alert-pictures-\(scheme)", Samples.pictures(), scheme: scheme)
            try saveAlert("alert-reply-\(scheme)", Samples.reply(), scheme: scheme)
            try saveAlert("alert-update-\(scheme)", Samples.update(), scheme: scheme)
        }
        let sent = Samples.confirm()
        sent.answer = .sent("Yes")
        try saveAlert("alert-question-sent", sent, scheme: .dark)
    }

    private func saveAlert(_ name: String, _ model: AlertModel, scheme: Scheme) throws {
        let card = AlertCard(model: model)
        let fitted = UIHostingController(rootView: card).sizeThatFits(in: CGSize(width: 361, height: CGFloat.greatestFiniteMagnitude))
        XCTAssertGreaterThan(fitted.height, 100, "\(name) would have no height")
        let platter = card.clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        try save(name, platter, scheme: scheme, width: 361)
    }

    func testLiveActivity() throws {
        let soon = Int(Date.now.addingTimeInterval(41 * 60 + 52).timeIntervalSince1970)
        var choice = waiting
        choice.topAgent = "Lumen"
        choice.topAgentId = "a-lumen"
        choice.topProject = "harbor-app"
        choice.topQuestion = "The build is green. What next?"
        choice.topKind = "choice"
        choice.topOptions = ["Ship it", "Keep testing", "Not now"]
        choice.topDefault = nil
        choice.topExpiresAt = soon + 3600
        var text = waiting
        text.topAgent = "Pixel"
        text.topAgentId = "a-pixel"
        text.topQuestion = "What should the release notes call the new seed shop?"
        text.topKind = "text"
        text.waiting = 3
        var sent = waiting
        sent.sentAnswer = "Yes"
        sent.waiting = 1
        var working = quietState
        working.working = Self.working
        var old = waiting
        // A server from before the new fields: only the first seven keys.
        old.topAgentId = nil; old.topProject = nil; old.topExpiresAt = nil; old.topOptions = nil; old.topDefault = nil

        let states: [(String, AgentsActivityAttributes.ContentState)] = [
            ("question", waiting), ("choice", choice), ("text", text), ("sent", sent),
            ("working", working), ("quiet", quietState), ("old-server", old),
        ]
        for scheme in Scheme.allCases {
            for (name, state) in states {
                let card = LockScreenView(state: state)
                    .padding(14)
                    .frame(width: 361, alignment: .leading)
                    .background(state.showsQuestion && state.sentAnswer == nil ? Theme.waitingSoft : Theme.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                let fitted = UIHostingController(rootView: card).sizeThatFits(in: CGSize(width: 361, height: 1000))
                XCTAssertLessThanOrEqual(fitted.height, 160, "live-activity-\(name) is taller than Apple's 160pt")
                try save("live-activity-\(name)-\(scheme)", card, scheme: scheme)
            }
        }
    }

    func testDynamicIsland() throws {
        var working = quietState
        working.working = Self.working
        for (name, state) in [("waiting", waiting), ("working", working)] {
            let compact = HStack {
                CompactLeading(state: state)
                Spacer()
                CompactTrailing(state: state)
            }
            .padding(.horizontal, 12)
            .frame(width: 250, height: 37)
            .background(.black, in: Capsule())
            try save("island-compact-\(name)", compact, scheme: .dark)

            let minimal = MinimalView(state: state)
                .frame(width: 37, height: 37)
                .background(.black, in: Circle())
            try save("island-minimal-\(name)", minimal, scheme: .dark)

            let expanded = VStack(spacing: 0) {
                HStack(alignment: .top) {
                    IslandLeading(state: state)
                    Spacer()
                    IslandTrailing(state: state)
                }
                IslandBottom(state: state)
            }
            .padding(EdgeInsets(top: 16, leading: 18, bottom: 18, trailing: 18))
            .frame(width: 371)
            .background(.black, in: RoundedRectangle(cornerRadius: 44, style: .continuous))
            try save("island-expanded-\(name)", expanded, scheme: .dark)
        }
    }

    /// The biggest text sizes people pick: everything must still read, and nothing important may be cut.
    func testLargeText() throws {
        let big = DynamicTypeSize.xxxLarge
        try save("large-widget-small", status(.systemSmall, snapshot), scheme: .dark, width: 158, height: 158, textSize: big)
        try save("large-widget-medium", status(.systemMedium, snapshot), scheme: .dark, width: 338, height: 158, textSize: big)
        try save("large-project-medium", project(.systemMedium), scheme: .dark, width: 338, height: 158, textSize: big)
        var working = quietState
        working.working = Self.working
        for (name, state) in [("question", waiting), ("working", working)] {
            let card = LockScreenView(state: state)
                .padding(14)
                .frame(width: 361, alignment: .leading)
                .frame(maxHeight: 160, alignment: .top)
                .background(state.showsQuestion ? Theme.waitingSoft : Theme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            try save("large-live-activity-\(name)", card, scheme: .dark, textSize: big)
        }
        let huge = DynamicTypeSize.accessibility2
        for (name, model) in [("question", Samples.confirm()), ("pictures", Samples.pictures()), ("reply", Samples.reply())] {
            let card = AlertCard(model: model).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            try save("large-alert-\(name)", card, scheme: .dark, width: 361, textSize: huge)
        }
        let siri = StatusSnippet(waiting: 2, working: 4, online: 3, top: snapshot.top)
            .background(Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        try save("large-siri-status", siri, scheme: .dark, width: 361, textSize: huge)
    }

    private static let working: [AgentsActivityAttributes.ContentState.Working] = [
        .init(agentId: "a-rover", agent: "Rover", task: "Fixing fee rounding at checkout", since: Int(Date.now.timeIntervalSince1970) - 12 * 60),
        .init(agentId: "a-lumen", agent: "Lumen", task: "Porting the share sheet to Compose", since: Int(Date.now.timeIntervalSince1970) - 40 * 60),
        .init(agentId: "a-pixel", agent: "Pixel", task: "Drawing the seed shop icons", since: Int(Date.now.timeIntervalSince1970) - 3 * 60),
    ]

    func testVoiceNoteLiveActivity() throws {
        let note = VoiceNoteActivityAttributes(startedAt: .now.addingTimeInterval(-12), projectName: "garden-game")
        try save("voice-note-recording", liveActivity(VoiceNoteStatusView(attributes: note, state: .init(phase: .recording))), scheme: .dark)
        try save("voice-note-sent", liveActivity(VoiceNoteStatusView(attributes: note, state: .init(phase: .sent, detail: "garden-game"))), scheme: .dark)
    }

    func testSiriSnippets() throws {
        for scheme in Scheme.allCases {
            let card = StatusSnippet(waiting: 2, working: 4, online: 3, top: snapshot.top)
                .background(Theme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            try save("siri-status-\(scheme)", card, scheme: scheme, width: 361)
        }
    }

    // MARK: Drawing

    private func status(_ family: WidgetFamily, _ snapshot: StatusSnapshot) -> some View {
        widgetCard(StatusWidgetContent(entry: StatusEntry(date: .now, state: .ready(snapshot)), family: family), waiting: snapshot.waiting > 0)
    }

    private func projectEntry(_ size: CardSize) -> ProjectEntry {
        ProjectEntry(date: .now, state: .ready(ProjectCard.sample(size: size)))
    }

    private func project(_ family: WidgetFamily) -> some View {
        widgetCard(ProjectWidgetContent(entry: projectEntry(CardSize.of(family)), family: family, showsBackground: true), waiting: false)
    }

    /// The widget's content on its card, as WidgetKit lays it out: 16pt in from a 22pt-cornered card.
    private func widgetCard(_ content: some View, waiting: Bool) -> some View {
        content
            .padding(16)
            .background(waiting ? Theme.waitingSoft : Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func accessory(_ family: WidgetFamily, width: CGFloat, height: CGFloat) -> some View {
        StatusWidgetContent(entry: StatusEntry(date: .now, state: .ready(snapshot)), family: family)
            .foregroundStyle(.white)
            .frame(width: width, height: height)
    }

    private func liveActivity(_ content: some View) -> some View {
        content
            .padding()
            .frame(width: 361, alignment: .leading)
            .background(Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    enum Scheme: String, CaseIterable, CustomStringConvertible {
        case dark, light
        var description: String { rawValue }
        var colorScheme: ColorScheme { self == .dark ? .dark : .light }
        var wallpaper: [Color] {
            self == .dark ? [Color(hex: 0x2B3B63), Color(hex: 0x07080C)] : [Color(hex: 0xD8E6FF), Color(hex: 0x8EA3C9)]
        }
    }

    private func save(
        _ name: String, _ view: some View, scheme: Scheme, width: CGFloat? = nil, height: CGFloat? = nil,
        textSize: DynamicTypeSize = .large
    ) throws {
        let framed = view
            .frame(width: width, height: height)
            .padding(28)
            .background(LinearGradient(colors: scheme.wallpaper, startPoint: .topLeading, endPoint: .bottomTrailing))
            .environment(\.colorScheme, scheme.colorScheme)
            .environment(\.dynamicTypeSize, textSize)
        let renderer = ImageRenderer(content: framed)
        renderer.scale = 3
        let png = try XCTUnwrap(renderer.uiImage?.pngData(), "\(name) drew nothing")
        try png.write(to: ScreenshotFolder.url.appendingPathComponent("\(name).png"))
    }
}

/// Made-up alerts for the long-press screenshots.
@MainActor
enum Samples {
    static func confirm() -> AlertModel {
        let payload = AlertPayload(
            kind: .request, title: "Rover is waiting on you", body: "Tests pass. Merge the fee-rounding branch into main?",
            markdown: "Tests pass. Merge the fee-rounding branch into main?", projectSlug: "garden-game",
            requestId: "req-1", requestKind: "confirm"
        )
        var details = AlertDetails()
        details.agentName = "Rover"
        details.agentId = "a-rover"
        details.projectName = "garden-game"
        details.waiting = 2
        details.request = PendingRequest(
            id: "req-1", agentName: "Rover", projectSlug: "garden-game", projectName: "garden-game",
            question: payload.body, kind: RequestKind.companion.fromWire(value: "confirm")!, options: nil,
            expiresAt: ISO8601DateFormatter().string(from: .now.addingTimeInterval(41 * 60 + 52)),
            detail: "412 tests pass. The only change outside checkout is one helper:\n\n```\nfun roundFee(p: Money) = p.round(2)\n```",
            defaultAnswer: "true"
        )
        return AlertModel(payload: payload, details: details)
    }

    static func pictures() -> AlertModel {
        let options = ["Grid", "Cards", "List"]
        let payload = AlertPayload(
            kind: .request, title: "Lumen is waiting on you", body: "Which home screen should ship?",
            markdown: "Which home screen should ship?", projectSlug: "harbor-app",
            requestId: "req-2", requestKind: "choice", options: options
        )
        var details = AlertDetails()
        details.agentName = "Lumen"
        details.agentId = "a-lumen"
        details.projectName = "harbor-app"
        details.waiting = 2
        details.request = PendingRequest(
            id: "req-2", agentName: "Lumen", projectSlug: "harbor-app", projectName: "harbor-app",
            question: payload.body, kind: RequestKind.companion.fromWire(value: "choice")!, options: options,
            expiresAt: ISO8601DateFormatter().string(from: .now.addingTimeInterval(42 * 60)),
            detail: "All three pass the contrast check. **Cards** tested best with the five people who tried it.",
            defaultAnswer: "Cards"
        )
        var pictures: [String: UIImage] = [:]
        for (index, hue) in [0.58, 0.42, 0.06].enumerated() { pictures["option-\(index)"] = picture(hue: hue) }
        return AlertModel(payload: payload, details: details, pictures: pictures)
    }

    static func reply() -> AlertModel {
        let payload = AlertPayload(
            kind: .message, title: "Pixel replied to you",
            body: "Done. I shifted the greens towards yellow and gave the pots a terracotta rim.",
            markdown: "Done. I shifted the greens towards yellow and gave the pots a **terracotta** rim. Before and after:",
            projectSlug: "garden-game", reply: ["answers": "m-2"], date: .now.addingTimeInterval(-60)
        )
        var details = AlertDetails()
        details.agentName = "Pixel"
        details.agentId = "a-pixel"
        details.quote = .init(text: "Can the seed shop icons be warmer? They read a bit cold on the dark theme.", date: .now.addingTimeInterval(-12 * 60))
        return AlertModel(payload: payload, details: details, pictures: ["media": picture(hue: 0.09)])
    }

    static func update() -> AlertModel {
        let payload = AlertPayload(
            kind: .update, title: "Fee rounding fixed",
            body: "Checkout now rounds fees to the penny.",
            markdown: "Checkout now rounds fees to the penny. All **412** tests pass and a pull request is open.\n\n```\n- total = fee * qty\n+ total = roundFee(fee * qty)\n```",
            projectSlug: "garden-game", reply: ["update": "u-1"], date: .now.addingTimeInterval(-120)
        )
        var details = AlertDetails()
        details.agentName = "Rover"
        details.agentId = "a-rover"
        return AlertModel(payload: payload, details: details)
    }

    /// A stand-in picture: a soft gradient with a couple of bars, like a screenshot of an app.
    static func picture(hue: Double) -> UIImage {
        let size = CGSize(width: 300, height: 400)
        return UIGraphicsImageRenderer(size: size).image { context in
            let colors = [UIColor(hue: hue, saturation: 0.55, brightness: 0.85, alpha: 1).cgColor,
                          UIColor(hue: hue + 0.1, saturation: 0.5, brightness: 0.4, alpha: 1).cgColor]
            let gradient = CGGradient(colorsSpace: nil, colors: colors as CFArray, locations: nil)!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            UIColor.white.withAlphaComponent(0.5).setFill()
            UIBezierPath(roundedRect: CGRect(x: 24, y: 28, width: 110, height: 16), cornerRadius: 8).fill()
            UIColor.white.withAlphaComponent(0.25).setFill()
            UIBezierPath(roundedRect: CGRect(x: 24, y: 220, width: 252, height: 150), cornerRadius: 16).fill()
        }
    }
}

/// A test bundle has no Info.plist to list its fonts, so it registers Geist itself.
enum Fonts {
    private static var done = false

    static func register() {
        guard !done else { return }
        done = true
        let bundle = Bundle(for: WidgetScreenshots.self)
        for url in bundle.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? [] {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

enum ScreenshotFolder {
    /// `xcodebuild` passes `TEST_RUNNER_SCREENSHOT_DIR` through as `SCREENSHOT_DIR`; the simulator can write to the Mac's disk.
    static var url: URL {
        let path = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? NSTemporaryDirectory()
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
