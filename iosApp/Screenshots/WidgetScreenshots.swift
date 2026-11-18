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

    func testStyledAlert() throws {
        let markdown = """
        ## Styled alert test
        Long-press this alert. You should see **bold**, _italics_, `inline code` and a [tappable link](https://dashboard.example.com).

        - a bullet point
        - another, with **bold** inside

        1. a numbered step
        2. and a second

        > a quoted line

        ```
        npm test   # a code block
        ```
        """
        let view = AlertView(title: "Pixel replied to you", markdown: markdown, plainText: "", openLink: { _ in }, openApp: {})
        let fitted = UIHostingController(rootView: view).sizeThatFits(in: CGSize(width: 370, height: CGFloat.greatestFiniteMagnitude))
        XCTAssertGreaterThan(fitted.height, 200, "the expanded alert would have no height")
        let alert = view
            .background(Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        try save("alert-styled", alert, scheme: .dark, width: 370)
    }

    func testLiveActivity() throws {
        for scheme in Scheme.allCases {
            try save("live-activity-question-\(scheme)", liveActivity(LockScreenView(state: waiting)), scheme: scheme)
            try save("live-activity-quiet-\(scheme)", liveActivity(LockScreenView(state: quietState)), scheme: scheme)
        }
    }

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

    private func save(_ name: String, _ view: some View, scheme: Scheme, width: CGFloat? = nil, height: CGFloat? = nil) throws {
        let framed = view
            .frame(width: width, height: height)
            .padding(28)
            .background(LinearGradient(colors: scheme.wallpaper, startPoint: .topLeading, endPoint: .bottomTrailing))
            .environment(\.colorScheme, scheme.colorScheme)
        let renderer = ImageRenderer(content: framed)
        renderer.scale = 3
        let png = try XCTUnwrap(renderer.uiImage?.pngData(), "\(name) drew nothing")
        try png.write(to: ScreenshotFolder.url.appendingPathComponent("\(name).png"))
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
