import Foundation

/// The last number of questions the watch saw waiting, so the complication has something to show
/// when it cannot reach the dashboard itself.
enum WaitingCount {
    private static let key = "watchWaitingCount"

    static var saved: Int? {
        get { CredentialStore.defaults.object(forKey: key) as? Int }
        set { CredentialStore.defaults.set(newValue, forKey: key) }
    }

    /// Where the complication takes you: the inbox, at the top of the app.
    static let inboxURL = URL(string: "agents://inbox")!
}
