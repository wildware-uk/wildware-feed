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
