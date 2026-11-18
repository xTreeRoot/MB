import Foundation
import MBFoundation
import Testing

@MainActor private final class TestVideo: VideoEngine {
    var loads: [URL] = []
    var positions: [Int64] = []
    var isPlaying = false
    var stopped = 0
    var errorMessage: String?
    func load(url: URL, at milliseconds: Int64) async throws { loads.append(url); positions.append(milliseconds) }
    func sync(positionMilliseconds: Int64, playing: Bool) { positions.append(positionMilliseconds); isPlaying = playing }
    func stop() { stopped += 1; isPlaying = false; errorMessage = nil }
}

private actor VideoFixture: VideoSource, PlaybackModeStore, PlaybackSource {
    nonisolated let displayName = "test"
    var mode: PlaybackMode?
    private var hold = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    private var requested: [String] = []
    func search(query: String, page: Int) -> TrackPage { TrackPage(tracks: [], hasMore: false) }
    func audio(trackID: String) -> MediaResource {
        MediaResource(url: URL(string: "https://media.example/\(trackID).m4a")!, headers: [:], fileExtension: "m4a")
    }
    func captions(trackID: String) -> CaptionTimeline { CaptionTimeline(lines: []) }
    func video(trackID: String) async -> MediaResource {
        requested.append(trackID)
        if hold, trackID == "one" {
            await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        }
        return MediaResource(url: URL(string: "https://media.example/\(trackID).mp4")!, headers: [:], fileExtension: "mp4")
    }
    func block() { hold = true }
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil; hold = false }
    func calls() -> [String] { requested }
    func loadPlaybackMode() -> PlaybackMode? { mode }
    func savePlaybackMode(_ mode: PlaybackMode) { self.mode = mode }
}

private let videoTracks = ["one", "two"].map { Track(sourceID: "test", sourceTrackID: $0, title: $0, artist: "UP", durationMilliseconds: 90_000) }

@Test @MainActor func videoPreferenceOnlyPersistsWithoutOpeningOrLoadingCurrentContent() async {
    let source = VideoFixture()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine, store: source)
    await model.choose(.video)
    #expect(model.mode == .video)
    #expect(await source.mode == .video)
    #expect(!model.isVisible && !model.isLoading)
    model.updateTrack(videoTracks[0])
    model.sync(positionMilliseconds: 42_000, playing: true)
    await model.choose(.video)
    model.open()
    #expect(model.presentationMode == .music)
    #expect(!model.isVisible)
    #expect(await source.calls().isEmpty)
    #expect(engine.loads.isEmpty)
}

@Test @MainActor func restoredVideoPreferenceAndTrackStayPausedWithoutVideoRequests() async {
    let source = VideoFixture()
    await source.savePlaybackMode(.video)
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine, store: source)
    await model.restore()
    model.updateTrack(videoTracks[1])
    model.sync(positionMilliseconds: 42_000, playing: false)
    #expect(model.mode == .video)
    #expect(model.presentationMode == .music)
    #expect(!model.isVisible && !model.isLoading)
    #expect(await source.calls().isEmpty)
    #expect(engine.loads.isEmpty)
}

@Test @MainActor func explicitContentClickAppliesPreferenceIncludingSameTrack() async {
    let source = VideoFixture()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    model.updateTrack(videoTracks[0])
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 42_000, playing: false)
    await model.waitUntilLoaded()
    #expect(model.presentationMode == .video && model.isVisible)
    #expect(engine.positions.first == 42_000)
    #expect(!engine.isPlaying)
    model.sync(positionMilliseconds: 45_000, playing: true)
    #expect(engine.isPlaying)
    #expect(engine.positions.last == 45_000)
    model.browse()
    await model.choose(.video)
    #expect(!model.isVisible)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "one"])
    await model.choose(.music)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    #expect(model.presentationMode == .music && !model.isVisible)
    #expect(await source.calls() == ["one", "one"])
}

@Test(arguments: [PlaybackSelectionIntent.transport, .automatic]) @MainActor
func transportAndAutomaticAdvanceDoNotApplyNewVideoPreference(_ intent: PlaybackSelectionIntent) async {
    let source = VideoFixture()
    let model = VideoModel(source: source, engine: TestVideo())
    model.updateTrack(videoTracks[0])
    await model.choose(.video)
    model.handleSelection(videoTracks[1], intent: intent, positionMilliseconds: 0, playing: true)
    #expect(model.track == videoTracks[1])
    #expect(model.presentationMode == .music)
    #expect(!model.isVisible)
    #expect(await source.calls().isEmpty)
}

@Test @MainActor func videoQueueAdvanceKeepsPresentationAndBrowsingDoesNotReopen() async {
    let source = VideoFixture()
    let model = VideoModel(source: source, engine: TestVideo())
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    model.handleSelection(videoTracks[1], intent: .automatic, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "two"])
    model.browse()
    model.handleSelection(videoTracks[0], intent: .transport, positionMilliseconds: 0, playing: true)
    model.sync(positionMilliseconds: 10_000, playing: true)
    #expect(!model.isVisible)
    #expect(model.presentationMode == .video)
    #expect(await source.calls() == ["one", "two"])
    model.open()
    await model.waitUntilLoaded()
    #expect(model.isVisible)
    #expect(await source.calls() == ["one", "two", "one"])
}

@Test @MainActor func leavingVideoModeRejectsLateResolution() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let old = Task { await model.waitUntilLoaded() }
    await Task.yield()
    await model.choose(.music)
    await source.release()
    await old.value
    #expect(engine.loads.isEmpty)
    #expect(!model.isReady && !model.isVisible)
    #expect(model.presentationMode == .music)
    #expect(model.errorMessage == nil)
}

@Test @MainActor func newContentRejectsOldVideoAndUnsupportedSourceOnlyShowsAfterClick() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let old = Task { await model.waitUntilLoaded() }
    await Task.yield()
    model.handleSelection(videoTracks[1], intent: .content, positionMilliseconds: 0, playing: true)
    await model.waitUntilLoaded()
    await source.release()
    await old.value
    #expect(engine.loads.map(\.lastPathComponent) == ["two.mp4"])
    #expect(model.track == videoTracks[1])
    let unsupported = VideoModel(source: nil, engine: TestVideo())
    unsupported.updateTrack(videoTracks[0])
    await unsupported.choose(.video)
    #expect(!unsupported.isVisible)
    unsupported.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    #expect(unsupported.isVisible && !unsupported.isSupported && !unsupported.isLoading)
}

@Test @MainActor func choosingVideoAgainDoesNotInterruptVideoAlreadyLoadingFromContentClick() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let model = VideoModel(source: source, engine: engine)
    await model.choose(.video)
    model.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    #expect(model.isLoading)
    await model.choose(.video)
    await source.release()
    await model.waitUntilLoaded()
    #expect(model.isReady && !model.isLoading)
    #expect(engine.loads.count == 1)
}

@MainActor private final class ContinuousAudio: AudioEngine {
    var loads = 0
    var starts = 0
    var stops = 0
    var position: Int64 = 0
    var errorMessage: String?
    func load(url: URL, at milliseconds: Int64) { loads += 1; position = milliseconds }
    func play() { starts += 1 }
    func pause() {}
    func stop() { stops += 1 }
    func seek(to milliseconds: Int64) { position = milliseconds }
    func snapshot() -> AudioEngineSnapshot {
        AudioEngineSnapshot(positionMilliseconds: position, durationMilliseconds: 90_000, errorMessage: errorMessage)
    }
}

@Test @MainActor func switchingPreferencesKeepsCurrentAudioTrackAndPositionWithoutRestarting() async {
    let source = VideoFixture()
    let audio = ContinuousAudio()
    let playback = PlaybackModel(source: source, engine: audio, download: { _ in URL(fileURLWithPath: "/tmp/mb-mode-test.m4a") })
    let video = VideoModel(source: source, engine: TestVideo())
    playback.select(videoTracks, index: 0)
    await playback.waitUntilLoaded()
    video.handleSelection(playback.queue.current, intent: playback.selectionIntent, positionMilliseconds: 0, playing: true)
    audio.position = 42_000
    playback.tick()
    let loads = audio.loads, starts = audio.starts, stops = audio.stops
    await video.choose(.video)
    #expect(!video.isVisible && video.presentationMode == .music)
    #expect(await source.calls().isEmpty)
    #expect(playback.positionMilliseconds == 42_000)
    #expect(playback.state == .playing)
    #expect(audio.loads == loads && audio.starts == starts && audio.stops == stops)
    video.handleSelection(playback.queue.current, intent: .content, positionMilliseconds: 42_000, playing: true)
    await video.waitUntilLoaded()
    await video.choose(.music)
    #expect(!video.isVisible && !video.isReady)
    #expect(playback.queue.current == videoTracks[0])
    #expect(playback.positionMilliseconds == 42_000)
    #expect(playback.state == .playing)
    #expect(audio.loads == loads && audio.starts == starts && audio.stops == stops)
}

private actor SlowModeStore: PlaybackModeStore {
    private var continuation: CheckedContinuation<Void, Never>?
    private var waiter: CheckedContinuation<Void, Never>?
    func loadPlaybackMode() async -> PlaybackMode? {
        await withCheckedContinuation { continuation = $0; waiter?.resume(); waiter = nil }
        return .video
    }
    func savePlaybackMode(_ mode: PlaybackMode) {}
    func waitBlocked() async {
        if continuation != nil { return }
        await withCheckedContinuation { waiter = $0 }
    }
    func release() { continuation?.resume(); continuation = nil }
}

@Test @MainActor func lateRestoredPreferenceCannotOverrideExplicitMusicChoice() async {
    let store = SlowModeStore()
    let model = VideoModel(source: VideoFixture(), engine: TestVideo(), store: store)
    let restoring = Task { await model.restore() }
    await store.waitBlocked()
    await model.choose(.music)
    await store.release()
    await restoring.value
    #expect(model.mode == .music)
    #expect(!model.isVisible)
}

@Test @MainActor func closingVideoKeepsSavedPreferenceAndIgnoresLateLoadRetryAndPolling() async {
    let source = VideoFixture()
    await source.block()
    let engine = TestVideo()
    let video = VideoModel(source: source, engine: engine, store: source)
    await video.choose(.video)
    video.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await source.waitBlocked()
    let loading = Task { await video.waitUntilLoaded() }
    await Task.yield()
    video.browse()
    #expect(video.mode == .video)
    #expect(await source.mode == .video)
    #expect(!video.isVisible && !video.isLoading)
    await source.release()
    await loading.value
    #expect(engine.loads.isEmpty)
    await video.restore()
    video.updateTrack(videoTracks[0])
    for index in 0..<20 {
        video.sync(positionMilliseconds: Int64(index * 250), playing: index.isMultiple(of: 2))
    }
    video.retry()
    #expect(video.mode == .video)
    #expect(!video.isVisible && !video.isLoading && !video.isReady)
    #expect(await source.calls() == ["one"])
    #expect(engine.loads.isEmpty)
    video.handleSelection(videoTracks[0], intent: .content, positionMilliseconds: 0, playing: true)
    await video.waitUntilLoaded()
    #expect(video.isVisible && video.isReady)
    #expect(await source.calls() == ["one", "one"])
    #expect(engine.loads.count == 1)
}

private actor RestoredSession: PlaybackSessionStore {
    func loadSession() -> PlaybackSession? {
        PlaybackSession(queue: videoTracks, index: 0, positionMilliseconds: 42_000)
    }
    func saveSession(_ session: PlaybackSession) {}
}

@Test @MainActor func sessionResumePauseAndAudioRetryNeverEmitContentSelectionOrReopenVideo() async {
    let source = VideoFixture()
    await source.savePlaybackMode(.video)
    let engine = ContinuousAudio()
    let playback = PlaybackModel(source: source, engine: engine, store: RestoredSession(), download: { _ in
        FileManager.default.temporaryDirectory.appendingPathComponent("mb-resume-test-\(UUID()).m4a")
    })
    let video = VideoModel(source: source, engine: TestVideo(), store: source)
    await video.restore()
    await playback.restore()
    video.updateTrack(playback.queue.current)
    let revision = playback.selectionRevision
    #expect(revision == 0 && playback.state == .paused)
    playback.play()
    await playback.waitUntilLoaded()
    video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
    playback.pause()
    video.sync(positionMilliseconds: playback.positionMilliseconds, playing: false)
    playback.play()
    #expect(engine.loads == 1)
    engine.errorMessage = "test interrupted stream"
    playback.tick()
    #expect(playback.state == .failed)
    engine.errorMessage = nil
    playback.play()
    await playback.waitUntilLoaded()
    for _ in 0..<20 {
        playback.tick()
        video.sync(positionMilliseconds: playback.positionMilliseconds, playing: playback.state == .playing)
    }
    #expect(engine.loads == 2)
    #expect(playback.state == .playing && playback.positionMilliseconds == 42_000)
    #expect(playback.selectionRevision == revision)
    #expect(video.mode == .video && !video.isVisible && !video.isLoading)
    #expect(await source.calls().isEmpty)
}
