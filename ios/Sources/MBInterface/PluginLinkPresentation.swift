import MBFoundation
import SwiftUI
import WebKit

struct PluginLinkPresentation: ViewModifier {
    let service: PluginUIService
    func body(content: Content) -> some View {
        content.sheet(item: Binding(get: { service.link }, set: { service.link = $0 })) { request in
            NavigationStack {
                PluginLinkPage(url: request.url).navigationTitle(request.title)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { service.link = nil } } }
            }
        }
    }
}

#if os(iOS)
private struct PluginLinkPage: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config); view.load(URLRequest(url: url)); return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {}
    static func dismantleUIView(_ view: WKWebView, coordinator: ()) { view.stopLoading() }
}
#else
private struct PluginLinkPage: NSViewRepresentable {
    let url: URL
    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config); view.load(URLRequest(url: url)); return view
    }
    func updateNSView(_ view: WKWebView, context: Context) {}
    static func dismantleNSView(_ view: WKWebView, coordinator: ()) { view.stopLoading() }
}
#endif
