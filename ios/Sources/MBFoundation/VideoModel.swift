import AVFoundation
import Foundation
import Observation

@MainActor public protocol VideoEngine: AnyObject {
    func load(url: URL, at milliseconds: Int64) async throws
    func load(resource: MediaResource, at milliseconds: Int64) async throws
    func sync(positionMilliseconds: Int64, playing: Bool)
    func stop()
    var errorMessage: String? { get }
    var isBuffering: Bool { get }
}

public extension VideoEngine {
    var isBuffering: Bool { false }
    func load(resource: MediaResource, at milliseconds: Int64) async throws { try await load(url: resource.url, at: milliseconds) }
}

/// mode 仅决定下次点击内容的播放方式；presentationMode 记录当前内容的表现，恢复和传输控制不打开页面。
@MainActor @Observable public final class VideoModel {
    public private(set) var mode: PlaybackMode = .music
    public private(set) var presentationMode: PlaybackMode = .music
    public private(set) var isVisible = false
    public private(set) var isLoading = false
    public private(set) var isReady = false
    public private(set) var isBuffering = false
    public private(set) var errorMessage: String?
    public private(set) var persistenceMessage: String?
    public private(set) var track: Track?
    public private(set) var isSupported: Bool
    public var player: AVPlayer? { (engine as? NativeVideoEngine)?.player }
    @ObservationIgnored private let source: (any VideoSource)?
    @ObservationIgnored private let engine: any VideoEngine
    @ObservationIgnored private let store: (any PlaybackModeStore)?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var preferenceRevision = 0
    @ObservationIgnored private var position: Int64 = 0
    @ObservationIgnored private var playing = false

    public init(source: (any VideoSource)?, engine: any VideoEngine = NativeVideoEngine(), store: (any PlaybackModeStore)? = nil) {
        self.source = source
        self.isSupported = source != nil
        self.engine = engine
        self.store = store
    }

    public func restore() async {
        let current = preferenceRevision
        do {
            if let saved = try await store?.loadPlaybackMode(), current == preferenceRevision {
                mode = saved
            }
        } catch { persistenceMessage = "播放模式读取失败" }
    }

    public func choose(_ mode: PlaybackMode) async {
        preferenceRevision += 1
        self.mode = mode
        if mode == .music {
            presentationMode = .music
            isVisible = false
            release()
        }
        do { try await store?.savePlaybackMode(mode); persistenceMessage = nil }
        catch { persistenceMessage = "播放模式保存失败" }
    }

    public func updateTrack(_ track: Track?) {
        guard self.track?.id != track?.id else { return }
        release()
        self.track = track
        position = 0
        if track == nil || track?.localFileID != nil { isVisible = false; presentationMode = .music }
        if isVisible { load() }
    }

    public func handleSelection(_ track: Track?, intent: PlaybackSelectionIntent, positionMilliseconds: Int64, playing: Bool) {
        if intent == .content {
            release()
            self.track = track
            position = positionMilliseconds
            self.playing = playing
            presentationMode = track?.localFileID == nil ? mode : .music
            isVisible = presentationMode == .video && track != nil
            if isVisible { load() }
        } else {
            updateTrack(track)
            sync(positionMilliseconds: positionMilliseconds, playing: playing)
        }
    }

    public func open() {
        guard presentationMode == .video, track != nil else { return }
        isVisible = true
        if !isReady && task == nil { load() }
    }

    public func browse() { isVisible = false; release() }
    public func retry() { release(); load() }

    public func sync(positionMilliseconds: Int64, playing: Bool) {
        position = positionMilliseconds
        self.playing = playing
        guard isReady else { return }
        if let message = engine.errorMessage { release(); errorMessage = message; return }
        engine.sync(positionMilliseconds: position, playing: playing)
        isBuffering = engine.isBuffering
    }

    public func waitUntilLoaded() async { await task?.value }

    private func load() {
        guard isVisible, let track, let source else { return }
        generation += 1
        let current = generation
        isLoading = true
        errorMessage = nil
        task = Task {
            do {
                let supported = await source.supportsVideo(track: track)
                guard current == generation, !Task.isCancelled else { return }
                isSupported = supported
                guard supported else { isLoading = false; return }
                let resource = try await source.video(track: track)
                try Task.checkCancellation()
                try await engine.load(resource: resource, at: position)
                guard current == generation, !Task.isCancelled else { return }
                engine.sync(positionMilliseconds: position, playing: playing)
                isReady = true
                isBuffering = engine.isBuffering
                isLoading = false
                task = nil
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                release()
                errorMessage = "视频加载失败，点击重试"
            }
        }
    }

    private func release() {
        generation += 1
        task?.cancel()
        task = nil
        engine.stop()
        isReady = false
        isLoading = false
        isBuffering = false
        errorMessage = nil
    }
}

/// 一个静音 AVPlayer 供主画面和展开播放器轮流使用，不启动第二条音轨。
@MainActor public final class NativeVideoEngine: VideoEngine {
    public let player = AVPlayer()
    private var seeking = false
    private var seekGeneration = 0
    private var remote: RemoteMediaAsset?
    public var errorMessage: String? { player.currentItem?.error == nil ? nil : "视频读取失败，点击重试" }
    public var isBuffering: Bool { player.currentItem?.status == .unknown || player.timeControlStatus == .waitingToPlayAtSpecifiedRate }
    public init() { player.isMuted = true }

    public func load(url: URL, at milliseconds: Int64) async throws {
        remote?.cancel()
        remote = nil
        let asset = AVURLAsset(url: url)
        try await prepare(asset, at: milliseconds)
    }

    public func load(resource: MediaResource, at milliseconds: Int64) async throws {
        remote?.cancel()
        let loader = try RemoteMediaAsset(resource: resource)
        remote = loader
        try await prepare(loader.asset, at: milliseconds)
    }

    private func prepare(_ asset: AVURLAsset, at milliseconds: Int64) async throws {
        guard try await asset.load(.isPlayable) else { throw MediaStreamingFailure.unplayable }
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard !tracks.isEmpty else { throw MediaStreamingFailure.unplayable }
        for track in tracks {
            // 首帧需要的远程轨道信息先异步就绪，渲染时不再同步询问服务器。
            _ = try await track.load(.formatDescriptions, .naturalSize, .preferredTransform)
        }
        _ = try await asset.load(.duration)
        try Task.checkCancellation()
        seekGeneration += 1
        seeking = false
        player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
        player.isMuted = true
        _ = await player.seek(to: CMTime(value: max(0, milliseconds), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero)
        try Task.checkCancellation()
    }

    public func sync(positionMilliseconds: Int64, playing: Bool) {
        let seconds = player.currentTime().seconds
        let drift = Double(positionMilliseconds) - (seconds.isFinite ? seconds * 1_000 : 0)
        let buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if abs(drift) > (playing ? 600 : 80), !seeking, !buffering {
            seeking = true
            let current = seekGeneration
            player.seek(to: CMTime(value: max(0, positionMilliseconds), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                Task { @MainActor in
                    if self?.seekGeneration == current { self?.seeking = false }
                }
            }
        }
        if playing {
            let rate: Float = !buffering && abs(drift) > 80 && abs(drift) <= 600 ? (drift > 0 ? 1.03 : 0.97) : 1
            if player.rate != rate { player.rate = rate }
        } else { player.pause() }
    }

    public func stop() {
        seekGeneration += 1
        remote?.cancel()
        remote = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        seeking = false
    }
}
