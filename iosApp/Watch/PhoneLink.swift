import Foundation
import WatchConnectivity

/// Receives the server address and device key from the iPhone, which signs in for the watch.
///
/// The phone sends them as its application context whenever it links or signs out, and answers
/// when the watch asks. They are kept in the watch's own keychain. Never log the key.
final class PhoneLink: NSObject, WCSessionDelegate {
    static let shared = PhoneLink()

    /// The context's keys. They match the phone's `WatchLink.Key`.
    private enum Key {
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

    /// Asks the phone for its login directly, for a watch that never got the context.
    func askPhone() {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return }
        session.sendMessage([Key.request: "credentials"], replyHandler: { reply in
            // An empty reply means the phone is not signed in, which says nothing new.
            if !reply.isEmpty { self.apply(reply) }
        }, errorHandler: nil)
    }

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard activationState == .activated else { return }
        // An empty context here only means nothing has arrived yet, not that the phone signed out.
        if !session.receivedApplicationContext.isEmpty { apply(session.receivedApplicationContext) }
        if CredentialStore.load() == nil { askPhone() }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        apply(applicationContext)
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        if CredentialStore.load() == nil { askPhone() }
    }

    /// Stores what the phone sent. A context without a key is the phone signing out, so the watch forgets too.
    private func apply(_ context: [String: Any]) {
        if let server = (context[Key.server] as? String).flatMap(URL.init(string:)),
           let deviceId = context[Key.deviceId] as? String,
           let token = context[Key.token] as? String {
            let incoming = Credentials(server: server, deviceId: deviceId, token: token)
            guard CredentialStore.load() != incoming else { return }
            CredentialStore.server = server
            CredentialStore.save(deviceId: deviceId, token: token)
        } else {
            guard CredentialStore.load() != nil else { return }
            CredentialStore.clear()
            CredentialStore.server = nil
        }
        Task { @MainActor in WatchModel.shared.credentialsChanged() }
    }
}
