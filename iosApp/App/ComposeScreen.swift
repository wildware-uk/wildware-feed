import ComposeApp
import SwiftUI
import UIKit

/// The whole app screen: the Compose feed, sign-in and composer, drawn edge to edge.
/// Compose reads the safe area and the keyboard itself, so nothing here pads it.
struct ComposeScreen: UIViewControllerRepresentable {
    let navigator: AppNavigator

    func makeUIViewController(context: Context) -> UIViewController {
        let folder = AppData.folder.path(percentEncoded: false)
        let controller = MainViewControllerKt.MainViewController(
            sessions: KeychainSessionStore(), dataDir: folder, navigator: navigator, sample: Sample.isOn,
            photoPicker: PhotoPicker()
        )
        controller.view.backgroundColor = .obsidian
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// Where the Compose app keeps its read marks and its own outbox, in the app group container.
enum AppData {
    static var folder: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: CredentialStore.appGroup)
            ?? URL.applicationSupportDirectory
        let folder = base.appending(path: "App", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}

/// The Compose app's sign-in, kept where it always was: the server in the app group and the key in
/// the shared Keychain, so widgets, the share sheet, alerts, Siri and the watch keep using it.
/// A phone linked by the old web sign-in is already signed in here.
final class KeychainSessionStore: NSObject, SessionStore {
    private static let deviceNameKey = "deviceName"

    func load() -> SavedSession? {
        guard let credentials = CredentialStore.load() else { return nil }
        let name = CredentialStore.defaults.string(forKey: Self.deviceNameKey) ?? UIDevice.current.name
        return SavedSession(
            server: credentials.server.absoluteString,
            token: credentials.token,
            deviceName: name,
            deviceId: credentials.deviceId.isEmpty ? nil : credentials.deviceId
        )
    }

    func save(session: SavedSession) {
        guard let server = URL(string: session.server) else { return }
        CredentialStore.server = server
        CredentialStore.save(deviceId: session.deviceId ?? "", token: session.token)
        CredentialStore.defaults.set(session.deviceName, forKey: Self.deviceNameKey)
        Task { @MainActor in await AppModel.shared.signedIn() }
    }

    func clear() {
        CredentialStore.clear()
        CredentialStore.defaults.removeObject(forKey: Self.deviceNameKey)
        Task { @MainActor in AppModel.shared.signedOut() }
    }
}

extension UIColor {
    /// The Obsidian background (dark, or its light twin), shown before Compose draws its first frame.
    static let obsidian = UIColor { traits in
        traits.userInterfaceStyle == .light
            ? UIColor(red: 0xF6 / 255, green: 0xF6 / 255, blue: 0xF7 / 255, alpha: 1)
            : UIColor(red: 0x0A / 255, green: 0x0B / 255, blue: 0x0D / 255, alpha: 1)
    }
}
