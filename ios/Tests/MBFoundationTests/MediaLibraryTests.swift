import Foundation
@testable import MBFoundation
import Testing

@Test func cacheEvictsOldFilesButKeepsDownloadsAndActivePlaybackCopy() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent("fixture.m4a")
    try Data(repeating: 1, count: 700_000).write(to: file)
    let store = MediaLibrary(directory: directory.appendingPathComponent("library"))
    try await store.setCacheLimit(megabytes: 1)
    let first = Track(sourceID: "test", sourceTrackID: "1", title: "one", artist: "UP")
    let second = Track(sourceID: "test", sourceTrackID: "2", title: "two", artist: "UP")
    try await store.cache(file: file, track: first)
    let active = try #require(try await store.playbackFile(for: first))
    defer { try? FileManager.default.removeItem(at: active) }
    try await store.saveDownload(file: file, track: first)
    try await store.cache(file: file, track: second)
    #expect(try await store.cacheBytes() == 700_000)
    try await store.clearCache()
    #expect(try await store.cacheBytes() == 0)
    #expect(try await store.list().map(\.track.id) == [first.id])
    #expect(FileManager.default.fileExists(atPath: active.path))
    let reopened = MediaLibrary(directory: directory.appendingPathComponent("library"))
    let saved = try #require(try await reopened.list().first)
    let export = try await reopened.export(saved)
    defer { try? FileManager.default.removeItem(at: export) }
    #expect(try Data(contentsOf: export) == Data(contentsOf: file))
    try await reopened.remove(saved)
    #expect(try await reopened.list().isEmpty)
    #expect(FileManager.default.fileExists(atPath: active.path))
}

@Test func zeroCacheAndOversizedFilesDoNotBecomePersistentEntries() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent("fixture.m4a")
    try Data(repeating: 0, count: 1_100_000).write(to: file)
    let store = MediaLibrary(directory: directory.appendingPathComponent("library"))
    let track = Track(sourceID: "test", sourceTrackID: "1", title: "one", artist: "UP")
    try await store.setCacheLimit(megabytes: 0)
    try await store.cache(file: file, track: track)
    #expect(try await store.playbackFile(for: track) == nil)
    try await store.setCacheLimit(megabytes: 1)
    try await store.cache(file: file, track: track)
    #expect(try await store.playbackFile(for: track) == nil)
}

@MainActor private final class LocalEngine: AudioEngine {
    var url: URL?
    func load(url: URL, at milliseconds: Int64) { self.url = url }
    func play() {}
    func pause() {}
    func stop() {}
    func seek(to milliseconds: Int64) {}
    func snapshot() -> AudioEngineSnapshot { AudioEngineSnapshot(positionMilliseconds: 0, durationMilliseconds: 1_000) }
}

@Test @MainActor func importedAudioPlaysWithoutAnyRemoteSourceOrDownload() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let file = directory.appendingPathComponent("fixture.m4a")
    try Data(repeating: 0, count: 20).write(to: file)
    let store = MediaLibrary(directory: directory.appendingPathComponent("library")), engine = LocalEngine()
    var track = Track(sourceID: "local", sourceTrackID: "file", title: "local", artist: "local", durationMilliseconds: 1_000)
    track.localFileID = "file"
    try await store.saveDownload(file: file, track: track)
    let playback = PlaybackModel(source: nil, engine: engine, mediaLibrary: store, download: { _ in Issue.record("本地文件不应下载"); throw URLError(.badURL) })
    playback.select([track], index: 0)
    await playback.waitUntilLoaded()
    #expect(playback.state == .playing)
    #expect(engine.url != file)
    #expect(engine.url.map { FileManager.default.fileExists(atPath: $0.path) } == true)
    playback.removeQueue(IndexSet(integer: 0))
}

@Test func mediaLibraryRestoresArtworkAfterContainerMoveAndRemovesUnusedCovers() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let original = root.appendingPathComponent("original"), moved = root.appendingPathComponent("moved")
    let library = MediaLibrary(directory: original)
    let cover = try await library.saveArtwork(Data([1, 2, 3]))
    let file = root.appendingPathComponent("audio.wav")
    try Data([1, 2]).write(to: file)
    let track = Track(sourceID: "local", sourceTrackID: "1", title: "one", artist: "artist", artworkURL: cover)
    try await library.saveDownload(file: file, track: track)
    try FileManager.default.moveItem(at: original, to: moved)
    let reopened = MediaLibrary(directory: moved)
    let item = try #require(try await reopened.list().first)
    #expect(item.track.artworkURL?.deletingLastPathComponent() == moved.appendingPathComponent("covers"))
    #expect(try Data(contentsOf: #require(item.track.artworkURL)) == Data([1,2,3]))
    try await reopened.remove(item)
    #expect(!FileManager.default.fileExists(atPath: item.track.artworkURL!.path))
}
