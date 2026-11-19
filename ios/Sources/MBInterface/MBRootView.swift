import MBFoundation
import SwiftUI

enum MBPage: Hashable { case discovery(DiscoveryCategory), video, library(LibraryRoute) }
private enum MBTab: Hashable { case discover, search, library, account }
private enum SearchScope: Hashable { case tracks, videos }

/// 手机使用层级返回；平板保留发现侧栏。所有页面共用同一播放会话。
public struct MBRootView: View {
    @State private var selectedTab = MBTab.discover
    @Namespace private var playerTransition
    @State private var trackSearch: SearchModel
    @State private var videoSearch: SearchModel?
    @State private var searchScope = SearchScope.tracks
    @State private var playback: PlaybackModel
    @State private var video: VideoModel
    @State private var home: DiscoveryModel?
    @State private var networkSettings = NetworkSettingsModel()
    @State private var suggestions: [String] = []
    @State private var recordedSelection = -1
    @State private var searchInputs: [SearchScope: String] = [:]
    @State private var request: SearchRequest?
    @State private var path: [MBPage] = []
    @State private var searchPath: [MBPage] = []
    @State private var showingAppSettings = false
    @State private var showingPlayer = false
    @State private var showingQueue = false
    @State private var showingSettings = false
    @State private var showingPlugins = false
    @State private var account: AccountModel
    @State private var accountPlatformID: String
    @State private var commentTrack: Track?
    @State private var systemPlayback = SystemPlaybackCoordinator()
    @State private var downloads: DownloadModel
    @State private var preferences = PreferencesModel()
    @State private var skin: BackgroundSkin
    @State private var localLibrary: LocalLibraryModel
    @State private var previewLibrary: DemoLibrary?
    @State private var columns: NavigationSplitViewVisibility = .all
    @FocusState private var inputFocused: Bool
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var source: any MusicSource
    @State private var selectedSourceID: String
    @State private var plugins: PluginModel
    @State private var platformData = PlatformDataModel()
    @State private var currentInteractions: (any InteractionSource)?
    @State private var interactionTrackID: String?
    @State private var currentLibrarySource: (any LibrarySource)?
    private let router: SourceRouter
    private let initialSource: any MusicSource
    private let initialSourceID: String
    private let demo: Bool
    private let demoTrackProblems: Bool
    private var search: SearchModel { searchScope == .videos ? videoSearch ?? trackSearch : trackSearch }
    private var input: String {
        get { searchInputs[searchScope, default: ""] }
        nonmutating set { searchInputs[searchScope] = newValue }
    }

    public init(source: any MusicSource, sourceID: String = "demo", demo: Bool = false) {
        let preview = demo ? DemoLibrary() : nil
        let router = SourceRouter()
        #if DEBUG
        let demoTrackProblems = demo && ProcessInfo.processInfo.arguments.contains("--plugin-track-problems")
        #else
        let demoTrackProblems = false
        #endif
        self.demoTrackProblems = demoTrackProblems
        self.router = router
        self.initialSource = source; self.initialSourceID = sourceID
        _selectedSourceID = State(initialValue: sourceID)
        _trackSearch = State(initialValue: SearchModel(source: source))
        _playback = State(initialValue: PlaybackModel(source: demo && !demoTrackProblems ? source as? any PlaybackSource : router,
                                                     engine: NativeAudioEngine(), store: demo ? nil : AppPersistence.shared, mediaLibrary: demo ? nil : MediaLibrary.shared))
        _home = State(initialValue: (source as? any DiscoverySource).map { DiscoveryModel(source: $0) })
        _video = State(initialValue: VideoModel(source: demo ? source as? any VideoSource : router, store: demo ? nil : AppPersistence.shared))
        _source = State(initialValue: source)
        self.demo = demo
        _account = State(initialValue: AccountModel(source: source as? any AccountSource))
        _accountPlatformID = State(initialValue: sourceID)
        _downloads = State(initialValue: DownloadModel(source: demo ? source as? any PlaybackSource : router, library: preview?.media ?? .shared))
        _localLibrary = State(initialValue: LocalLibraryModel(store: preview?.store ?? .shared))
        _previewLibrary = State(initialValue: preview)
        _skin = State(initialValue: BackgroundSkin(directory: preview?.skinDirectory))
        let installer = preview.map { PluginInstaller(directory: $0.pluginDirectory) } ?? PluginInstaller()
        #if DEBUG
        if let preview, ProcessInfo.processInfo.arguments.contains("--plugin-check-demo") {
            _plugins = State(initialValue: PluginModel(installer: installer) { installer, repo in
                try await Task.sleep(for: .seconds(5))
                let fixture = try preview.pluginFixture()
                return try await installer.prepare(repo == "fixture/failure" ? fixture.appendingPathComponent("missing.zip") : fixture)
            })
            return
        }
        #endif
        _plugins = State(initialValue: PluginModel(installer: installer))
    }

    private var tabContent: some View {
        TabView(selection: $selectedTab) {
            adaptiveNavigation
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .discover) }
                .tabItem { Label("发现", systemImage: "safari") }.tag(MBTab.discover)
            searchNavigation.safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .search) }
                .tabItem { Label("搜索", systemImage: "magnifyingglass") }.tag(MBTab.search)
            DownloadsView(model: downloads, library: localLibrary, playback: playback)
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .library) }
                .tabItem { Label("资料库", systemImage: "music.note.list") }.tag(MBTab.library)
            AccountLibraryView(account: account, plugin: source as? PluginSource, source: source as? any LibrarySource, playback: playback, downloads: downloads, select: select, openSettings: { showingAppSettings = true }, openPlugins: { showingPlugins = true })
                .safeAreaInset(edge: .bottom, spacing: 0) { legacyMiniPlayer(for: .account) }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }.tag(MBTab.account)
        }
    }

    @ViewBuilder private var tabsWithPlayer: some View {
        #if os(iOS)
        if #available(iOS 26.1, *) {
            tabContent.tabViewBottomAccessory(isEnabled: playback.queue.current != nil && !video.isVisible) {
                miniPlayer(systemContainer: true)
            }
        } else { tabContent }
        #else
        tabContent
        #endif
    }

    @ViewBuilder private func legacyMiniPlayer(for tab: MBTab) -> some View {
        // 旧系统仅在当前标签的内容安全区挂载，保留唯一的封面转场来源。
        if selectedTab == tab {
            #if os(iOS)
            if #unavailable(iOS 26.1) { miniPlayer() }
            #else
            miniPlayer()
            #endif
        }
    }

    @ViewBuilder private func miniPlayer(systemContainer: Bool = false) -> some View {
        if playback.queue.current != nil && !video.isVisible {
            MiniPlayerView(playback: playback, systemContainer: systemContainer, transitionEnabled: preferences.values.growFromCover, transitionNamespace: playerTransition, openQueue: { showingQueue = true }) {
                if video.presentationMode == .video { video.open(); showVideo() }
                else { showingPlayer = true }
            }
        }
    }

    private var tabs: some View {
        tabsWithPlayer
            .environment(\.backgroundSkinImage, skin.image)
            .preferredColorScheme(preferences.values.appearance == .system ? nil : preferences.values.appearance == .dark ? .dark : .light)
            .playerPresentation(isPresented: $showingPlayer) { NowPlayingView(playback: playback, demo: demo, interactionSource: currentInteractions, librarySource: currentLibrarySource, accountRevision: account.revision, downloads: downloads, preferences: preferences, watchVideo: { video.watchCurrent() }).modifier(CoverTransitionDestination(enabled: preferences.values.growFromCover, namespace: playerTransition)) }
            .sheet(isPresented: $showingQueue) { QueueView(playback: playback) }
            .sheet(isPresented: $showingAppSettings) { SettingsView(preferences: preferences, network: networkSettings, plugins: plugins, skin: skin, platformData: platformData, account: verifiedProfileAccount, platform: selectedSourceID) }
            .sheet(item: $commentTrack) { track in
                if let interactions = currentInteractions { CommentsView(source: interactions, track: track).id(account.revision) }
            }
            .sheet(isPresented: $showingPlugins) {
                NavigationStack {
                    PluginsView(model: plugins).toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { showingPlugins = false }.accessibilityIdentifier("dismiss-plugins") } }
                }.modifier(PluginPreparationPresentation(model: plugins))
            }
            .onOpenURL { url in
                if let repo = PluginInstaller.repository(fromInstallURL: url) { showingPlugins = true; Task { await plugins.prepareGitHub(repo) } }
            }
            .sheet(isPresented: $showingSettings) { NetworkSettingsView(model: networkSettings) }
    }

    private var initializedTabs: some View {
        tabs
            .task { await skin.restore() }
            .task { if !demo { await platformData.restore() } }
            .task(id: "\(selectedSourceID):\(accountPlatformID):\(account.revision):\(account.isSigningIn):\(account.isSigningOut):\(platformData.revision)") {
                if !demo { platformData.sync(verifiedProfileAccount, platform: selectedSourceID) }
            }
            .task {
                await router.register(initialSource, id: initialSourceID)
                if !demo { await plugins.restore(); await registerPlugins(); Task { await plugins.checkUpdates() } }
                if !demo { systemPlayback.connect(playback) }
                if !demo { await networkSettings.restore() }
                if !demo { await preferences.restore(); if let saved = preferences.values.selectedSourceID { selectedSourceID = saved }; playback.setVolume(preferences.values.volume); playback.setNormalization(preferences.values.normalization) }
                if !demo { systemPlayback.setLyricsEnabled(preferences.values.nowPlayingLyrics) }
                AppLocalization.use(preferences.values.language)
                await router.setQuality(preferences.values.quality)
                await account.restore()
                if let previewLibrary {
                    await previewLibrary.prepare()
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("--plugin-demo") {
                        do { await plugins.prepare(try previewLibrary.pluginFixture(includeTrackProblems: demoTrackProblems)) }
                        catch { plugins.reportError(error) }
                    }
                    #endif
                }
                await downloads.refresh()
                localLibrary.updateTracks(downloads.items)
                await localLibrary.restore()
                await video.restore()
                await playback.restore()
                for item in downloads.items { playback.updateLocalTrack(item.track) }
                video.updateTrack(playback.queue.current)
                if let home, home.sections.isEmpty { await home.refresh() }
            }
            .task(id: selectedSourceID + ":" + String(plugins.revision)) { await activateSource() }
            .task(id: (playback.queue.current?.id ?? "") + ":" + String(plugins.revision)) {
                currentInteractions = nil
                interactionTrackID = nil
                currentLibrarySource = nil
                let track = playback.queue.current
                await registerPlugins()
                guard let track, let provider = await router.source(for: track), !Task.isCancelled,
                      playback.queue.current?.id == track.id else { return }
                currentLibrarySource = provider as? any LibrarySource
                if let interactions = provider as? any InteractionSource {
                    let available = await interactions.capabilities().available
                    guard !Task.isCancelled, playback.queue.current?.id == track.id else { return }
                    currentInteractions = available ? interactions : nil
                    interactionTrackID = available ? track.id : nil
                }
            }
    }

    private var observedTabs: some View {
        AnyView(initializedTabs)
            .onChange(of: selectedSourceID) { _, id in preferences.values.selectedSourceID = id }
            .onChange(of: selectedTab) { _, _ in inputFocused = false }
            .onChange(of: preferences.values.builtInEnabled) { _, enabled in
                if !enabled, selectedSourceID == initialSourceID, plugins.sources[initialSourceID] == nil { switchSource(plugins.sources.keys.sorted().first ?? "") }
                if enabled, selectedSourceID.isEmpty { switchSource(initialSourceID) }
                Task { await registerPlugins() }
            }
            .onChange(of: plugins.revision) { _, _ in Task { await registerPlugins() } }
            .onChange(of: playback.positionMilliseconds) { _, _ in syncVideoClock() }
            .onChange(of: playback.audioBuffering) { _, _ in syncVideoClock() }
            .onChange(of: video.isLoading) { _, _ in syncVideoClock() }
            .onChange(of: video.isBuffering) { _, _ in syncVideoClock() }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { Task { await playback.saveNow(); if !demo { await preferences.flush() } } }
                else if !demo { Task { await plugins.checkUpdates() } }
            }
            .onChange(of: preferences.values) { _, _ in
                AppLocalization.use(preferences.values.language)
                if !demo { preferences.save(); playback.setVolume(preferences.values.volume); playback.setNormalization(preferences.values.normalization); systemPlayback.setLyricsEnabled(preferences.values.nowPlayingLyrics) }
            }
            .onChange(of: preferences.values.quality) { _, quality in
                playback.invalidatePreparedAudio()
                Task { await router.setQuality(quality); playback.invalidatePreparedAudio() }
            }
            .onChange(of: downloads.items) { _, items in
                localLibrary.updateTracks(items)
                for item in items { playback.updateLocalTrack(item.track) }
            }
    }

    private var playbackObservedTabs: some View {
        AnyView(observedTabs)
            .onChange(of: playback.state) { _, state in
                syncVideoClock()
                if state == .playing, let track = playback.queue.current {
                    Task { await localLibrary.recordPlay(track) }
                    if recordedSelection != playback.selectionRevision {
                        recordedSelection = playback.selectionRevision
                        Task { if let source = await router.source(for: track) as? any PlaybackRecorder { await source.played(track) } }
                    }
                }
            }
            .onDisappear { systemPlayback.disconnect() }
            .onChange(of: playback.selectionRevision) { _, _ in
                video.handleSelection(playback.queue.current, intent: playback.selectionIntent,
                                      positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
            }
            .onChange(of: video.isVisible) { _, visible in
                syncVideoClock()
                if visible { showingPlayer = false; showVideo() }
                else { path.removeAll { $0 == .video }; searchPath.removeAll { $0 == .video } }
            }
            .onChange(of: video.mode) { _, mode in
                if mode == .music { changeSearchScope(.tracks) }
            }
            .onChange(of: path) { _, pages in
                if selectedTab == .discover, !pages.contains(.video), video.isVisible { video.browse() }
            }
            .onChange(of: searchPath) { _, pages in
                if selectedTab == .search, !pages.contains(.video), video.isVisible { video.browse() }
            }
    }

    private func syncVideoClock() {
        video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing && !playback.audioBuffering)
        playback.holdForVideo(video.isVisible && (video.isLoading || video.isBuffering))
    }

    public var body: some View {
        AnyView(playbackObservedTabs)
            .task(id: input + ":" + selectedSourceID + ":" + String(describing: searchScope)) { await updateSuggestions() }
            .task(id: request?.id) { [request] in
                guard let request, !Task.isCancelled else { return }
                if request.more { await request.model.loadMore() }
                else { await request.model.search(request.query) }
            }
            .environment(\.pluginUI, plugins.ui)
            .modifier(PluginLinkPresentation(service: plugins.ui))
            .environment(\.offlineLibrary, localLibrary)
            .environment(\.stationPlayback, playback)
            .environment(\.lyricSparks, preferences.values.stageSparks)
            .environment(\.lyricVisualStyle, preferences.values.lyricStyle)
            .environment(\.locale, Locale(identifier: preferences.values.language.languageCode))
            .tint(MBTheme.accent)
    }

    private func updateSuggestions() async {
        suggestions = []
        guard inputFocused,
              !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let source = source as? any SuggestionSource else { return }
        do {
            try await Task.sleep(for: .milliseconds(250))
            let result = try await source.suggestions(input)
            guard !Task.isCancelled else { return }
            suggestions = Array(Set(result)).sorted()
        } catch { /* 联想失败保留正常搜索。 */ }
    }

    @ViewBuilder private var adaptiveNavigation: some View {
        if usesSidebar {
            NavigationSplitView(columnVisibility: $columns) {
                List {
                    Section("发现") {
                        Button("首页", systemImage: "house") { video.browse(); path = []; input = "" }
                        ForEach(DiscoveryCategory.allCases) { category in
                            Button(LocalizedStringKey(category.title)) { video.browse(); path = [.discovery(category)] }
                        }
                    }
                    Section("播放器") {
                        Button("听歌模式", systemImage: "music.note") { chooseMode(.music) }
                        Button("视频模式", systemImage: "play.rectangle") { chooseMode(.video) }
                        Button("网络设置", systemImage: "gearshape") { showingSettings = true }
                    }
                }
                .navigationTitle("MB")
                .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 260)
            } detail: { navigation }
        } else { navigation }
    }

    private var usesSidebar: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad && sizeClass == .regular
        #else
        sizeClass == .regular
        #endif
    }

    private var navigation: some View {
        NavigationStack(path: $path) {
            browsingHome
                .safeAreaInset(edge: .top, spacing: 0) {
                    if !sourceTabs.isEmpty {
                        SourceTabs(sources: sourceTabs, selected: selectedSourceID, select: switchSource)
                    }
                }
                .navigationTitle("")
                .modifier(CompactRootNavigation())
                .toolbar { toolbar }
                .navigationDestination(for: MBPage.self) { destination($0) }
        }
    }

    private var searchNavigation: some View {
        NavigationStack(path: $searchPath) {
            VStack(spacing: 0) {
                searchBar
                if video.mode == .video, videoSearch != nil {
                    Picker("搜索类型", selection: Binding(get: { searchScope }, set: { scope in changeSearchScope(scope) })) {
                        Text("音乐").tag(SearchScope.tracks)
                        Text("视频").tag(SearchScope.videos)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("search-scope")
                    .padding(.horizontal, 16).padding(.bottom, 8)
                }
                if inputFocused && !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(suggestions, id: \.self) { text in Button(text) { input = text; submit() }.buttonStyle(.bordered).frame(minHeight: 44) } }.padding(.horizontal, 20)
                    }
                }
                Group {
                    if search.query.isEmpty { searchLanding }
                    else { searchResults }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .scrollDismissesKeyboard(.immediately)
                .simultaneousGesture(TapGesture().onEnded { inputFocused = false })
                .simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in inputFocused = false })
            }
            .defaultFocus($inputFocused, false)
            .onAppear { inputFocused = false }
            .onDisappear { inputFocused = false }
            .navigationTitle("")
            .modifier(CompactRootNavigation())
            .toolbar { toolbar }
            .navigationDestination(for: MBPage.self) { destination($0) }
        }
        .onChange(of: input) { _, value in
            if value.trimmingCharacters(in: .whitespacesAndNewlines) != search.query {
                search.cancel()
                if value.isEmpty { request = SearchRequest(model: search, query: "") }
                else if request?.query != value { request = nil }
            }
        }
    }

    private var searchLanding: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if !preferences.values.searchHistory.isEmpty {
                    MusicSectionHeading(title: "最近搜索")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120))], alignment: .leading, spacing: 10) {
                        ForEach(preferences.values.searchHistory, id: \.self) { term in
                            Button(term) { input = term; submit() }.buttonStyle(.bordered).frame(minHeight: 44)
                        }
                    }
                }
                if source is any DiscoverySource {
                    MusicSectionHeading(title: "浏览音乐")
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                        ForEach(DiscoveryCategory.allCases) { category in
                            NavigationLink(value: MBPage.discovery(category)) {
                                VStack(alignment: .leading, spacing: 24) {
                                    Image(systemName: category == .chart ? "chart.bar.fill" : category == .fresh ? "sparkles" : category == .trending ? "flame.fill" : "headphones").font(.title2)
                                    Text(LocalizedStringKey(category.title)).font(.headline)
                                }.frame(maxWidth: .infinity, minHeight: 110, alignment: .leading).padding(16)
                                    .background(MBTheme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain)
                        }
                    }
                }
                if let plugin = source as? PluginSource {
                    NavigationLink("搜索音源") { PluginCatalogSearch(source: plugin, select: select, menu: trackMenu) }
                }
            }.padding(20)
        }.modifier(SkinSurface(defaultColor: MBTheme.background))
            .scrollDismissesKeyboard(.immediately)
            .accessibilityIdentifier("search-landing")
    }

    @ViewBuilder private func destination(_ page: MBPage) -> some View {
        switch page {
        case .video:
            VideoPageView(video: video, playback: playback, interactionSource: interactionTrackID == playback.queue.current?.id ? currentInteractions : nil, accountRevision: account.revision, openMusic: {
                showingPlayer = true
                Task { await video.choose(.music) }
            })
            #if os(iOS)
            .toolbar(.hidden, for: .navigationBar, .tabBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            #else
            .navigationTitle("视频播放")
            #endif
        case .discovery(let category):
            if let discovery = source as? any DiscoverySource { DiscoveryPageView(source: discovery, category: category, videoMode: video.mode == .video, select: select, menu: trackMenu).toolbar { toolbar } }
        case .library(let route):
            if let provider = source as? any LibrarySource { LibraryPageView(source: provider, route: route, select: select, menu: trackMenu) }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Menu {
                Button("听歌模式", systemImage: video.mode == .music ? "checkmark" : "music.note") { chooseMode(.music) }
                Button("视频模式", systemImage: video.mode == .video ? "checkmark" : "play.rectangle") { chooseMode(.video) }
            } label: {
                Label(LocalizedStringKey(video.mode == .music ? "听歌模式" : "视频模式"), systemImage: video.mode == .music ? "music.note" : "play.rectangle")
            }.accessibilityLabel("切换播放模式")
        }
        if selectedTab == .discover {
            ToolbarItem(placement: .primaryAction) {
                Button("搜索", systemImage: "magnifyingglass") { selectedTab = .search }.labelStyle(.iconOnly)
            }
        }
        if let track = playback.queue.current, track.localFileID == nil, currentInteractions != nil {
            ToolbarItem(placement: .secondaryAction) {
                Button("评论与点赞", systemImage: "bubble.left.and.bubble.right") { commentTrack = track }
            }
        }
    }

    @ViewBuilder private var browsingHome: some View {
            if let home, let discovery = source as? any DiscoverySource {
                DiscoveryHomeView(model: home, source: discovery, videoMode: video.mode == .video, select: select, menu: trackMenu)
            } else if let plugin = source as? PluginSource {
                PluginHomeView(source: plugin, select: select, menu: trackMenu)
                    .id(ObjectIdentifier(plugin))
            } else { ContentUnavailableView("从喜欢的声音开始", systemImage: "waveform", description: Text("搜索视频与音乐")) }
    }

    @ViewBuilder private var searchResults: some View {
        if search.tracks.isEmpty && search.isLoading {
            ProgressView("正在搜索").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let message = search.errorMessage, search.tracks.isEmpty {
            ContentUnavailableView {
                Label("搜索失败", systemImage: "wifi.exclamationmark")
            } description: { Text(LocalizedStringKey(message)) } actions: { Button("重试") { submit() }.buttonStyle(.borderedProminent) }
        } else if search.tracks.isEmpty && !search.hasMore { ContentUnavailableView.search(text: search.query) }
        else {
            List {
                ForEach(Array(search.tracks.enumerated()), id: \.element.id) { index, track in
                    HStack(spacing: 4) {
                        Button { select(search.tracks, index) } label: {
                            let selected = playback.queue.current?.id == track.id
                            if video.mode == .video { VideoTrackRow(track: track, selected: selected) }
                            else { TrackRow(track: track, selected: selected) }
                        }
                            .buttonStyle(.plain).accessibilityLabel("选择 \(track.title)")
                            .modifier(TrackProblemAccessibility(track: track, enabled: video.mode == .music))
                        Menu { trackMenu(track) } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("曲目操作 \(track.title)")
                    }.contextMenu { trackMenu(track) }
                }
                if search.hasMore {
                    VStack(spacing: 8) {
                        if let message = search.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                        Button("加载更多") { request = SearchRequest(model: search, query: search.query, more: true) }
                            .buttonStyle(.bordered).disabled(search.isLoading)
                        if search.isLoading { ProgressView() }
                    }.frame(maxWidth: .infinity).listRowSeparator(.hidden)
                }
            }.listStyle(.plain).modifier(SkinSurface(list: true))
        }
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("搜索视频、音乐、UP 主", text: Binding(get: { input }, set: { input = $0 }))
                .accessibilityLabel("搜索视频、音乐、UP 主")
                .autocorrectionDisabled().focused($inputFocused)
                .submitLabel(.search)
                .onSubmit { submit() }
            if !input.isEmpty { Button("清空搜索", systemImage: "xmark.circle.fill") { input = "" }.labelStyle(.iconOnly).foregroundStyle(.secondary) }
            Button("搜索") { submit() }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("submit-search")
            if inputFocused {
                Button("完成") { inputFocused = false }
                    .accessibilityIdentifier("dismiss-search-keyboard")
            }
        }
        .padding(12).background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private func registerPlugins() async {
        if preferences.values.builtInEnabled { await router.register(initialSource, id: initialSourceID) }
        await router.keep(Set(plugins.sources.keys).union(preferences.values.builtInEnabled ? [initialSourceID] : []))
        for (id, plugin) in plugins.sources { await router.register(plugin, id: id) }
    }
    private var sourceTabs: [SourceTab] {
        var tabs = plugins.installed.compactMap { plugin in
            plugins.sources[plugin.id] == nil ? nil : SourceTab(id: plugin.id, name: plugin.manifest.displayName)
        }
        if preferences.values.builtInEnabled, plugins.sources[initialSourceID] == nil {
            tabs.insert(SourceTab(id: initialSourceID, name: initialSource.displayName), at: 0)
        }
        return tabs
    }
    private func switchSource(_ id: String) {
        guard id != selectedSourceID else { return }
        platformData.cancelProfileSync()
        playback.clear()
        video.updateTrack(nil)
        showingPlayer = false; showingQueue = false; commentTrack = nil
        inputFocused = false
        search.cancel(); request = nil
        selectedSourceID = id
    }
    private func activateSource() async {
        let id = selectedSourceID
        let chosen: any MusicSource
        if let plugin = plugins.sources[id] { chosen = plugin }
        else if id == initialSourceID, preferences.values.builtInEnabled { chosen = initialSource }
        else if let fallback = plugins.sources.keys.sorted().first { switchSource(fallback); return }
        else if preferences.values.builtInEnabled { switchSource(initialSourceID); return }
        else if !id.isEmpty { switchSource(""); return }
        else { chosen = DisabledMusicSource() }
        account.cancelSignIn()
        let accountSource: (any AccountSource)?
        let videoSearchSource: PluginSource?
        if let plugin = chosen as? PluginSource {
            accountSource = (try? await plugin.supports("signIn")) == true ? plugin : nil
            videoSearchSource = (try? await plugin.supports("searchVideos")) == true ? plugin : nil
        } else {
            accountSource = chosen as? any AccountSource
            videoSearchSource = nil
        }
        guard id == selectedSourceID, !Task.isCancelled else { return }
        trackSearch.cancel(); videoSearch?.cancel()
        source = chosen; path = []; searchPath = []; searchInputs = [:]; request = nil; searchScope = .tracks
        trackSearch = SearchModel(source: chosen)
        videoSearch = videoSearchSource.map { SearchModel(videoSource: $0) }
        home = (chosen as? any DiscoverySource).map { DiscoveryModel(source: $0) }
        account = AccountModel(source: accountSource)
        accountPlatformID = id
        await account.restore()
        if let home { await home.refresh() }
    }

    private var verifiedProfileAccount: SourceAccount? {
        guard selectedSourceID == accountPlatformID, !account.isSigningIn, !account.isSigningOut else { return nil }
        return account.account
    }

    private func chooseMode(_ mode: PlaybackMode) {
        Task { await video.choose(mode) }
    }
    private func changeSearchScope(_ scope: SearchScope) {
        guard scope != searchScope else { return }
        search.cancel(); request = nil; searchScope = scope
    }
    private func showVideo() {
        if selectedTab == .search { if searchPath.last != .video { searchPath.append(.video) } }
        else { selectedTab = .discover; if path.last != .video { path.append(.video) } }
    }
    private func select(_ tracks: [Track], _ index: Int) { playback.select(tracks, index: index, autoplay: !demo) }
    private func trackMenu(_ track: Track) -> AnyView {
        AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads,
            openCreator: source is any LibrarySource ? { id in
                if selectedTab == .search { searchPath.append(.library(.creator(id))) }
                else { path.append(.library(.creator(id))) }
            } : nil))
    }
    private func submit() {
        inputFocused = false; video.browse(); searchPath = []
        if !demo { preferences.recordSearch(input) }
        request = SearchRequest(model: search, query: input)
    }
}

private struct SearchRequest {
    let id = UUID()
    let model: SearchModel
    let query: String
    var more = false
}

