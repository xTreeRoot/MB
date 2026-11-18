import MBFoundation
import SwiftUI

struct DiscoveryHomeView: View {
    let model: DiscoveryModel
    let source: any DiscoverySource
    let select: ([Track], Int) -> Void
    var menu: (Track) -> AnyView = { _ in AnyView(EmptyView()) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 28) {
                if let message = model.errorMessage {
                    Text(LocalizedStringKey(message)).font(.callout).foregroundStyle(.secondary)
                }
                if model.sections.isEmpty && model.isLoading {
                    ProgressView("正在加载首页").frame(maxWidth: .infinity).padding(.top, 80)
                }
                ForEach(model.sections) { section in
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text(LocalizedStringKey(section.category.title)).font(.title3.bold())
                            Spacer()
                            NavigationLink(value: MBPage.discovery(section.category)) { Text("查看更多").font(.subheadline) }
                            .accessibilityLabel("查看更多\(section.category.title)")
                        }
                        if let message = section.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                        if section.tracks.isEmpty {
                            Text(LocalizedStringKey(section.errorMessage == nil ? "暂无视频" : "下拉刷新重试")).foregroundStyle(.secondary).padding(.vertical, 20)
                        } else { VideoGrid(tracks: section.tracks, select: select, menu: menu) }
                    }
                }
                if model.sections.isEmpty && !model.isLoading {
                    Button("重新加载") { Task { await model.refresh() } }.frame(maxWidth: .infinity)
                }
            }
            .padding(16)
        }
        .refreshable { await model.refresh() }
    }
}

struct DiscoveryPageView: View {
    @State private var model: DiscoveryPageModel
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView

    init(source: any DiscoverySource, category: DiscoveryCategory, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView = { _ in AnyView(EmptyView()) }) {
        _model = State(initialValue: DiscoveryPageModel(source: source, category: category))
        self.select = select
        self.menu = menu
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let subtitle = model.section?.subtitle { Text(subtitle).foregroundStyle(.secondary) }
                if let message = model.errorMessage {
                    Text(LocalizedStringKey(message)).foregroundStyle(.secondary)
                    Button("重试") { Task { await model.refresh() } }
                }
                if model.isLoading && model.section == nil { ProgressView().frame(maxWidth: .infinity) }
                if let section = model.section {
                    if section.tracks.isEmpty { Text("暂无视频").foregroundStyle(.secondary) }
                    VideoGrid(tracks: section.tracks, select: select, menu: menu)
                }
            }.padding(16)
        }
        .navigationTitle(Text(LocalizedStringKey(model.category.title)))
        .task { if model.section == nil { await model.refresh() } }
        .refreshable { await model.refresh() }
    }
}

private struct VideoGrid: View {
    let tracks: [Track]
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var columns: [GridItem] {
        sizeClass == .regular ? [GridItem(.adaptive(minimum: 180), spacing: 16)] : [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    }

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 22) {
            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                Button { select(tracks, index) } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        RemoteArtwork(track: track)
                            .aspectRatio(16 / 9, contentMode: .fit)
                            .clipped()
                            .overlay(alignment: .bottomTrailing) {
                                Text(TimeLabel.string(milliseconds: track.durationMilliseconds))
                                    .font(.caption2.monospacedDigit()).padding(4)
                                    .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 4))
                                    .foregroundStyle(.white).padding(6)
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        Text(track.title).font(.subheadline.weight(.medium)).lineLimit(2)
                            .frame(height: 42, alignment: .topLeading).frame(maxWidth: .infinity, alignment: .leading)
                        Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("播放 \(track.title)，\(track.artist)")
                .contextMenu { menu(track) }
            }
        }
    }
}
