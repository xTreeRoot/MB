import AVFoundation
import MBFoundation
import SwiftUI

/// 手机竖屏顶部画面与详情；宽屏平板将队列放在右侧，横屏全屏共用原生播放器。
struct VideoPageView: View {
    let video: VideoModel
    let playback: PlaybackModel
    @State private var fullScreen = false
    @State private var selectedTab = VideoDetailTab.info

    var body: some View {
        GeometryReader { geometry in
            if geometry.size.width >= 800 {
                HStack(alignment: .top, spacing: 24) {
                    ScrollView { mainContent }
                    VideoQueueView(playback: playback).frame(width: 300)
                }.padding(20)
            } else {
                VStack(spacing: 0) {
                    picture
                    ScrollView {
                        VStack(alignment: .leading, spacing: 18) {
                            VideoTransport(playback: playback)
                            PlaybackOrderControls(playback: playback)
                            Picker("视频详情", selection: $selectedTab) {
                                Text("简介").tag(VideoDetailTab.info)
                                Text("字幕").tag(VideoDetailTab.captions)
                                Text("队列").tag(VideoDetailTab.queue)
                            }.pickerStyle(.segmented)
                            switch selectedTab {
                            case .info: information
                            case .captions: CaptionView(timeline: playback.timeline, positionMilliseconds: playback.positionMilliseconds, message: playback.captionMessage, seek: { playback.seek(to: $0) }).frame(minHeight: 240)
                            case .queue: VideoQueueView(playback: playback)
                            }
                        }.padding(16)
                    }
                }
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $fullScreen) { VideoFullscreenView(video: video, playback: playback) }
        #else
        .sheet(isPresented: $fullScreen) { VideoFullscreenView(video: video, playback: playback) }
        #endif
    }

    private var mainContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            picture
            VideoTransport(playback: playback)
            PlaybackOrderControls(playback: playback)
            information
            CaptionView(timeline: playback.timeline, positionMilliseconds: playback.positionMilliseconds, message: playback.captionMessage, seek: { playback.seek(to: $0) })
                .frame(minHeight: 160)
        }
    }

    private var picture: some View {
        VideoScreen(video: video, pictureVisible: !fullScreen)
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay(alignment: .bottomTrailing) {
                Button("全屏播放", systemImage: "arrow.up.left.and.arrow.down.right") { fullScreen = true }
                    .labelStyle(.iconOnly).padding(12)
                    .background(.black.opacity(0.55), in: Circle()).foregroundStyle(.white).padding(8)
            }
    }

    private var information: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let track = playback.queue.current {
                Text(track.title).font(.title3.bold())
                Label(track.artist, systemImage: "person.crop.circle").font(.subheadline).foregroundStyle(.secondary)
            }
            Button("返回浏览", systemImage: "square.grid.2x2") { video.browse() }
            if let message = playback.errorMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.red) }
            if let message = video.persistenceMessage { Text(LocalizedStringKey(message)).font(.caption).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private enum VideoDetailTab { case info, captions, queue }

private struct VideoQueueView: View {
    let playback: PlaybackModel
    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            Text("播放队列 · \(playback.queue.tracks.count)").font(.headline)
            ForEach(Array(playback.queue.tracks.enumerated()), id: \.offset) { index, track in
                Button { playback.jump(to: index, intent: .transport) } label: {
                    TrackRow(track: track, selected: playback.queue.index == index)
                }.buttonStyle(.plain).accessibilityLabel("队列选择 \(track.title)")
            }
        }
    }
}

private struct VideoFullscreenView: View {
    let video: VideoModel
    let playback: PlaybackModel
    @Environment(\.dismiss) private var dismiss
    @State private var controlsVisible = true
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoScreen(video: video).ignoresSafeArea().onTapGesture { controlsVisible.toggle() }
            if controlsVisible {
                VStack {
                    HStack {
                        Button("退出全屏", systemImage: "chevron.left") { dismiss() }
                        Text(playback.queue.current?.title ?? "视频播放").font(.headline).lineLimit(1)
                        Spacer()
                    }
                    .padding().background(.black.opacity(0.6))
                    Spacer()
                    VideoTransport(playback: playback).padding().background(.black.opacity(0.6))
                }.foregroundStyle(.white)
            }
        }
        .tint(MBTheme.accent)
        #if os(iOS)
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear { MobileOrientation.fullscreen(true) }
        .onDisappear { MobileOrientation.fullscreen(false) }
        #endif
    }
}

private struct VideoTransport: View {
    let playback: PlaybackModel
    var body: some View {
        VStack(spacing: 8) {
            Slider(value: Binding(get: { Double(playback.positionMilliseconds) }, set: { playback.seek(to: Int64($0)) }),
                   in: 0...Double(max(1, playback.durationMilliseconds)))
                .accessibilityLabel("视频播放进度")
            HStack {
                Text(TimeLabel.string(milliseconds: playback.positionMilliseconds))
                Text("/ \(TimeLabel.string(milliseconds: playback.durationMilliseconds))")
                Spacer()
                Button("上一首", systemImage: "backward.end.fill") { playback.previous() }
                Button(LocalizedStringKey(playback.state == .playing ? "暂停" : "播放"), systemImage: playback.state == .playing ? "pause.fill" : "play.fill") { playback.toggle() }
                Button("下一首", systemImage: "forward.end.fill") { playback.next() }
            }.font(.subheadline.monospacedDigit()).labelStyle(.iconOnly)
            #if os(iOS)
            SystemVolumeView().frame(maxWidth: 240).frame(height: 28).accessibilityLabel("系统音量")
            #endif
        }
    }
}

struct VideoScreen: View {
    let video: VideoModel
    var pictureVisible = true
    var body: some View {
        Color.black.overlay {
            if video.track == nil { message("先从首页或搜索选择视频") }
            else if !video.isSupported { message("此音源不支持视频播放") }
            else if let error = video.errorMessage {
                VStack(spacing: 12) { message(error); Button("重试视频") { video.retry() } }
            } else {
                ZStack {
                    if video.isReady, let player = video.player, pictureVisible {
                        NativeVideoSurface(player: player).accessibilityLabel("视频画面")
                    }
                    if video.isLoading || video.isBuffering {
                        ProgressView(LocalizedStringKey(video.isLoading ? "正在加载视频" : "正在缓冲"))
                            .tint(.white).foregroundStyle(.white)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
    private func message(_ text: String) -> some View {
        Text(LocalizedStringKey(text)).font(.callout).foregroundStyle(.white.opacity(0.85)).multilineTextAlignment(.center).padding(20)
    }
}

#if os(iOS)
import MediaPlayer

private struct SystemVolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView { MPVolumeView() }
    func updateUIView(_ view: MPVolumeView, context: Context) {}
}

private final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
private struct NativeVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> PlayerSurface {
        let view = PlayerSurface()
        view.playerLayer.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ view: PlayerSurface, context: Context) { view.playerLayer.player = player }
    static func dismantleUIView(_ view: PlayerSurface, coordinator: ()) { view.playerLayer.player = nil }
}
#else
private struct NativeVideoSurface: NSViewRepresentable {
    let player: AVPlayer
    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        let layer = AVPlayerLayer()
        layer.videoGravity = .resizeAspect
        view.layer = layer
        return view
    }
    func updateNSView(_ view: NSView, context: Context) { (view.layer as? AVPlayerLayer)?.player = player }
    static func dismantleNSView(_ view: NSView, coordinator: ()) { (view.layer as? AVPlayerLayer)?.player = nil }
}
#endif
