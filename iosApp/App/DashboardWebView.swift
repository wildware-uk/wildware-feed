import SwiftUI
import WebKit

/// A request to show one page. A fresh id makes the same page load again, as a second tap on one notification should.
struct WebNavigation: Equatable {
    let path: String
    let id = UUID()
}

/// The dashboard itself, exactly as the browser shows it. Native features sit around it.
struct DashboardWebView: UIViewRepresentable {
    let server: URL
    let navigation: WebNavigation?
    let onPathChanged: @MainActor (String) -> Void
    let onPageLoaded: (WKWebView) -> Void

    static let userAgentSuffix = "AgentsiOS/1"

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.applicationNameForUserAgent = Self.userAgentSuffix

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.isInspectable = true
        webView.backgroundColor = UIColor(red: 0x11 / 255, green: 0x14 / 255, blue: 0x19 / 255, alpha: 1)
        webView.isOpaque = false

        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.reload(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refresh

        // The dashboard moves between pages without reloading, so only the address shows where it is.
        let onPathChanged = onPathChanged
        context.coordinator.addressObservation = webView.observe(\.url) { webView, _ in
            guard let path = webView.url?.path() else { return }
            Task { @MainActor in onPathChanged(path) }
        }

        webView.load(URLRequest(url: url(for: navigation?.path)))
        context.coordinator.lastNavigation = navigation?.id
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        guard let navigation, navigation.id != context.coordinator.lastNavigation else { return }
        context.coordinator.lastNavigation = navigation.id
        webView.load(URLRequest(url: url(for: navigation.path)))
    }

    private func url(for path: String?) -> URL {
        guard let path, let url = URL(string: path, relativeTo: server) else { return server }
        return url.absoluteURL
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let parent: DashboardWebView
        var lastNavigation: UUID?
        var addressObservation: NSKeyValueObservation?

        init(_ parent: DashboardWebView) { self.parent = parent }

        @objc func reload(_ sender: UIRefreshControl) {
            (sender.superview?.superview as? WKWebView)?.reload()
            sender.endRefreshing()
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.onPageLoaded(webView)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else { return decisionHandler(.allow) }
            if url.host() == parent.server.host() || url.scheme == "about" || url.scheme == "blob" {
                return decisionHandler(.allow)
            }
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url { UIApplication.shared.open(url) }
            return nil
        }
    }
}
