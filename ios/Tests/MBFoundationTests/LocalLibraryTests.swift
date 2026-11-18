import AVFoundation
import Foundation
@testable import MBFoundation
import Testing

@Test func playlistsFavoritesAndRecentSurviveRelaunchWithMissingFilesKeptForReimport() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = LocalLibraryStore(directory: directory)
    var state = try await store.createPlaylist("  通勤  ")
    let id = try #require(state.playlists.first?.id)
    state = try await store.addTracks(["a", "missing", "b", "c", "a"], to: id)
    #expect(state.playlists[0].trackIDs == ["a", "missing", "b", "c"])
    state = try await store.moveTracks(in: id, offsets: IndexSet(integer: 2), to: 0, available: ["a", "b", "c"])
    #expect(state.playlists[0].trackIDs == ["c", "missing", "a", "b"])
    _ = try await store.renamePlaylist(id, name: "  夜路 ")
    _ = try await store.setFavorite("missing", on: true)
    _ = try await store.setFavorite("a", on: true)
    _ = try await store.recordPlay("a")
    _ = try await store.recordPlay("b")
    _ = try await store.recordPlay("a")
    let reopened = LocalLibraryStore(directory: directory)
    state = try await reopened.snapshot()
    #expect(state.playlists[0].name == "夜路")
    #expect(state.playlists[0].trackIDs == ["c", "missing", "a", "b"])
    #expect(state.favorites == ["a", "missing"])
    #expect(state.recent == ["a", "b"])
    state = try await reopened.removeTracks(["a"], from: id)
    #expect(state.playlists[0].trackIDs == ["c", "missing", "b"])
    _ = try await reopened.toggleFavorite("a")
    state = try await reopened.deletePlaylist(id)
    #expect(state.playlists.isEmpty)
    #expect(state.favorites == ["missing"])
}

@Test func invalidPlaylistEditsAndFailedPersistenceDoNotAlterUserState() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = LocalLibraryStore(directory: directory)
    let original = try await store.createPlaylist("keep")
    let id = try #require(original.playlists.first?.id)
    await #expect(throws: LocalLibraryFailure.self) { try await store.createPlaylist(" \n ") }
    await #expect(throws: LocalLibraryFailure.self) { try await store.renamePlaylist("missing", name: "bad") }
    await #expect(throws: LocalLibraryFailure.self) { try await store.moveTracks(in: id, offsets: IndexSet(integer: 0), to: 0, available: []) }
    #expect(try await store.snapshot() == original)
    // 确切的写入失败：父目录变成普通文件，actor 仍保留上一次成功的快照。
    try FileManager.default.removeItem(at: directory)
    try Data("occupied".utf8).write(to: directory)
    await #expect(throws: (any Error).self) { try await store.setFavorite("a", on: true) }
    #expect(try await store.snapshot() == original)
}

private func catalogTrack(_ id: String, title: String, artist: String, album: String, albumArtist: String? = nil, folder: String = "one", number: Int) -> Track {
    var track = Track(sourceID: "local", sourceTrackID: id, title: title, artist: artist)
    track.localFileID = id
    track.localMetadata = LocalAudioMetadata(album: album, albumArtist: albumArtist, trackNumber: number, folderID: folder)
    return track
}

@Test func localCatalogGroupsByAlbumArtistOrFolderSortsTracksAndPreservesArtistCommas() {
    let tracks = [
        catalogTrack("a", title: "Last", artist: "Earth, Wind & Fire", album: "Greatest Hits", number: 2),
        catalogTrack("b", title: "First", artist: "Earth, Wind & Fire", album: "Greatest Hits", number: 1),
        catalogTrack("c", title: "Other", artist: "Someone Else", album: "Greatest Hits", folder: "two", number: 1),
        catalogTrack("d", title: "Duet", artist: "Singer A feat. Singer B", album: "Together", albumArtist: "Various", folder: "one", number: 2),
        catalogTrack("e", title: "Solo", artist: "Singer C", album: "Together", albumArtist: "Various", folder: "two", number: 1),
    ]
    let catalog = LocalCatalog(tracks: tracks)
    #expect(catalog.albums.count == 3)
    #expect(catalog.albums.first { $0.tracks.contains { $0.sourceTrackID == "a" } }?.tracks.map(\.sourceTrackID) == ["b", "a"])
    #expect(catalog.albums.first { $0.title == "Together" }?.tracks.map(\.sourceTrackID) == ["e", "d"])
    #expect(catalog.artists.contains { $0.title == "Earth, Wind & Fire" })
    #expect(catalog.artists.contains { $0.title == "Singer B" && $0.tracks.count == 1 })
    #expect(catalog.artists.contains { $0.title == "Various" && $0.tracks.count == 2 })
    #expect(catalog.search("first EARTH").map(\.sourceTrackID) == ["b"])
    #expect(catalog.search("Together singer").count == 2)
    #expect(LocalCatalog(tracks: tracks.reversed()).albums.map(\.id) == catalog.albums.map(\.id))
}

@Test func lrcSupportsMultipleTagsOffsetTranslationsAndTimedSilence() {
    let timeline = CaptionTimeline.lrc("""
    [ar:Test]
    [offset:-500]
    [00:01.00][00:05.000]Hello
    [00:01.00]你好
    [00:03.5]
    [99999999999999999999:00]bad
    [00:07.00]Last
    """, durationMilliseconds: 9_000)
    #expect(timeline.lines.map(\.startMilliseconds) == [500, 4_500, 6_500])
    #expect(timeline.lines.first?.text == "Hello\n你好")
    #expect(timeline.lines.first?.endMilliseconds == 3_000)
    #expect(timeline.activeIndex(at: 3_200) == nil)
    #expect(timeline.lines.last?.endMilliseconds == 9_000)
}

@Test func audioImportReadsRealTagsAndDuplicateContentKeepsIdentity() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let input = directory.appendingPathComponent("tone.wav")
    // 0.1 秒单声道 PCM，用系统导出器生成真正包含标签的 M4A。
    var bytes = Data()
    func append<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { bytes.append(contentsOf: $0) } }
    let samples: UInt32 = 4_410 * 2
    bytes.append(Data("RIFF".utf8)); append(samples + 36); bytes.append(Data("WAVEfmt ".utf8))
    append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(44_100)); append(UInt32(88_200)); append(UInt16(2)); append(UInt16(16))
    bytes.append(Data("data".utf8)); append(samples); bytes.append(Data(repeating: 0, count: Int(samples)))
    try bytes.write(to: input)
    let output = directory.appendingPathComponent("tagged.m4a")
    let session = try #require(AVAssetExportSession(asset: AVURLAsset(url: input), presetName: AVAssetExportPresetAppleM4A))
    let fields: [(AVMetadataIdentifier, String)] = [(.commonIdentifierTitle, "Fixture Title"), (.commonIdentifierArtist, "Fixture Artist"), (.commonIdentifierAlbumName, "Fixture Album"), (.iTunesMetadataAlbumArtist, "Fixture Band"), (.iTunesMetadataLyrics, "[00:00.01]Hello")]
    session.metadata = fields.map { identifier, value in
        let item = AVMutableMetadataItem(); item.identifier = identifier; item.value = value as NSString; item.extendedLanguageTag = "und"; return item
    }
    try await session.export(to: output, as: .m4a)
    let media = MediaLibrary(directory: directory.appendingPathComponent("media"))
    let importer = LocalAudioImporter(library: media)
    let first = try await importer.importFile(output)
    #expect(first.title == "Fixture Title")
    #expect(first.artist == "Fixture Artist")
    #expect(first.localMetadata?.album == "Fixture Album")
    #expect(first.localMetadata?.albumArtist == "Fixture Band")
    #expect(first.localMetadata?.lyrics == "[00:00.01]Hello")
    #expect(first.durationMilliseconds > 0)
    let secondURL = directory.appendingPathComponent("renamed.m4a")
    try FileManager.default.copyItem(at: output, to: secondURL)
    let second = try await importer.importFile(secondURL)
    #expect(first.id == second.id)
    #expect(try await media.list().count == 1)
    let sidecar = output.deletingPathExtension().appendingPathExtension("lrc")
    try "[00:00.01]Sidecar lyric".write(to: sidecar, atomically: true, encoding: .utf8)
    let withSidecar = try await importer.importFile(output)
    #expect(withSidecar.localMetadata?.lyrics == "[00:00.01]Sidecar lyric")
    let attached = directory.appendingPathComponent("attached.lrc")
    try "[00:00.01]Attached lyric".write(to: attached, atomically: true, encoding: .utf8)
    try await importer.attachLyrics(attached, to: withSidecar)
    let saved = try #require(try await MediaLibrary(directory: directory.appendingPathComponent("media")).list().first)
    #expect(saved.track.localMetadata?.lyrics == "[00:00.01]Attached lyric")
    try "Untimed text".write(to: attached, atomically: true, encoding: .utf8)
    await #expect(throws: (any Error).self) { try await importer.attachLyrics(attached, to: saved.track) }
    #expect(try await media.list().first?.track.localMetadata?.lyrics == "[00:00.01]Attached lyric")
}

@Test func folderImportFindsNestedAudioButSkipsHiddenFilesAndSymlinks() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let nested = directory.appendingPathComponent("album")
    try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
    let audio = nested.appendingPathComponent("audio.mp3")
    try Data([0]).write(to: audio)
    try Data([0]).write(to: directory.appendingPathComponent(".hidden.mp3"))
    try Data([0]).write(to: directory.appendingPathComponent("notes.txt"))
    try FileManager.default.createSymbolicLink(at: directory.appendingPathComponent("alias.mp3"), withDestinationURL: audio)
    let importer = LocalAudioImporter(library: MediaLibrary(directory: directory.appendingPathComponent("library")))
    let found = try await importer.audioFiles(in: directory)
    #expect(found.map { $0.resolvingSymlinksInPath().path } == [audio.resolvingSymlinksInPath().path])
}
