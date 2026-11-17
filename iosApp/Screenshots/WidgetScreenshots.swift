import Shared
import SwiftUI
import WidgetKit
import XCTest

/// Draws the real widget and Live Activity views with sample data and saves them as PNGs.
/// A home screen can't be scripted, so these are the views on a stand-in wallpaper, not the system's own frame.
@MainActor
final class WidgetScreenshots: XCTestCase {
    private let waiting = AgentsActivityAttributes.ContentState(
        online: 3,
        waiting: 2,
        inProgress: 4,
        topAgent: "Garden Game",
        topQuestion: "Tests pass. Merge the fee-rounding branch into main?",
        topRequestId: "req-1",
        topKind: "confirm"
    )
    private let quiet = AgentsActivityAttributes.ContentState(
        online: 3, waiting: 0, inProgress: 4, topAgent: nil, topQuestion: nil, topRequestId: nil, topKind: nil
    )
    private let confirm = PendingRequest(
        id: "req-1",
        agentName: "Garden Game",
        projectSlug: "garden-game",
        projectName: "Garden Game",
        question: "Tests pass. Merge the fee-rounding branch into main?",
        kind: RequestKind.companion.fromWire(value: "confirm")!,
        options: nil,
        expiresAt: "2026-11-16T12:00:00Z",
        detail: nil,
        defaultAnswer: nil
    )
    private let choice = PendingRequest(
        id: "req-2",
        agentName: "Harbor App",
        projectSlug: "harbor-app",
        projectName: "Harbor App",
        question: "The build is green. What next?",
        kind: RequestKind.companion.fromWire(value: "choice")!,
        options: ["Ship it", "Hold", "Roll back"],
        expiresAt: "2026-11-16T12:00:00Z",
        detail: nil,
        defaultAnswer: nil
    )

    func testHomeScreenWidgets() throws {
        try save("widget-small", widget(.systemSmall, state: waiting, top: confirm), width: 170, height: 170)
        try save("widget-medium", widget(.systemMedium, state: waiting, top: choice), width: 364, height: 170)
        try save("widget-medium-quiet", widget(.systemMedium, state: quiet, top: nil), width: 364, height: 170)
    }

    func testLockScreenWidgets() throws {
        try save("lock-rectangular", accessory(.accessoryRectangular, width: 172, height: 76))
        try save("lock-circular", accessory(.accessoryCircular, width: 76, height: 76))
        try save("lock-inline", accessory(.accessoryInline, width: 260, height: 26))
    }

    func testProjectWidget() throws {
        try save("project-small", project(.systemSmall), width: 170, height: 170)
        try save("project-medium", project(.systemMedium), width: 364, height: 170)
        try save("project-large", project(.systemLarge), width: 364, height: 382)
        // StandBy: no card behind it, on a black screen across the room.
        let standBy = ProjectWidgetContent(entry: projectEntry(.small), family: .systemSmall, showsBackground: false)
            .padding(16)
            .background(.black)
        try save("project-standby", standBy, width: 170, height: 170)
        let lock = ProjectWidgetContent(entry: projectEntry(.accessory), family: .accessoryRectangular, showsBackground: false)
            .foregroundStyle(.white)
        try save("project-lock-rectangular", lock, width: 172, height: 76)
    }

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
        let view = AlertView(title: "home-server replied to you", markdown: markdown, plainText: "", openLink: { _ in }, openApp: {})
        let fitted = UIHostingController(rootView: view).sizeThatFits(in: CGSize(width: 370, height: CGFloat.greatestFiniteMagnitude))
        XCTAssertGreaterThan(fitted.height, 200, "the expanded alert would have no height")
        let alert = view
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        try save("alert-styled", alert, width: 370)
    }

    func testLiveActivity() throws {
        try save("live-activity-question", liveActivity(waiting))
        try save("live-activity-quiet", liveActivity(quiet))
    }

    func testVoiceNoteLiveActivity() throws {
        let note = VoiceNoteActivityAttributes(startedAt: .now.addingTimeInterval(-12), projectName: "Garden Game")
        try save("voice-note-recording", liveActivity(VoiceNoteStatusView(attributes: note, state: .init(phase: .recording))))
        try save("voice-note-sent", liveActivity(VoiceNoteStatusView(attributes: note, state: .init(phase: .sent, detail: "Garden Game"))))
    }

    private func widget(_ family: WidgetFamily, state: AgentsActivityAttributes.ContentState, top: PendingRequest?) -> some View {
        StatusWidgetContent(entry: StatusEntry(date: .now, state: .ready(state, top: top)), family: family)
            .padding(16)
            .background(Color.dashboardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func projectEntry(_ size: CardSize) -> ProjectEntry {
        ProjectEntry(date: .now, state: .ready(ProjectCard.sample(size: size)))
    }

    private func project(_ family: WidgetFamily) -> some View {
        ProjectWidgetContent(entry: projectEntry(CardSize.of(family)), family: family, showsBackground: true)
            .padding(16)
            .background(Color.dashboardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func accessory(_ family: WidgetFamily, width: CGFloat, height: CGFloat) -> some View {
        StatusWidgetContent(entry: StatusEntry(date: .now, state: .ready(waiting, top: confirm)), family: family)
            .foregroundStyle(.white)
            .frame(width: width, height: height)
    }

    private func liveActivity(_ state: AgentsActivityAttributes.ContentState) -> some View {
        liveActivity(LockScreenView(state: state))
    }

    private func liveActivity(_ content: some View) -> some View {
        content
            .padding()
            .frame(width: 370, alignment: .leading)
            .background(Color.dashboardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    private func save(_ name: String, _ view: some View, width: CGFloat? = nil, height: CGFloat? = nil) throws {
        let framed = view
            .frame(width: width, height: height)
            .padding(28)
            .background(LinearGradient(colors: [.indigo, .black], startPoint: .topLeading, endPoint: .bottomTrailing))
            .environment(\.colorScheme, .dark)
        let renderer = ImageRenderer(content: framed)
        renderer.scale = 3
        let png = try XCTUnwrap(renderer.uiImage?.pngData(), "\(name) drew nothing")
        try png.write(to: ScreenshotFolder.url.appendingPathComponent("\(name).png"))
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
