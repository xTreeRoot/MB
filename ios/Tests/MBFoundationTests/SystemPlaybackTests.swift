import Foundation
@testable import MBFoundation
@preconcurrency import MediaPlayer
import Testing

private let systemTracks = [
    Track(sourceID: "test", sourceTrackID: "one", title: "Song one", artist: "Artist one", durationMilliseconds: 10_000),
    Track(sourceID: "test", sourceTrackID: "two", title: "Song two", artist: "Artist two", durationMilliseconds: 10_000),
]
private let systemLyrics = CaptionTimeline(lines: [
    CaptionLine(startMilliseconds: 1_100, endMilliseconds: 1_400, text: "First line"),
    CaptionLine(startMilliseconds: 1_400, endMilliseconds: 1_800, text: "Second line"),
])

private actor SystemSource: PlaybackSource {
    nonisolated let displayName = "test"
    let timeline: CaptionTimeline
    let delayed: Bool
    private var requested = false
    private var pending: CheckedContinuation<Void, Never>?
    private var waiting: [CheckedContinuation<Void, Never>] = []
    init(timeline: CaptionTimeline = systemLyrics, delayed: Bool = false) {
        self.timeline = timeline; self.delayed = delayed
    }
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) -> MediaResource {
        MediaResource(url: URL(string: "https://example.test/audio")!, headers: [:], fileExtension: "m4a")
    }
    func captions(trackID: String) async -> CaptionTimeline {
        guard trackID == "one" else { return CaptionTimeline(lines: []) }
        if delayed {
            await withCheckedContinuation { continuation in
                pending = continuation; requested = true
                for waiter in waiting { waiter.resume() }; waiting.removeAll()
            }
        }
        return timeline
    }
    func waitForCaptions() async {
        if !requested { await withCheckedContinuation { waiting.append($0) } }
    }
    func releaseCaptions() { pending?.resume(); pending = nil }
}

@MainActor private final class SystemClock: AudioEngine, AudioEngineEvents, AudioEngineStreaming {
    var onUpdate: (() -> Void)?
    var position: Int64 = 0
    func load(url: URL, at milliseconds: Int64) { position = milliseconds }
    func load(resource: MediaResource, at milliseconds: Int64) { position = milliseconds }
    func play() {}
    func pause() {}
    func stop() { position = 0 }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot { AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: 10_000) }
    func advance(to milliseconds: Int64) { position = milliseconds; onUpdate?() }
}

@MainActor private final class SystemInfo {
    var value: [String: Any]?
    var state = MPNowPlayingPlaybackState.stopped
    var writes = 0
    var title: String? { value?[MPMediaItemPropertyTitle] as? String }
    var artist: String? { value?[MPMediaItemPropertyArtist] as? String }
    var rate: Double? { value?[MPNowPlayingInfoPropertyPlaybackRate] as? Double }
}

@MainActor private final class SystemFixture {
    let clock = SystemClock()
    let info = SystemInfo()
    let playback: PlaybackModel
    let coordinator: SystemPlaybackCoordinator
    init(source: SystemSource = SystemSource(), store: (any PlaybackSessionStore)? = nil) {
        playback = PlaybackModel(source: source, engine: clock, store: store)
        let info = self.info
        coordinator = SystemPlaybackCoordinator { value, state in
            info.value = value; info.state = state; info.writes += 1
        }
        coordinator.connect(playback)
    }
}

@Suite(.serialized) struct SystemPlaybackTests {
    // 系统允许任意队列请求封面；此包装只用于模拟 MediaPlayer 的跨队列调用。
    private struct ArtworkRequest: @unchecked Sendable { let artwork: MPMediaItemArtwork }

    @Test @MainActor func systemArtworkCanBeRequestedOffMainActor() async throws {
        let data = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6fJ0AAAAASUVORK5CYII="))
        let request = ArtworkRequest(artwork: try #require(SystemPlaybackCoordinator.makeArtwork(data: data)))
        let size = await Task.detached { request.artwork.image(at: CGSize(width: 1, height: 1))?.size }.value
        #expect(size == CGSize(width: 1, height: 1))
    }

    @Test @MainActor func audioClockUpdatesSameSecondLyricsPauseAndSeekingWithoutAView() async {
        let f = SystemFixture()
        defer { f.coordinator.disconnect() }
        f.playback.select(systemTracks, index: 0)
        await f.playback.waitUntilLoaded()
        #expect(f.info.artist == "Artist one")
        f.clock.advance(to: 1_100)
        #expect(f.info.title == "Song one")
        #expect(f.info.artist == "First line")
        #expect(f.info.rate == 1)
        let first = f.info.writes
        f.clock.advance(to: 1_200)
        #expect(f.info.writes == first)
        f.clock.advance(to: 1_400)
        #expect(f.info.artist == "Second line")
        #expect(f.info.writes == first + 1)
        f.clock.position = 1_500
        f.playback.pause()
        #expect(f.info.artist == "Second line")
        #expect(f.info.rate == 0)
        #expect(f.info.state == .paused)
        f.playback.seek(to: 1_150)
        #expect(f.info.artist == "First line")
        let sought = f.info.writes
        f.playback.seek(to: 1_180)
        #expect(f.info.writes == sought + 1)
        #expect(f.info.value?[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? Double == 1.18)
        #expect(f.info.value?[MPMediaItemPropertyPlaybackDuration] as? Double == 10)
        f.playback.play()
        #expect(f.info.rate == 1)
    }

    @Test @MainActor func gapsBlankLinesNoLyricsAndSwitchingRestoreRealArtist() async {
        let lines = CaptionTimeline(lines: [
            CaptionLine(startMilliseconds: 1_000, endMilliseconds: 2_000, text: "  Hello\n world \t ", translation: "你好"),
            CaptionLine(startMilliseconds: 2_000, endMilliseconds: 3_000, text: " \n "),
        ])
        let f = SystemFixture(source: SystemSource(timeline: lines))
        defer { f.coordinator.disconnect() }
        f.playback.select(systemTracks, index: 0)
        await f.playback.waitUntilLoaded()
        #expect(f.info.artist == "Artist one")
        f.clock.advance(to: 1_100)
        #expect(f.info.artist == "Hello world")
        f.coordinator.setLyricsEnabled(false)
        #expect(f.info.artist == "Artist one")
        f.coordinator.setLyricsEnabled(true)
        #expect(f.info.artist == "Hello world")
        for position: Int64 in [2_000, 3_000, 9_999, 0] {
            f.clock.advance(to: position)
            #expect(f.info.artist == "Artist one")
        }
        f.playback.next()
        await f.playback.waitUntilLoaded()
        #expect(f.info.title == "Song two")
        #expect(f.info.artist == "Artist two")
    }

    @Test @MainActor func lateCaptionsPublishWhilePausedWithoutAnotherClockEvent() async throws {
        let source = SystemSource(delayed: true), f = SystemFixture(source: source)
        defer { f.coordinator.disconnect() }
        f.playback.select(systemTracks, index: 0)
        await source.waitForCaptions()
        for _ in 0..<1_000 where f.playback.state == .loading { await Task.yield() }
        try #require(f.playback.state == .playing)
        f.clock.position = 1_150
        f.playback.pause()
        #expect(f.info.artist == "Artist one")
        await source.releaseCaptions()
        await f.playback.waitUntilLoaded()
        #expect(f.info.artist == "First line")
        #expect(f.info.state == .paused)
        #expect(f.info.rate == 0)
    }

    @Test @MainActor func cancelledOldCaptionsCannotOverwriteTheNextSongsMetadata() async {
        let source = SystemSource(delayed: true), f = SystemFixture(source: source)
        defer { f.coordinator.disconnect() }
        f.playback.select(systemTracks, index: 0)
        await source.waitForCaptions()
        let old = Task { await f.playback.waitUntilLoaded() }
        await Task.yield()
        f.playback.select(systemTracks, index: 1)
        await f.playback.waitUntilLoaded()
        let writes = f.info.writes
        await source.releaseCaptions()
        await old.value
        #expect(f.info.title == "Song two")
        #expect(f.info.artist == "Artist two")
        #expect(f.info.writes == writes)
    }

    @Test @MainActor func clearingReconnectAndQueueEditsUpdateMetadataAndRemoteAvailability() {
        let f = SystemFixture(), commands = MPRemoteCommandCenter.shared()
        defer { f.coordinator.disconnect() }
        f.playback.select(systemTracks, index: 0, autoplay: false)
        #expect(commands.nextTrackCommand.isEnabled)
        f.playback.removeQueue(IndexSet(integersIn: 0..<2))
        #expect(f.info.value == nil)
        #expect(f.info.state == .stopped)
        #expect(!commands.playCommand.isEnabled)
        f.playback.select([systemTracks[0]], index: 0, autoplay: false)
        #expect(f.info.title == "Song one")
        #expect(!commands.nextTrackCommand.isEnabled)
        #expect(!commands.previousTrackCommand.isEnabled)
        f.playback.enqueue(systemTracks[1])
        #expect(commands.nextTrackCommand.isEnabled)
        f.playback.removeQueue(IndexSet(integer: 1))
        #expect(!commands.nextTrackCommand.isEnabled)
        f.coordinator.disconnect()
        #expect(f.info.value == nil)
        f.coordinator.connect(f.playback)
        #expect(f.info.title == "Song one")
        #expect(f.info.state == .paused)
    }

    @Test @MainActor func editingLocalLyricsUpdatesPausedSystemInformation() {
        let f = SystemFixture()
        defer { f.coordinator.disconnect() }
        var track = systemTracks[0]
        track.localFileID = "local-one"
        f.playback.select([track], index: 0, autoplay: false)
        track.localMetadata = LocalAudioMetadata(lyrics: "[00:00.00]Local line")
        f.playback.updateLocalTrack(track)
        #expect(f.info.artist == "Local line")
        track.localMetadata?.lyrics = "[00:00.00]Updated line"
        f.playback.updateLocalTrack(track)
        #expect(f.info.artist == "Updated line")
        #expect(f.info.title == "Song one")
    }

    @Test @MainActor func systemArtworkCanBeRequestedOffMainAfterSelectionAndSessionRestore() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("cover.png")
        let png = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII="))
        try png.write(to: file)
        let store = AppPersistence(directory: directory)
        var f = SystemFixture(store: store)
        defer { f.coordinator.disconnect() }
        var track = systemTracks[0]
        track.artworkURL = file
        f.playback.select([track], index: 0, autoplay: false)
        for pass in 0..<2 {
            if pass == 1 {
                f = SystemFixture(store: AppPersistence(directory: directory))
                await f.playback.restore()
                #expect(f.playback.queue.current?.id == track.id)
                #expect(f.playback.state == .paused)
            }
            for _ in 0..<200 where f.info.value?[MPMediaItemPropertyArtwork] == nil {
                try await Task.sleep(for: .milliseconds(10))
            }
            let artwork = try #require(f.info.value?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork)
            // MediaPlayer 在自己的 accessQueue 请求封面，不能继承创建处的 MainActor。
            let request = BackgroundArtworkRequest(artwork: artwork)
            #expect(await Task.detached { request.hasImage() }.value)
            await f.playback.saveNow()
            f.coordinator.disconnect()
        }
    }
}

private struct BackgroundArtworkRequest: @unchecked Sendable {
    let artwork: MPMediaItemArtwork
    func hasImage() -> Bool { artwork.image(at: CGSize(width: 32, height: 32)) != nil }
}
