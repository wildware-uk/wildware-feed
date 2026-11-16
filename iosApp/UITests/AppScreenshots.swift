import XCTest

/// Drives the real app in the simulator: the first-run screen, then the dashboard loaded inside it.
final class AppScreenshots: XCTestCase {
    func testFirstRunThenDashboard() throws {
        let app = XCUIApplication()
        app.launch()

        let address = app.textFields.firstMatch
        XCTAssertTrue(address.waitForExistence(timeout: 15))
        address.tap()
        address.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 12))
        address.typeText(ProcessInfo.processInfo.environment["DASHBOARD_URL"] ?? "https://dashboard.example.com")
        try save("app-setup")

        app.buttons["Connect"].tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 20))
        // The page fetches its scripts after the web view appears; give it time to draw.
        Thread.sleep(forTimeInterval: 8)
        try save("app-dashboard")
    }

    private func save(_ name: String) throws {
        let path = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? NSTemporaryDirectory()
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
    }
}
