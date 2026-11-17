import Foundation
import WatchConnectivity

/// Hands this phone's server address and device key to the paired watch, which has no login page of its own.
///
/// The key goes over WatchConnectivity's application context: only the newest copy is kept, and the
/// watch receives it whenever it next wakes, even if it was off when the phone linked. Never log it.
final class WatchLink: NSObject, WCSessionDelegate {
    static let shared = WatchLink()

    /// Keys of the context dictionary. The watch app reads the same names.
    enum Key {
        static let server = "server"
        static let deviceId = "deviceId"
        static let token = "token"
        static let request = "wants"
    }

    func start() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Sends the current login, or an empty one after signing out so the watch forgets it too.
    func sendCredentials() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        do {
            try session.updateApplicationContext(Self.context())
        } catch {
            // The error never carries the context, so this does not log the key.
            print("Could not hand the login to the watch: \(error.localizedDescription)")
        }
    }

    private static func context() -> [String: Any] {
        guard let credentials = CredentialStore.load() else { return [:] }
        return [
            Key.server: credentials.server.absoluteString,
            Key.deviceId: credentials.deviceId,
            Key.token: credentials.token,
        ]
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if activationState == .activated { sendCredentials() }
    }

    /// The watch app was just installed, or the owner switched watches.
    func sessionWatchStateDidChange(_ session: WCSession) {
        sendCredentials()
    }

    /// A watch with no key asks for one when it opens, in case the context never reached it.
    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        replyHandler(message[Key.request] != nil ? Self.context() : [:])
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        // Switching to another watch deactivates the old session; the new one needs activating.
        session.activate()
    }
}
