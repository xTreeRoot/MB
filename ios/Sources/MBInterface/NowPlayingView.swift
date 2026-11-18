import MBFoundation
import SwiftUI

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
    @State private var hotComments: [TrackComment] = []
    @State private var hotTrackID: String?
    @State private var showingQueue = false
    @State private var showingCaptions = false
    @State private var previewMilliseconds = 0.0
    @State private var scrubbing = false
    @State private var scrubbingSelection: Int?
    @State private var showingComments = false
    @State private var creatorDestination: CreatorDestination?

    private struct CreatorDestination: Identifiable {
        let id: String
        let source: any LibrarySource
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let track = playback.queue.current {
                    VStack(spacing: 28) {
                        if showingCaptions, let comment = quietComment {
                            Button { showingComments = true } label: {
                                VStack(alignment: .leading, spacing: 18) {
                                    Label("热评", systemImage: "bubble.left").font(.caption).foregroundStyle(.secondary)
                                    Text(comment.text).font(.title3.weight(.medium)).multilineTextAlignment(.leading)
                                    Text("\(comment.author) · \(comment.likes) 赞").font(.caption).foregroundStyle(.secondary)
                                }.padding(20).frame(maxWidth: .infinity, minHeight: 260, alignment: .leading)
                            }.buttonStyle(.plain).accessibilityLabel("查看热评 \(comment.text)")
                        } else if showingCaptions {
                            CaptionView(timeline: demo ? DemoCatalog.timeline : playback.timeline,
                                        positionMilliseconds: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
                                        message: playback.captionMessage, seek: { playback.seek(to: $0) })
                                .frame(minHeight: 260)
                        } else if video.presentationMode == .video {
                            VideoScreen(video: video).aspectRatio(16 / 9, contentMode: .fit)
                        } else {
                            if let style = preferences?.values.playerStyle, style != .cover {
                                RetroArtworkView(track: track, style: style, positionMilliseconds: playback.positionMilliseconds, durationMilliseconds: playback.durationMilliseconds, playing: playback.state == .playing)
                            } else {
                                ArtworkView(track: track, size: 260).shadow(color: .black.opacity(0.18), radius: 24, y: 14)
                            }
                        }
                        if let station = playback.station {
                            HStack { Label(station.title, systemImage: "radio"); Spacer(); Button("退出电台") { playback.endStation() } }.font(.caption)
                        }
                        if let message = playback.persistenceMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
                    }
                    .padding(28)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let track = playback.queue.current {
                    VStack(spacing: 14) {
                        VStack(spacing: 5) {
                            Text(track.title).font(.title3.bold()).lineLimit(2).multilineTextAlignment(.center)
                            Text(track.artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                            if let message = playback.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red).lineLimit(2) }
                        }
                        progress(track: track)
                        controls
                        HStack {
                            Button { showingCaptions.toggle() } label: { Label("字幕", systemImage: "quote.bubble") }
                                .tint(showingCaptions ? MBTheme.accent : .secondary).frame(maxWidth: .infinity, minHeight: 44)
                            if interactionSource != nil, track.localFileID == nil {
                                Button { showingComments = true } label: { Label("评论", systemImage: "bubble.left.and.bubble.right") }
                                    .accessibilityLabel("评论与点赞").frame(maxWidth: .infinity, minHeight: 44)
                            }
                            Button { showingQueue = true } label: { Label("队列", systemImage: "list.bullet") }
                                .accessibilityValue("\(playback.queue.tracks.count)").frame(maxWidth: .infinity, minHeight: 44)
                        }.font(.subheadline)
                    }
                    .padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 8)
                    .frame(maxWidth: 560).frame(maxWidth: .infinity)
                    .background(.regularMaterial)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("player-controls")
                }
            }
            .background {
                if preferences?.values.fog == true, let track = playback.queue.current { PlaybackFog(track: track, bass: playback.bassLevel) }
            }
            .navigationTitle(Text(demo ? "界面演示" : "正在播放"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("收起", systemImage: "chevron.down") { dismiss() }
                }
                if let preferences {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Picker("播放器样式", selection: Binding(get: { preferences.values.playerStyle }, set: { preferences.values.playerStyle = $0 })) {
                                    ForEach(PlayerVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                            }
                            Picker("歌词舞台", selection: Binding(get: { preferences.values.lyricStyle }, set: { preferences.values.lyricStyle = $0 })) {
                                ForEach(LyricVisualStyle.allCases) { style in Text(LocalizedStringKey(style.title)).tag(style) }
                            }
                        } label: { Label("播放界面样式", systemImage: "paintbrush") }
                    }
                }
                if let track = playback.queue.current, let downloads {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            TrackContextMenu(track: track, playback: playback, downloads: downloads,
                                openCreator: librarySource.map { source in
                                    { id in creatorDestination = CreatorDestination(id: id, source: source) }
                                })
                        } label: { Label("更多操作", systemImage: "ellipsis") }
                    }
                }
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
                    creatorDestination = nil
                    playback.select(tracks, index: index, autoplay: !demo)
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
        .onChange(of: playback.positionMilliseconds) { _, value in
            if !scrubbing && !demo { previewMilliseconds = Double(value) }
        }
        .onAppear { showingCaptions = preferences?.values.lyricsStage == true; if !demo { previewMilliseconds = Double(playback.positionMilliseconds) } }
        #if os(iOS)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        #endif
    }

    private var quietComment: TrackComment? {
        guard preferences?.values.hotComments == true, hotTrackID == playback.queue.current?.id else { return nil }
        return QuietComments.comment(at: demo ? Int64(previewMilliseconds) : playback.positionMilliseconds,
            timeline: demo ? DemoCatalog.timeline : playback.timeline, duration: playback.durationMilliseconds, comments: hotComments)
    }

    private func progress(track: Track) -> some View {
        VStack(spacing: 8) {
            Slider(value: Binding(get: { min(max(0, previewMilliseconds), Double(max(1, playback.durationMilliseconds))) }, set: { previewMilliseconds = $0 }), in: 0...Double(max(1, playback.durationMilliseconds))) { editing in
                scrubbing = editing
                if editing { scrubbingSelection = playback.selectionRevision }
                else {
                    if !demo, scrubbingSelection == playback.selectionRevision { playback.seek(to: Int64(previewMilliseconds)) }
                    scrubbingSelection = nil
                }
            }
                .overlay {
                    if preferences?.values.smoke == true { ProgressSmoke(progress: previewMilliseconds / Double(max(1, playback.durationMilliseconds)), position: Int64(previewMilliseconds), playing: playback.state == .playing) }
                }
                .accessibilityLabel(Text(demo ? "字幕时间轴预览" : "播放进度"))
            HStack {
                Text(TimeLabel.string(milliseconds: Int64(previewMilliseconds)))
                Spacer()
                Text(TimeLabel.string(milliseconds: playback.durationMilliseconds))
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            if demo { Text("拖动预览字幕时间轴，无音频").font(.caption).foregroundStyle(.secondary) }
        }
    }

    private var controls: some View {
        VStack(spacing: 12) {
            HStack {
                Button("随机播放", systemImage: "shuffle") { playback.toggleShuffle() }
                    .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                    .tint(playback.queue.shuffled ? MBTheme.accent : .secondary)
                    .accessibilityValue(Text(playback.queue.shuffled ? "开启" : "关闭"))
                Spacer(minLength: 4)
                Button("上一首", systemImage: "backward.end.fill") { playback.previous() }
                    .labelStyle(.iconOnly).font(.title2).frame(minWidth: 44, minHeight: 44)
                Spacer(minLength: 4)
                Button { playback.toggle() } label: {
                    Group {
                        if playback.state == .loading { ProgressView().tint(.white) }
                        else { Image(systemName: playback.state == .playing ? "pause.fill" : "play.fill").font(.title) }
                    }.frame(width: 70, height: 70)
                }
                .buttonStyle(.borderedProminent).clipShape(Circle()).disabled(demo)
                .accessibilityLabel(Text(playback.state == .playing ? "暂停" : "播放"))
                Spacer(minLength: 4)
                Button("下一首", systemImage: "forward.end.fill") { playback.next() }
                    .labelStyle(.iconOnly).font(.title2).frame(minWidth: 44, minHeight: 44)
                Spacer(minLength: 4)
                Button(LocalizedStringKey(playback.queue.repeatMode.title), systemImage: playback.queue.repeatMode == .one ? "repeat.1" : "repeat") { playback.cycleRepeat() }
                    .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                    .tint(playback.queue.repeatMode == .off ? .secondary : MBTheme.accent)
            }
            if playback.state == .loading { Text("正在准备音频").font(.caption).foregroundStyle(.secondary) }
        }
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

