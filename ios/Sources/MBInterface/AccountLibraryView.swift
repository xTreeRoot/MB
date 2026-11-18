import CoreImage.CIFilterBuiltins
import MBFoundation
import SwiftUI

struct AccountLibraryView: View {
    let account: AccountModel
    var plugin: PluginSource?
    let source: (any LibrarySource)?
    let playback: PlaybackModel
    let downloads: DownloadModel
    let select: ([Track], Int) -> Void
    @State private var showingSignIn = false
    @State private var path: [LibraryRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    if let user = account.account {
                        Label(user.name, systemImage: "person.crop.circle.fill").font(.title3.bold())
                        if source != nil { NavigationLink("我的投稿", value: LibraryRoute.creator(user.id)) }
                        Button("退出登录", role: .destructive) { Task { await account.signOut() } }
                    } else if account.available {
                        Button("登录\(account.displayName)", systemImage: "qrcode.viewfinder") { showingSignIn = true; account.signIn() }
                    }
                    if let message = account.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red) }
                }
                if let plugin {
                    Section("我的内容") { PluginPersonalLinks(source: plugin, account: account.account, select: select, menu: { AnyView(TrackContextMenu(track: $0, playback: playback, downloads: downloads)) }) }
                }
                if source != nil {
                    Section("我的内容") {
                        NavigationLink("收藏夹", value: LibraryRoute.favorites)
                        NavigationLink("点赞的视频", value: LibraryRoute.liked)
                        NavigationLink("观看历史", value: LibraryRoute.history)
                        NavigationLink("我的关注", value: LibraryRoute.following)
                    }
                }
            }
            .navigationTitle("我的")
            .navigationDestination(for: LibraryRoute.self) { route in
                if let source {
                    LibraryPageView(source: source, route: route, select: select, menu: { track in
                        AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads, openCreator: { path.append(.creator($0)) }))
                    }).id(account.revision)
                }
            }
            .refreshable { await account.restore() }
        }
        .onChange(of: ObjectIdentifier(account)) { _, _ in showingSignIn = false; path = [] }
        .sheet(isPresented: $showingSignIn, onDismiss: { account.cancelSignIn() }) { SignInView(model: account) }
    }
}

private struct SignInView: View {
    let model: AccountModel
    @Environment(\.pluginUI) private var pluginUI
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if let prompt = model.prompt, let image = qrImage(prompt.qrURL.absoluteString) {
                    image.interpolation(.none).resizable().scaledToFit().frame(maxWidth: 270).padding(16).background(.white)
                        .accessibilityLabel("登录二维码")
                    Text(LocalizedStringKey(prompt.message)).multilineTextAlignment(.center)
                    ShareLink(item: image, preview: SharePreview("登录二维码", image: image)) {
                        Label("保存或分享二维码", systemImage: "square.and.arrow.up")
                    }
                } else if model.isSigningIn { ProgressView("正在准备登录") }
                if let error = model.errorMessage {
                    Text(LocalizedStringKey(error)).foregroundStyle(.red)
                    Button("重新登录") { model.signIn() }.buttonStyle(.borderedProminent)
                }
            }
            .padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("扫码登录")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { model.cancelSignIn(); dismiss() } } }
        }
        .modifier(PluginSignInPresentation(service: pluginUI))
        .onChange(of: model.account) { _, value in if value != nil && !model.isSigningIn { dismiss() } }
    }
    private func qrImage(_ text: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return Image(decorative: cgImage, scale: 1)
    }
}

struct LibraryPageView: View {
    @State private var model: LibraryModel
    let select: ([Track], Int) -> Void
    let menu: (Track) -> AnyView
    init(source: any LibrarySource, route: LibraryRoute, select: @escaping ([Track], Int) -> Void, menu: @escaping (Track) -> AnyView = { _ in AnyView(EmptyView()) }) {
        _model = State(initialValue: LibraryModel(source: source, route: route)); self.select = select
        self.menu = menu
    }
    var body: some View {
        List {
            ForEach(model.links) { link in
                NavigationLink(value: link.route) {
                    HStack {
                        Image(systemName: link.route.isCreator ? "person.crop.circle" : "folder").font(.title2).frame(width: 40)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(link.title)
                            if !link.subtitle.isEmpty { Text(link.subtitle).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                Button { select(model.tracks, index) } label: { TrackRow(track: track) }
                    .buttonStyle(.plain).accessibilityLabel("播放 \(track.title)")
                    .contextMenu { menu(track) }
            }
            if let error = model.errorMessage {
                Text(LocalizedStringKey(error)).foregroundStyle(.secondary)
                Button("重试") { Task { await model.retry() } }
            }
            if model.isLoading { ProgressView().frame(maxWidth: .infinity) }
            else if model.tracks.isEmpty && model.links.isEmpty && model.errorMessage == nil {
                ContentUnavailableView("暂无内容", systemImage: "tray")
            }
            if model.nextCursor != nil {
                Button("加载更多") { Task { await model.loadMore() } }.disabled(model.isLoading)
            }
        }
        .navigationTitle(Text(LocalizedStringKey(model.title)))
        .task { await model.refresh() }
        .refreshable { await model.refresh() }
    }
}

private extension LibraryRoute {
    var isCreator: Bool { if case .creator = self { true } else { false } }
}
