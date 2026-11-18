import UIKit
import XCTest

/// Drives the real app in the simulator. The screens are drawn by Compose, so the tests wait and
/// take pictures rather than tapping through them.
final class AppScreenshots: XCTestCase {
    /// A phone with no key opens on the sign-in screen.
    func testFirstRun() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
        // Compose draws its first frame, and loads its fonts, a moment after launch.
        Thread.sleep(forTimeInterval: 5)
        XCTAssertEqual(app.state, .runningForeground, "The app closed on its first screen")
        try save("app-sign-in")
    }

    /// The feed on made-up data (the app's `-sample-inbox` switch), so it shows without a dashboard.
    func testSampleFeed() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-sample-inbox"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15))
        Thread.sleep(forTimeInterval: 5)
        XCTAssertEqual(app.state, .runningForeground, "The app closed while drawing the feed")
        try save("app-feed")
    }

    private func save(_ name: String) throws {
        let name = UIDevice.current.userInterfaceIdiom == .pad ? "\(name)-ipad" : name
        let path = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? NSTemporaryDirectory()
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
    }
}
