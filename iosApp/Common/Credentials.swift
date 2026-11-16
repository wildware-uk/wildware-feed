import Foundation
import Security

/// Where this phone's dashboard lives and the device key that lets the app, its widgets,
/// its share sheet and Siri act as the owner. Stored once, read by every target.
struct Credentials: Equatable {
    let server: URL
    let deviceId: String
    let token: String
}

enum CredentialStore {
    static let appGroup = "group.dev.wildware.agents"
    private static let service = "dev.wildware.agents.device"
    private static let account = "owner"
    private static let serverKey = "serverURL"

    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    /// The server address is not a secret, and the app needs it before any device key exists.
    static var server: URL? {
        get { defaults.string(forKey: serverKey).flatMap(URL.init(string:)) }
        set { defaults.set(newValue?.absoluteString, forKey: serverKey) }
    }

    static func load() -> Credentials? {
        guard let server else { return nil }
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let stored = try? JSONDecoder().decode(StoredKey.self, from: data)
        else { return nil }
        return Credentials(server: server, deviceId: stored.deviceId, token: stored.token)
    }

    static func save(deviceId: String, token: String) {
        let data = (try? JSONEncoder().encode(StoredKey(deviceId: deviceId, token: token))) ?? Data()
        SecItemDelete(baseQuery() as CFDictionary)
        var query = baseQuery()
        query[kSecValueData as String] = data
        // Widgets and notification actions run while the phone is locked, after its first unlock.
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    static func clear() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private struct StoredKey: Codable {
        let deviceId: String
        let token: String
    }

    private static func baseQuery() -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group = sharedKeychainGroup { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    /// The team prefix is only known at run time, so read it from the app's own entitlement.
    private static let sharedKeychainGroup: String? = {
        guard let prefix = Bundle.main.object(forInfoDictionaryKey: "AppIdentifierPrefix") as? String else { return nil }
        return "\(prefix)dev.wildware.agents.shared"
    }()
}
