import MBFoundation
import SwiftUI

/// 封面与歌词共用同一舞台和控制区；紧凑设备及辅助字体可滚动查看全部操作。
struct NowPlayingView: View {
    let playback: PlaybackModel
    let video: VideoModel
    let demo: Bool
    var interactionSource: (any InteractionSource)? = nil
    var librarySource: (any LibrarySource)? = nil
    var accountRevision = 0
    var downloads: DownloadModel? = nil
    var preferences: PreferencesModel? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var hotComments: [TrackComment] = []
    @State private var hotTrackID: String?
    @State private var showingQueue = false
    @State private var showingCaptions = false
    @State private var previewMilliseconds = 0.0
    @State private var scrubbing = false
    @State private var scrubbingSelection: Int?
    @State private var showingComments = false
    @State private var creatorDestination: CreatorDestination?
    private struct CreatorDestination: Identifiable { let id: String; let source: any LibrarySource }

    var body: some View {
        GeometryReader { geometry in
            if let track = playback.queue.current {
                Group {
                    if textSize.isAccessibilitySize {
                        ScrollView { player(track: track).frame(minHeight: max(900, geometry.size.height)) }
                    } else { player(track: track) }
                }.padding(.horizontal, geometry.size.width > 650 ? 70 : 24).padding(.vertical, 12)
            } else { ContentUnavailableView("播放队列为空", systemImage: "music.note.list") }
        }
        .background {
            MBTheme.background.ignoresSafeArea()
            if let track = playback.queue.current {
                RemoteArtwork(track: track).blur(radius: 90).opacity(0.12).ignoresSafeArea().allowsHitTesting(false).accessibilityHidden(true)
                if preferences?.values.fog == true { PlaybackFog(track: track, bass: playback.bassLevel) }
            }
        }
        .tint(MBTheme.accent)
        .sheet(isPresented: $showingQueue) { QueueView(playback: playback) }
        .sheet(isPresented: $showingComments) {
            if let interactionSource, let track = playback.queue.current { CommentsView(source: interactionSource, track: track).id("\(track.id):\(accountRevision)") }
        }
        .sheet(item: $creatorDestination) { destination in
            NavigationStack {
                LibraryPageView(source: destination.source, route: .creator(destination.id), select: { tracks, index in
                    creatorDestination = nil; playback.select(tracks, index: index, autoplay: !demo)
                }, menu: { track in
                    if let downloads { AnyView(TrackContextMenu(track: track, playback: playback, downloads: downloads)) }
                    else { AnyView(EmptyView()) }
                })
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { creatorDestination = nil } } }
            }.id(destination.id)
        }
        .task(id: (playback.queue.current?.id ?? "") + ":" + String(preferences?.values.hotComments ?? false) + ":" + String(interactionSource != nil) + ":" + String(accountRevision)) {
            hotComments = []; hotTrackID = nil
            guard preferences?.values.hotComments == true, let track = playback.queue.current, let interactionSource else { return }
            do {
                let page = try await interactionSource.comments(trackID: track.sourceTrackID, page: 1)
                guard !Task.isCancelled, playback.queue.current?.id == track.id else { return }
                hotComments = page.comments; hotTrackID = track.id
            } catch { /* 热评失败不打断歌词或播放。 */ }
        }
        .onChange(of: playback.selectionRevision) { _, _ in
            scrubbing = false; scrubbingSelection = nil
            previewMilliseconds = demo ? 0 : Double(playback.positionMilliseconds)
        }
        .onChange(of: playback.positionMilliseconds) { _, value in if !scrubbing && !demo { previewMilliseconds = Double(value) } }
        .onChange(of: video.presentationMode) { _, mode in if mode == .video { showingCaptions = false } }
        .onAppear { showingCaptions = preferences?.values.lyricsStage == true && video.presentationMode == .music; if !demo { previewMilliseconds = Double(playback.positionMilliseconds) } }
        #if os(iOS)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        #endif
    }

    private func player(track: Track) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 8) {
                Button("收起", systemImage: "chevron.down") { dismiss() }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                Spacer(minLength: 0)
                Text(LocalizedStringKey(demo ? "界面演示" : "正在播放")).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                Spacer(minLength: 0)
                styleMenu
                if let downloads {
                    Menu {
                        TrackContextMenu(track: track, playback: playback, downloads: downloads, openCreator: librarySource.map { source in
                            { id in creatorDestination = CreatorDestination(id: id, source: source) }
                        })
                    } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("更多操作")
                }
            }
            PlaybackModePicker(video: video)
            GeometryReader { region in
                let side = max(100, min(region.size.width, region.size.height, 440))
                Group {
                    if showingCaptions, let comment = quietComment {
                        Button { showingComments = true } label: {
                            VStack(alignment: .leading, spacing: 18) {
                                Label("热评", systemImage: "bubble.left").font(.caption).foregroundStyle(.secondary)
                                Text(comment.text).font(.title2.weight(.semibold)).multilineTextAlignment(.leading)
                                Text("\(comment.author) · \(comment.likes) 赞").font(.caption).foregroundStyle(.secondary)
                            }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.plain).accessibilityLabel("查看热评 \(comment.text)")
                    } else if showingCaptions {
                        CaptionView(timeline: demo ? DemoCatalog.timeline : playback.timeline,
                                    positionMilliseconds: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
                                    message: playback.captionMessage, viewportHeight: region.size.height, seek: { playback.seek(to: $0) })
                    } else if video.presentationMode == .video { VideoScreen(video: video).aspectRatio(16 / 9, contentMode: .fit) }
                    else {
                        Button {
                            Task { await video.choose(.video) }
                        } label: {
                            Group {
                                if let style = preferences?.values.playerStyle, style != .cover {
                                    RetroArtworkView(track: track, style: style, positionMilliseconds: playback.positionMilliseconds,
                                                     durationMilliseconds: playback.durationMilliseconds, playing: playback.state == .playing, size: side)
                                } else { ArtworkView(track: track, size: side).shadow(color: .black.opacity(0.15), radius: 18, y: 12) }
                            }.contentShape(Rectangle()).accessibilityHidden(true)
                        }.buttonStyle(.plain).accessibilityLabel("切换到实时视频")
                            .accessibilityIdentifier("player-artwork-" + (preferences?.values.playerStyle.rawValue ?? "cover"))
                            .disabled(track.localFileID != nil)
                    }
                }.frame(width: region.size.width, height: region.size.height)
            }.frame(minHeight: 130)
            VStack(spacing: 10) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(track.title).font(.title2.bold()).lineLimit(2)
                        Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if let downloads {
                        if let library, library.contains(track) {
                            Button { Task { await library.toggleFavorite(track) } } label: {
                                Image(systemName: library.snapshot.favorites.contains(track.id) ? "heart.fill" : "heart").font(.title2).frame(width: 44, height: 44)
                            }.accessibilityLabel(library.snapshot.favorites.contains(track.id) ? "取消喜欢" : "喜欢")
                        } else {
                            Button("下载音频", systemImage: "arrow.down.circle") { downloads.download(track) }.labelStyle(.iconOnly).font(.title2).frame(width: 44, height: 44).disabled(downloads.downloading[track.id] != nil || demo)
                        }
                    }
                }
                if let message = playback.errorMessage, !demo { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red).lineLimit(2) }
                if let station = playback.station { HStack { Label(station.title, systemImage: "radio"); Spacer(); Button("退出电台") { playback.endStation() } }.font(.caption) }
                if let message = playback.persistenceMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                progress
                controls
                HStack {
                    Button { showingCaptions.toggle(); if showingCaptions { Task { await video.choose(.music) } } } label: { Image(systemName: "quote.bubble").font(.title3).frame(width: 52, height: 44).background(showingCaptions ? MBTheme.accent.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 12)) }
                        .accessibilityLabel("字幕").accessibilityValue(Text(showingCaptions ? "开启" : "关闭"))
                    Spacer()
                    if interactionSource != nil, track.localFileID == nil {
                        Button { showingComments = true } label: { Image(systemName: "bubble.left.and.bubble.right").font(.title3).frame(width: 52, height: 44) }.accessibilityLabel("评论与点赞")
                        Spacer()
                    }
                    #if os(iOS)
                    AudioRoutePicker().frame(width: 44, height: 44).accessibilityLabel("音频输出")
                    Spacer()
                    #endif
                    Button { showingQueue = true } label: { Image(systemName: "list.bullet").font(.title3).frame(width: 52, height: 44) }.accessibilityLabel("队列").accessibilityValue("\(playback.queue.tracks.count)")
                }.foregroundStyle(.primary)
            }.frame(maxWidth: 540).frame(maxWidth: .infinity).accessibilityElement(children: .contain).accessibilityIdentifier("player-controls")
        }
    }
    @Environment(\.offlineLibrary) private var library

    @ViewBuilder private var styleMenu: some View {
        if let preferences {
            Menu {
                Menu("播放器样式") {
                    ForEach(PlayerVisualStyle.allCases) { style in
                        Button {
                            preferences.values.playerStyle = style
                            showingCaptions = false
                            Task { await video.choose(.music) }
                        } label: {
                            if !showingCaptions, video.presentationMode == .music, preferences.values.playerStyle == style {
                                Label(LocalizedStringKey(style.title), systemImage: "checkmark")
                            } else { Text(LocalizedStringKey(style.title)) }
                        }
                    }
                }
                Menu("歌词舞台") {
                    ForEach(LyricVisualStyle.allCases) { style in
                        Button {
                            preferences.values.lyricStyle = style
                            showingCaptions = true
                            Task { await video.choose(.music) }
                        } label: {
                            if showingCaptions, preferences.values.lyricStyle == style {
                                Label(LocalizedStringKey(style.title), systemImage: "checkmark")
                            } else { Text(LocalizedStringKey(style.title)) }
                        }
                    }
                }
            } label: { Image(systemName: "paintbrush").frame(width: 44, height: 44) }.accessibilityLabel("播放界面样式")
        }
    }
    private var quietComment: TrackComment? {
        guard preferences?.values.hotComments == true, hotTrackID == playback.queue.current?.id else { return nil }
        return QuietComments.comment(at: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
            timeline: demo ? DemoCatalog.timeline : playback.timeline, duration: playback.durationMilliseconds, comments: hotComments)
    }
    private var progress: some View {
        VStack(spacing: 0) {
            Slider(value: Binding(get: { min(max(0, previewMilliseconds), Double(max(1, playback.durationMilliseconds))) }, set: { previewMilliseconds = $0 }), in: 0...Double(max(1, playback.durationMilliseconds))) { editing in
                scrubbing = editing
                if editing { scrubbingSelection = playback.selectionRevision }
                else {
                    if !demo, scrubbingSelection == playback.selectionRevision { playback.seek(to: Int64(previewMilliseconds)) }
                    scrubbingSelection = nil
                }
            }.overlay { if preferences?.values.smoke == true { ProgressSmoke(progress: previewMilliseconds / Double(max(1, playback.durationMilliseconds)), position: Int64(previewMilliseconds), playing: playback.state == .playing) } }
                .accessibilityLabel(Text(demo ? "字幕时间轴预览" : "播放进度"))
            HStack { Text(TimeLabel.string(milliseconds: Int64(previewMilliseconds))); Spacer(); Text(TimeLabel.string(milliseconds: playback.durationMilliseconds)) }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
    }
    private var controls: some View {
        HStack {
            Button("随机播放", systemImage: "shuffle") { playback.toggleShuffle() }.labelStyle(.iconOnly).frame(width: 44, height: 44)
                .tint(playback.queue.shuffled ? MBTheme.accent : .secondary).accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
            Spacer(minLength: 4)
            Button("上一首", systemImage: "backward.end.fill") { playback.previous() }.labelStyle(.iconOnly).font(.title2).frame(width: 44, height: 52)
            Spacer(minLength: 4)
            Button { playback.toggle() } label: {
                Group { if playback.state == .loading { ProgressView() } else { Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").font(.system(size: 34, weight: .semibold)) } }.frame(width: 64, height: 64)
            }.buttonStyle(.plain).disabled(demo).accessibilityLabel(Text(playback.state == .playing ? "暂停" : "播放"))
            Spacer(minLength: 4)
            Button("下一首", systemImage: "forward.end.fill") { playback.next() }.labelStyle(.iconOnly).font(.title2).frame(width: 44, height: 52)
            Spacer(minLength: 4)
            Button(LocalizedStringKey(playback.queue.repeatMode.title), systemImage: playback.queue.repeatMode == .one ? "repeat.1" : "repeat") { playback.cycleRepeat() }.labelStyle(.iconOnly).frame(width: 44, height: 44).tint(playback.queue.repeatMode == .off ? .secondary : MBTheme.accent)
        }.foregroundStyle(.primary)
    }
}

struct QueueView: View {
    let playback: PlaybackModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                    Button { playback.jump(to: index, intent: .transport); dismiss() } label: {
                        TrackRow(track: track, selected: index == playback.queue.index)
                    }.buttonStyle(.plain).accessibilityLabel("队列选择 \(track.title)")
                }
                .onDelete { playback.removeQueue($0) }
                .onMove { playback.moveQueue($0, to: $1) }
            }
            .overlay {
                if playback.queue.tracks.isEmpty { ContentUnavailableView("播放队列为空", systemImage: "music.note.list") }
            }
            .navigationTitle("播放队列 · \(playback.queue.tracks.count)")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            #if os(iOS)
            .toolbar { ToolbarItem(placement: .primaryAction) { EditButton() } }
            #endif
        }
    }
}

struct PlaybackOrderControls: View {
    let playback: PlaybackModel
    var body: some View {
        HStack {
            Button("随机播放", systemImage: "shuffle") { playback.toggleShuffle() }
                .tint(playback.queue.shuffled ? MBTheme.accent : .secondary)
                .accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
            Spacer()
            Button(LocalizedStringKey(playback.queue.repeatMode.title), systemImage: playback.queue.repeatMode == .one ? "repeat.1" : "repeat") { playback.cycleRepeat() }
                .tint(playback.queue.repeatMode == .off ? .secondary : MBTheme.accent)
        }.font(.subheadline)
    }
}

