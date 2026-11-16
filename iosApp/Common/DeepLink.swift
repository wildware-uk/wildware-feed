import Foundation

/// `agents://open?path=/projects/x?focus=y` opens a page of the dashboard inside the app.
enum DeepLink {
    static let scheme = "agents"

    static func open(path: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "open"
        components.queryItems = [URLQueryItem(name: "path", value: path)]
        return components.url!
    }

    static func path(from url: URL) -> String? {
        guard url.scheme == scheme else { return nil }
        return URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "path" }?.value
    }

    /// Notifications carry absolute dashboard URLs; the web view only needs the part after the host.
    static func dashboardPath(fromAbsolute string: String) -> String? {
        guard let url = URL(string: string) else { return nil }
        var path = url.path()
        if let query = url.query() { path += "?\(query)" }
        return path.isEmpty ? "/" : path
    }
}

/// A link tapped in an expanded alert. Alerts can't open links themselves, so the alert leaves it
/// here and opens the app, which follows it.
enum PendingLink {
    private static let key = "pendingAlertLink"
    /// Old enough and it belongs to some earlier tap, not the one that opened the app.
    private static let freshFor: TimeInterval = 30

    static func leave(_ url: URL) {
        CredentialStore.defaults.set(["url": url.absoluteString, "at": Date.now.timeIntervalSince1970], forKey: key)
    }

    static func take() -> URL? {
        let defaults = CredentialStore.defaults
        guard let saved = defaults.dictionary(forKey: key) else { return nil }
        defaults.removeObject(forKey: key)
        guard let at = saved["at"] as? Double, Date.now.timeIntervalSince1970 - at < freshFor,
              let string = saved["url"] as? String else { return nil }
        return URL(string: string)
    }
}
