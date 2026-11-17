import UIKit
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

    /// The Inbox and composer bar with made-up questions (the app's `-sample-inbox` switch), so they show without a dashboard.
    func testInboxAndComposer() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-sample-inbox"]
        app.launch()

        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 20))
        Thread.sleep(forTimeInterval: 6)
        try save("app-composer")

        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        if !isPad {
            let inbox = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Inbox'")).firstMatch
            XCTAssertTrue(inbox.waitForExistence(timeout: 5))
            inbox.tap()
        }
        let question = app.staticTexts["Tests pass. Merge the fee-rounding branch into main?"]
        XCTAssertTrue(question.waitForExistence(timeout: 10))
        Thread.sleep(forTimeInterval: 1)
        try save("app-inbox")

        app.staticTexts["The build is green. What next?"].tap()
        XCTAssertTrue(app.buttons["Ship it"].waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 1)
        try save("app-question")

        app.buttons["Reply without answering"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Reply to Harbor App'")).firstMatch.waitForExistence(timeout: 5))
        Thread.sleep(forTimeInterval: 1)
        try save("app-composer-reply")
    }

    private func save(_ name: String) throws {
        let name = UIDevice.current.userInterfaceIdiom == .pad ? "\(name)-ipad" : name
        let path = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? NSTemporaryDirectory()
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
    }
}
