import Foundation
import Observation

public enum PlaybackState: Sendable { case paused, loading, playing, failed }
public enum PlaybackSelectionIntent: Sendable { case content, transport, automatic }

/// 恢复只选中曲目和进度；用户播放时重新解析地址。失败重试、切曲和取消使用同一代次隔离。
@MainActor @Observable
public final class PlaybackModel {
    public let queue = PlaybackQueue()
    public private(set) var selectionRevision = 0
    public private(set) var selectionIntent: PlaybackSelectionIntent = .content
    public private(set) var state: PlaybackState = .paused
    public private(set) var positionMilliseconds: Int64 = 0
    public private(set) var bassLevel = 0.0
    public private(set) var durationMilliseconds: Int64 = 0
    public private(set) var timeline = CaptionTimeline(lines: [])
    public private(set) var errorMessage: String?
    public private(set) var captionMessage: String?
    public private(set) var persistenceMessage: String?
    public private(set) var controlRevision = 0
    public private(set) var station: PlaybackStation?
    @ObservationIgnored public var stateDidChange: (() -> Void)?
    @ObservationIgnored private let source: (any PlaybackSource)?
    @ObservationIgnored private let engine: any AudioEngine
    @ObservationIgnored private let store: (any PlaybackSessionStore)?
    @ObservationIgnored private let mediaLibrary: MediaLibrary?
    @ObservationIgnored private let download: @Sendable (MediaResource) async throws -> URL
    @ObservationIgnored private var loadingTask: Task<Void, Never>?
    @ObservationIgnored private var captionTask: Task<Void, Never>?
    @ObservationIgnored private var cacheTask: Task<Void, Never>?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var ready = false
    @ObservationIgnored private var wantsPlay = false
    @ObservationIgnored private var localFile: URL?
    @ObservationIgnored private var savedPosition: Int64 = -1
    @ObservationIgnored private var saveRevision = 0
    @ObservationIgnored private var restored = false
    @ObservationIgnored private var stationTask: Task<Void, Never>?
    @ObservationIgnored private var stationRevision = 0
    @ObservationIgnored private var stationAdvance = false

    public init(source: (any PlaybackSource)?, engine: any AudioEngine, store: (any PlaybackSessionStore)? = nil,
                mediaLibrary: MediaLibrary? = nil,
                download: @escaping @Sendable (MediaResource) async throws -> URL = { try await NetworkClient.shared.download($0) }) {
        self.source = source
        self.engine = engine
        self.store = store
        self.download = download
        self.mediaLibrary = mediaLibrary
        if let events = engine as? any AudioEngineEvents { events.onUpdate = { [weak self] in self?.tick() } }
    }

    public func restore() async {
        defer { stateDidChange?() }
        guard !restored else { return }
        restored = true
        do {
            guard let session = try await store?.loadSession(), queue.current == nil,
                  session.queue.indices.contains(session.index) else { return }
            queue.replace(with: session.queue, selecting: session.index)
            station = session.station
            queue.restorePreferences(shuffled: session.shuffled ?? false, repeatMode: session.repeatMode ?? .off, order: session.playOrder)
            durationMilliseconds = queue.current?.durationMilliseconds ?? 0
            positionMilliseconds = durationMilliseconds > 0 ? max(0, min(session.positionMilliseconds, durationMilliseconds - 1)) : max(0, session.positionMilliseconds)
            timeline = CaptionTimeline(lines: session.captions ?? [])
            state = .paused
        } catch { persistenceMessage = "播放记录读取失败" }
    }

    public func select(_ tracks: [Track], index: Int, intent: PlaybackSelectionIntent = .content, autoplay: Bool = true) {
        guard tracks.indices.contains(index) else { return }
        endStation()
        queue.replace(with: tracks, selecting: index)
        jump(to: index, intent: intent, autoplay: autoplay)
    }

    public func jump(to index: Int, intent: PlaybackSelectionIntent = .content, autoplay: Bool = true) {
        guard queue.tracks.indices.contains(index) else { return }
        stationAdvance = false
        defer { stateDidChange?() }
        reset()
        selectionIntent = intent
        selectionRevision += 1
        queue.select(index: index)
        durationMilliseconds = queue.current?.durationMilliseconds ?? 0
        persistSoon()
        if autoplay { play() }
    }

    public func next() {
        if station != nil, queue.index == queue.tracks.indices.last { refillStation(advance: true); return }
        if let index = queue.nextIndex(automatic: false) { jump(to: index, intent: .transport) }
    }
    public func previous() {
        if let index = queue.previousIndex() { jump(to: index, intent: .transport) }
    }

    public func startStation(_ station: PlaybackStation, tracks: [Track], index: Int) {
        select(tracks, index: index)
        self.station = station
        persistSoon()
        refillStation()
    }
    public func endStation() {
        stationRevision += 1; stationTask?.cancel(); stationTask = nil; stationAdvance = false; station = nil
        persistSoon()
    }
    private func refillStation(advance: Bool = false) {
        guard let station, let provider = source as? any StationPlaybackSource else { return }
        stationAdvance = stationAdvance || advance
        guard stationTask == nil, advance || queue.tracks.count - (queue.index ?? 0) <= 2 else { return }
        let revision = stationRevision
        stationTask = Task {
            do {
                var tracks: [Track] = []
                for _ in 0..<3 where tracks.isEmpty { tracks = try await provider.stationNext(station).filter { $0.unavailable != true }; try Task.checkCancellation() }
                guard revision == stationRevision, !Task.isCancelled else { return }
                stationTask = nil
                guard !tracks.isEmpty else { stationAdvance = false; errorMessage = "电台暂无可播放曲目"; stateDidChange?(); return }
                let next = queue.tracks.count
                for track in tracks { queue.append(track) }
                let advance = stationAdvance; stationAdvance = false
                if advance { jump(to: next, intent: .automatic) }
                persistSoon(); stateDidChange?()
            } catch {
                guard revision == stationRevision, !Task.isCancelled else { return }
                stationTask = nil; stationAdvance = false
                errorMessage = AppLocalization.text("电台加载失败：%@", arguments: [error.localizedDescription]); stateDidChange?()
            }
        }
    }

    public func toggleShuffle() { queue.toggleShuffle(); persistSoon(); stateDidChange?() }
    public func setVolume(_ volume: Double) { (engine as? any AudioEngineVolume)?.setVolume(volume) }
    public func setNormalization(_ mode: AudioNormalization) { (engine as? any AudioEngineNormalization)?.setNormalization(mode) }
    public func updateLocalTrack(_ track: Track) {
        guard track.localFileID != nil, queue.update(track) else { return }
        if queue.current?.id == track.id {
            timeline = .lrc(track.localMetadata?.lyrics ?? "", durationMilliseconds: track.durationMilliseconds)
            captionMessage = timeline.lines.isEmpty ? "本地文件没有带时间的歌词" : nil
        }
        persistSoon(); stateDidChange?()
    }
    public func cycleRepeat() { queue.cycleRepeat(); persistSoon(); stateDidChange?() }
    public func enqueue(_ track: Track, next: Bool = false) {
        let empty = queue.current == nil
        queue.append(track, next: next)
        if empty { durationMilliseconds = track.durationMilliseconds }
        persistSoon(); stateDidChange?()
    }
    public func moveQueue(_ offsets: IndexSet, to destination: Int) { queue.move(offsets, to: destination); persistSoon(); stateDidChange?() }
    public func removeQueue(_ offsets: IndexSet) {
        let removedCurrent = queue.remove(offsets)
        if queue.tracks.isEmpty { endStation(); reset(); durationMilliseconds = 0 }
        else if removedCurrent, let index = queue.index { jump(to: index, intent: .transport) }
        persistSoon(); stateDidChange?()
    }

    public func toggle() {
        if state == .playing || (state == .loading && wantsPlay) { pause() }
        else { play() }
    }

    public func play() {
        defer { stateDidChange?() }
        controlRevision += 1
        guard let track = queue.current else { return }
        guard track.unavailable != true else { fail("此曲目暂不可播放"); return }
        guard source != nil || mediaLibrary != nil else { errorMessage = "本地示例没有音频"; return }
        wantsPlay = true
        errorMessage = nil
        if ready {
            do { try engine.play(); state = .playing }
            catch { fail("音频播放失败，点击播放重试") }
            return
        }
        guard loadingTask == nil else { state = .loading; return }
        state = .loading
        generation += 1
        let current = generation
        let start = positionMilliseconds
        captionTask?.cancel()
        captionTask = Task {
            if track.localFileID != nil {
                timeline = .lrc(track.localMetadata?.lyrics ?? "", durationMilliseconds: track.durationMilliseconds)
                captionMessage = timeline.lines.isEmpty ? "本地文件没有带时间的歌词" : nil
                return
            }
            guard let source else { return }
            let result = await Self.loadCaptions(source, track: track)
            guard current == generation, !Task.isCancelled else { return }
            if result.1 == nil { timeline = result.0; persistSoon() }
            captionMessage = result.1
        }
        loadingTask = Task {
            defer { stateDidChange?() }
            do {
                var file: URL?
                var streamingResource: MediaResource?
                if let cached = try await mediaLibrary?.playbackFile(for: track) { file = cached }
                else {
                    guard track.localFileID == nil, let source else { throw CocoaError(.fileNoSuchFile) }
                    let resource = try await source.audio(track: track)
                    try Task.checkCancellation()
                    if let streaming = engine as? any AudioEngineStreaming {
                        do {
                            try await streaming.load(resource: resource, at: start)
                            streamingResource = resource
                        } catch {
                            try Task.checkCancellation()
                            guard current == generation else { return }
                            // 不支持 Range 的媒体服务器仍可通过完整下载播放。
                            engine.stop()
                            file = try await download(resource)
                        }
                    } else { file = try await download(resource) }
                }
                guard current == generation, !Task.isCancelled else {
                    if let file { try? FileManager.default.removeItem(at: file) }
                    return
                }
                if let file {
                    localFile = file
                    try await engine.load(url: file, at: start)
                    if track.localFileID == nil { try? await mediaLibrary?.cache(file: file, track: track) }
                }
                guard current == generation, !Task.isCancelled else { return }
                // 加载时拖动进度也要保留，不能被开始下载时的旧位置盖回去。
                if positionMilliseconds != start { engine.seek(to: positionMilliseconds) }
                ready = true
                refillStation()
                loadingTask = nil
                if wantsPlay { try engine.play(); state = .playing }
                else { state = .paused }
                if let resource = streamingResource, let mediaLibrary, await mediaLibrary.cacheEnabled(), current == generation, !Task.isCancelled {
                    cacheTask = Task {
                        do {
                            let cached = try await download(resource)
                            defer { try? FileManager.default.removeItem(at: cached) }
                            try Task.checkCancellation()
                            try await mediaLibrary.cache(file: cached, track: track)
                        } catch { /* 缓存失败不打断已经开始的分段播放。 */ }
                    }
                }
            } catch {
                guard current == generation, !Task.isCancelled else { return }
                loadingTask = nil
                fail(AppLocalization.text("音频加载失败：%@", arguments: [error.localizedDescription]))
            }
        }
    }

    public func pause() {
        defer { stateDidChange?() }
        controlRevision += 1
        stationAdvance = false
        wantsPlay = false
        if ready {
            let snapshot = engine.snapshot()
            bassLevel = snapshot.bassLevel
            positionMilliseconds = max(0, snapshot.positionMilliseconds)
        }
        engine.pause()
        state = .paused
        persistSoon()
    }

    public func seek(to milliseconds: Int64) {
        defer { stateDidChange?() }
        controlRevision += 1
        positionMilliseconds = max(0, min(milliseconds, max(0, durationMilliseconds - 1)))
        if ready { engine.seek(to: positionMilliseconds) }
        persistSoon()
    }

    public func tick() {
        defer { stateDidChange?() }
        guard ready else { return }
        let snapshot = engine.snapshot()
        bassLevel = snapshot.bassLevel
        positionMilliseconds = max(0, snapshot.positionMilliseconds)
        if snapshot.durationMilliseconds > 0 { durationMilliseconds = snapshot.durationMilliseconds }
        if let error = snapshot.errorMessage { fail(error); return }
        if snapshot.finished && wantsPlay {
            if station != nil, queue.index == queue.tracks.indices.last {
                wantsPlay = false; engine.pause(); state = .paused; refillStation(advance: true); return
            }
            if let index = queue.nextIndex(automatic: true) { jump(to: index, intent: .automatic) }
            else { wantsPlay = false; engine.stop(); state = .paused; ready = false; removeFile(); persistSoon() }
            return
        }
        if abs(positionMilliseconds - savedPosition) >= 5_000 { persistSoon() }
    }

    public func saveNow() async {
        let pending = saveTask
        pending?.cancel()
        await pending?.value
        await writeSession()
    }

    private func writeSession() async {
        guard let store else { return }
        let session = PlaybackSession(queue: queue.tracks, index: queue.index ?? 0, positionMilliseconds: positionMilliseconds, captions: timeline.lines,
            shuffled: queue.shuffled, repeatMode: queue.repeatMode, playOrder: queue.playOrder, station: station)
        do { try await store.saveSession(session); persistenceMessage = nil; savedPosition = session.positionMilliseconds }
        catch { persistenceMessage = "播放记录保存失败" }
    }

    public func waitUntilLoaded() async { await loadingTask?.value; await captionTask?.value }

    private func reset() {
        generation += 1
        loadingTask?.cancel()
        loadingTask = nil
        captionTask?.cancel()
        captionTask = nil
        cacheTask?.cancel(); cacheTask = nil
        engine.stop()
        ready = false
        wantsPlay = false
        positionMilliseconds = 0; bassLevel = 0
        timeline = CaptionTimeline(lines: [])
        captionMessage = nil
        errorMessage = nil
        state = .paused
        removeFile()
    }

    private func fail(_ message: String) {
        engine.stop()
        ready = false
        wantsPlay = false
        state = .failed
        errorMessage = message
        // 保留曲目、字幕与位置；下一次播放会重取临时地址。
        removeFile()
        persistSoon()
    }

    private func removeFile() {
        if let localFile { try? FileManager.default.removeItem(at: localFile) }
        localFile = nil
    }

    private func persistSoon() {
        guard store != nil else { return }
        saveRevision += 1
        guard saveTask == nil else { return }
        saveTask = Task {
            // 合并同一轮状态更新；持续 tick 不取消待保存任务，避免一直播放却一直不落盘。
            await Task.yield()
            while !Task.isCancelled {
                let revision = saveRevision
                await writeSession()
                if revision == saveRevision { break }
            }
            saveTask = nil
        }
    }

    private static func loadCaptions(_ source: any PlaybackSource, track: Track) async -> (CaptionTimeline, String?) {
        do { return (try await source.captions(track: track), nil) }
        catch { return (CaptionTimeline(lines: []), error.localizedDescription) }
    }
}
