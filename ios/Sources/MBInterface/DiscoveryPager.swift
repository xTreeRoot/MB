import MBFoundation
import SwiftUI

/// 系统分页手势与内容横向滚动协调，平台顺序直接使用插件本地顺序。
struct DiscoveryPager: View {
    let sources: [SourceTab]
    let providers: [String: any MusicSource]
    let selected: String
    let switchSource: (String) -> Void
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    var body: some View {
        #if os(iOS)
        TabView(selection: Binding(get: { selected }, set: switchSource)) {
            ForEach(sources) { tab in
                if let source = providers[tab.id] {
                    DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu)
                        .tag(tab.id)
                }
            }
        }.tabViewStyle(.page(indexDisplayMode: .never)).accessibilityIdentifier("discovery-platform-pager")
        #else
        if let source = providers[selected] { DiscoveryPlatformPage(source: source, videoMode: videoMode, select: select, menu: menu) }
        #endif
    }
}

private struct DiscoveryPlatformPage: View {
    let source: any MusicSource
    let videoMode: Bool
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @State private var discovery: DiscoveryModel?
    init(source: any MusicSource, videoMode: Bool, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView) {
        self.source = source; self.videoMode = videoMode; self.select = select; self.menu = menu
        _discovery = State(initialValue: (source as? any DiscoverySource).map { DiscoveryModel(source: $0) })
    }
    var body: some View {
        Group {
            if let plugin = source as? PluginSource { PluginHomeView(source: plugin, select: select, menu: menu).id(ObjectIdentifier(plugin)) }
            else if let discovery, let provider = source as? any DiscoverySource {
                DiscoveryHomeView(model: discovery, source: provider, videoMode: videoMode, select: select, menu: menu)
            } else { ContentUnavailableView("暂无内容", systemImage: "music.note") }
        }.task { if let discovery, discovery.sections.isEmpty { await discovery.refresh() } }
    }
}
